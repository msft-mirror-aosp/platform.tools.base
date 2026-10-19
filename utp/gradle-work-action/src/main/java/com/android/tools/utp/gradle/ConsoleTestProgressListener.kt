/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.utp.gradle

import com.android.tools.androidtest.listener.AndroidTestReportKeys
import com.android.tools.androidtest.listener.getDeviceId
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

/**
 * A JUnit Platform [TestExecutionListener] that reports the progress of Android instrumentation tests to the Gradle console.
 *
 * For each device, it logs when the test run starts and finishes, logs failed and skipped tests, logs failures of the whole test run, and
 * periodically logs a progress status line (e.g. `"<device> Tests 3/10 completed. (1 skipped) (0 failed)"`) using a
 * [TestResultProgressTracker].
 *
 * @param deviceNames Maps a device serial to the name used in console messages. If a device is not in this map, its serial is used instead.
 * @param logger The Gradle logger to write messages to.
 * @param trackerFactory Creates a [TestResultProgressTracker] for the given device name.
 */
internal class ConsoleTestProgressListener(
  private val deviceNames: Map<String, String>,
  private val logger: Logger = Logging.getLogger(ConsoleTestProgressListener::class.java),
  private val trackerFactory: (String) -> TestResultProgressTracker = { deviceName ->
    TestResultProgressTracker(deviceName, logger = logger)
  },
) : TestExecutionListener {

  /** Progress trackers keyed by device serial. */
  private val trackers = ConcurrentHashMap<String, TestResultProgressTracker>()

  /** Serials of devices whose test count has already been reported. */
  private val testCountReportedDevices = ConcurrentHashMap.newKeySet<String>()

  override fun reportingEntryPublished(testIdentifier: TestIdentifier, entry: ReportEntry) {
    val deviceId = testIdentifier.getDeviceId()?.takeIf { it.isNotEmpty() } ?: return
    entry.keyValuePairs[AndroidTestReportKeys.TEST_COUNT]?.toIntOrNull()?.let { testCount -> onTestCountReported(deviceId, testCount) }
    entry.keyValuePairs[AndroidTestReportKeys.RUN_FAILURE]?.let { errorMessage -> logRunFailure(deviceId, errorMessage) }
  }

  private fun onTestCountReported(deviceId: String, testCount: Int) {
    if (!testCountReportedDevices.add(deviceId)) return
    // A tracker may already exist if a test event arrived before the test count was reported. In that case, keep its progress and
    // update the number of scheduled tests.
    val tracker = trackers.computeIfAbsent(deviceId) { trackerFactory(getDeviceName(deviceId)) }
    logger.lifecycle("Starting $testCount tests on ${tracker.status.deviceName}")
    tracker.onTestSuiteStarted(testCount)
  }

  override fun executionSkipped(testIdentifier: TestIdentifier, reason: String) {
    if (!testIdentifier.isTest) return
    val deviceId = testIdentifier.getDeviceId()?.takeIf { it.isNotEmpty() } ?: return
    val tracker = getOrCreateTracker(deviceId)
    logger.warn(formatTestMessage(testIdentifier, tracker, SKIPPED))
    tracker.onTestSkipped()
  }

  override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
    val deviceId = testIdentifier.getDeviceId()?.takeIf { it.isNotEmpty() } ?: return

    if (testIdentifier.isContainer) {
      // A container with a device ID represents the test run on that device.
      if (testExecutionResult.status == TestExecutionResult.Status.FAILED) {
        logRunFailure(deviceId, testExecutionResult.throwable.map { it.message ?: it.javaClass.name }.orElse(""))
      }
      testCountReportedDevices.remove(deviceId)
      trackers.remove(deviceId)?.onTestSuiteFinished()
      return
    }

    if (!testIdentifier.isTest) return
    val tracker = getOrCreateTracker(deviceId)
    // The Android Test Engine reports the instrumentation stack trace from the device as the exception message, so use the message
    // rather than the host-side stack trace of the exception.
    val message = testExecutionResult.throwable.map { it.message ?: it.javaClass.name }.orElse(null)
    when (testExecutionResult.status) {
      TestExecutionResult.Status.SUCCESSFUL -> {
        logger.info(formatTestMessage(testIdentifier, tracker, SUCCESS))
        tracker.onTestPassed()
      }
      TestExecutionResult.Status.FAILED -> {
        logger.warn(formatTestMessage(testIdentifier, tracker, FAILED))
        message?.let { logger.warn(getModifiedTrace(it)) }
        tracker.onTestFailed()
      }
      // The Android Test Engine reports ignored tests and assumption failures as aborted.
      TestExecutionResult.Status.ABORTED -> {
        // Only assumption failures come with a stack trace. Ignored tests only have a placeholder message such as "Test ignored", which is
        // not logged.
        val trace = message?.takeIf { it.contains('\n') }?.let { "\n${getModifiedTrace(it)}" } ?: ""
        logger.warn(formatTestMessage(testIdentifier, tracker, SKIPPED) + trace)
        tracker.onTestSkipped()
      }
      else -> {}
    }
  }

  override fun testPlanExecutionFinished(testPlan: TestPlan) {
    // Make sure no periodic logging outlives the test plan, e.g. if a device container never finished.
    trackers.values.forEach { it.cancel() }
    trackers.clear()
    testCountReportedDevices.clear()
  }

  /** Returns the tracker for [deviceId], creating one if the test count has not been reported yet. */
  private fun getOrCreateTracker(deviceId: String): TestResultProgressTracker =
    trackers.computeIfAbsent(deviceId) { trackerFactory(getDeviceName(deviceId)).apply { onTestSuiteStarted(0) } }

  private fun getDeviceName(deviceId: String): String = deviceNames[deviceId] ?: deviceId

  private fun logRunFailure(deviceId: String, errorMessage: String) {
    logger.warn("Tests on ${getDeviceName(deviceId)} failed: $errorMessage")
  }

  private fun formatTestMessage(testIdentifier: TestIdentifier, tracker: TestResultProgressTracker, status: String): String {
    val methodSource = testIdentifier.source.orElse(null) as? MethodSource
    val className = methodSource?.className ?: testIdentifier.displayName.substringBeforeLast(".")
    val methodName = methodSource?.methodName ?: testIdentifier.displayName.substringAfterLast(".")
    return "\n$className > $methodName[${tracker.status.deviceName}] $status"
  }

  /** Returns the first two lines of [trace], each prefixed with a tab. */
  private fun getModifiedTrace(trace: String): String {
    val lines = trace.split("\n")
    if (lines.size < 2) {
      return trace
    }
    return "\t${lines[0]}\n\t${lines[1]}"
  }

  private companion object {
    const val SUCCESS = "\u001B[32mSUCCESS \u001B[0m"
    const val FAILED = "\u001B[31mFAILED \u001B[0m"
    const val SKIPPED = "\u001B[33mSKIPPED \u001B[0m"
  }
}
