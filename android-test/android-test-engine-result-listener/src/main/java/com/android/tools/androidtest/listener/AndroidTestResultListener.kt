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

package com.android.tools.androidtest.listener

import com.android.tools.androidtest.listener.proto.TestResultEventProto.TestResultEvent
import com.google.protobuf.Any
import com.google.testing.platform.proto.api.core.ErrorProto
import com.google.testing.platform.proto.api.core.TestCaseProto
import com.google.testing.platform.proto.api.core.TestResultProto
import com.google.testing.platform.proto.api.core.TestStatusProto
import com.google.testing.platform.proto.api.core.TestSuiteResultProto
import java.io.File
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

/**
 * A JUnit Platform [TestExecutionListener] that converts test results into TestResultProtos and emits them as base64 encoded strings to
 * stdout.
 *
 * This listener maps the JUnit Platform's hierarchical test model to the flat, per-device test-result proto model. In the test-result proto
 * model, a TestSuite is expected to run on a single device. Therefore, this listener tracks test execution per device and emits
 * TestSuiteStarted/Finished events when a device container starts/finishes.
 */
class AndroidTestResultListener : TestExecutionListener {

  companion object {
    private val logger = Logger.getLogger(AndroidTestResultListener::class.java.name)
  }

  /** Tracks whether all tests have passed for each device serial. */
  private val perDeviceAllTestsPassed = ConcurrentHashMap<String, Boolean>()

  /** Tracks whether TestSuiteStarted event has been emitted for each device serial. */
  private val perDeviceTestSuiteStartedEmitted = ConcurrentHashMap<String, Boolean>()

  /** Tracks device display name for each device serial. */
  private val perDeviceDisplayName = ConcurrentHashMap<String, String>()

  /** Tracks device info file path for each device serial. */
  private val perDeviceDeviceInfoPath = ConcurrentHashMap<String, String>()

  /** Tracks test results for each device serial. */
  private val perDeviceTestResults = ConcurrentHashMap<String, MutableList<TestResultProto.TestResult>>()

  /**
   * Tracks logcat file paths for each test. UniqueId -> LogcatPath.
   *
   * Paths are published via [reportingEntryPublished] and consumed in [executionFinished].
   */
  private val testLogcatFiles = ConcurrentHashMap<String, String>()

  /** Properties loaded from the file specified by AGP environment variable. */
  private val configurationProperties: Properties by lazy {
    val properties = Properties()
    val path = System.getenv("com.android.junit.engine.input.parameters")
    if (path != null) {
      val file = File(path)
      if (file.exists()) {
        try {
          file.inputStream().use { properties.load(it) }
        } catch (t: Throwable) {
          logger.log(Level.SEVERE, "failed to load configuration from $path", t)
        }
      }
    }
    properties
  }

  /** Returns the property value for the given [key], checking system properties first. */
  private fun getProperty(key: String, defaultValue: String? = null): String? {
    return System.getProperty(key) ?: configurationProperties.getProperty(key) ?: defaultValue
  }

  override fun testPlanExecutionStarted(testPlan: TestPlan) {
    // No-op. Test suite events are reported per device in executionStarted/executionFinished.
    // This is because the test-result proto model maps a TestSuite to a single device.
  }

  override fun executionStarted(testIdentifier: TestIdentifier) {
    val deviceId = testIdentifier.getDeviceId() ?: ""

    // If the identifier is a container and has a device ID, it represents an AndroidDeviceDescriptor.
    // We defer TestSuiteStarted until we receive the test count via reportingEntryPublished,
    // or until the first test case starts.
    if (testIdentifier.isContainer && deviceId.isNotEmpty()) {
      perDeviceAllTestsPassed[deviceId] = true
      perDeviceTestSuiteStartedEmitted[deviceId] = false
      return
    }

    // Report individual test case start.
    if (!testIdentifier.isTest) return
    try {
      // Ensure TestSuiteStarted is emitted before any TestCaseStarted.
      if (deviceId.isNotEmpty()) {
        emitTestSuiteStarted(deviceId, 0)
      }

      val testCase = testIdentifier.toTestCaseProto()
      val testCaseStarted = TestResultEvent.TestCaseStarted.newBuilder().setTestCase(Any.pack(testCase)).build()

      val event = TestResultEvent.newBuilder().setTestCaseStarted(testCaseStarted).setDeviceId(deviceId).build()

      printTestResultEvent(event)
    } catch (t: Throwable) {
      logger.log(Level.SEVERE, "failed to report executionStarted for ${testIdentifier.displayName}", t)
    }
  }

  override fun reportingEntryPublished(testIdentifier: TestIdentifier, entry: ReportEntry) {
    val deviceId = testIdentifier.getDeviceId() ?: ""

    val testCount = entry.keyValuePairs[AndroidTestReportKeys.TEST_COUNT]?.toIntOrNull()
    if (testCount != null && deviceId.isNotEmpty()) {
      emitTestSuiteStarted(deviceId, testCount)
    }

    val displayName = entry.keyValuePairs[AndroidTestReportKeys.DEVICE_DISPLAY_NAME]
    if (displayName != null) {
      perDeviceDisplayName[deviceId] = displayName
    }

    val deviceInfoPath = entry.keyValuePairs[AndroidTestReportKeys.DEVICE_INFO_PATH]
    if (deviceInfoPath != null) {
      perDeviceDeviceInfoPath[deviceId] = deviceInfoPath
    }

    val logcatPath = entry.keyValuePairs[AndroidTestReportKeys.LOGCAT_PATH]
    if (logcatPath != null) {
      testLogcatFiles[testIdentifier.uniqueId] = logcatPath
    }
  }

  /** Emits a TestSuiteStarted event for the given [deviceId] with the specified [scheduledTestCount]. */
  private fun emitTestSuiteStarted(deviceId: String, scheduledTestCount: Int) {
    if (perDeviceTestSuiteStartedEmitted[deviceId] == true) return
    try {
      val testSuiteMetaData = TestSuiteResultProto.TestSuiteMetaData.newBuilder().setScheduledTestCaseCount(scheduledTestCount).build()
      val testSuiteStarted = TestResultEvent.TestSuiteStarted.newBuilder().setTestSuiteMetadata(Any.pack(testSuiteMetaData)).build()
      val event = TestResultEvent.newBuilder().setTestSuiteStarted(testSuiteStarted).setDeviceId(deviceId).build()
      printTestResultEvent(event)
      perDeviceTestSuiteStartedEmitted[deviceId] = true
    } catch (t: Throwable) {
      logger.log(Level.SEVERE, "failed to report testSuiteStarted for device $deviceId", t)
    }
  }

  override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
    val deviceId = testIdentifier.getDeviceId() ?: ""

    // If the identifier is a container and has a device ID, it represents an AndroidDeviceDescriptor.
    // We emit a TestSuiteFinished event for this specific device.
    if (testIdentifier.isContainer && deviceId.isNotEmpty()) {
      try {
        // Ensure TestSuiteStarted was emitted.
        emitTestSuiteStarted(deviceId, 0)

        val allTestsPassed = perDeviceAllTestsPassed.getOrDefault(deviceId, true)
        val testResults = perDeviceTestResults[deviceId] ?: emptyList<TestResultProto.TestResult>()
        val testSuiteResult =
          TestSuiteResultProto.TestSuiteResult.newBuilder()
            .setTestStatus(if (allTestsPassed) TestStatusProto.TestStatus.PASSED else TestStatusProto.TestStatus.FAILED)
            .addAllTestResult(testResults)
            .apply {
              perDeviceDeviceInfoPath[deviceId]?.let { path ->
                addOutputArtifactBuilder().apply {
                  labelBuilder.label = "device-info"
                  labelBuilder.namespace = "android"
                  sourcePathBuilder.path = path
                }
              }
            }
            .build()

        val resultsFilePath =
          getProperty(AndroidTestResultListenerKeys.TEST_RESULTS_FILE)
            ?: getProperty(AndroidTestResultListenerKeys.RESULTS_DIR)?.let { resultsDir ->
              val dirName = perDeviceDisplayName[deviceId] ?: deviceId
              File(resultsDir, dirName).resolve("test-result.pb").absolutePath
            }

        if (resultsFilePath != null) {
          try {
            val file = File(resultsFilePath)
            file.parentFile?.mkdirs()
            file.outputStream().use { testSuiteResult.writeTo(it) }
          } catch (t: Throwable) {
            logger.log(Level.SEVERE, "failed to write test results to $resultsFilePath", t)
          }
        }

        val testSuiteFinished = TestResultEvent.TestSuiteFinished.newBuilder().setTestSuiteResult(Any.pack(testSuiteResult)).build()
        val event = TestResultEvent.newBuilder().setTestSuiteFinished(testSuiteFinished).setDeviceId(deviceId).build()
        printTestResultEvent(event)
      } catch (t: Throwable) {
        logger.log(Level.SEVERE, "failed to report testSuiteFinished for device $deviceId", t)
      }
      return
    }

    // Report individual test case result.
    if (!testIdentifier.isTest) return
    try {
      val status = testExecutionResult.toTestStatus()
      if (status != TestStatusProto.TestStatus.PASSED) {
        perDeviceAllTestsPassed[deviceId] = false
      }

      val testCaseProto = testIdentifier.toTestCaseProto()
      val testResult =
        TestResultProto.TestResult.newBuilder()
          .setTestCase(testCaseProto)
          .setTestStatus(status)
          .apply {
            testExecutionResult.throwable.ifPresent { t ->
              setError(
                ErrorProto.Error.newBuilder()
                  .setErrorMessage(t.message ?: "")
                  .setErrorType(t.javaClass.name)
                  .setStackTrace(t.stackTraceToString())
                  .build()
              )
            }
            // Attach the logcat artifact if it was published for this test case.
            // We remove it from the map to avoid memory leaks after it's been processed.
            val logcatPath = testLogcatFiles.remove(testIdentifier.uniqueId)
            if (logcatPath != null) {
              addOutputArtifactBuilder().apply {
                labelBuilder.label = "logcat"
                labelBuilder.namespace = "android"
                sourcePathBuilder.path = logcatPath
              }
            }
          }
          .build()

      if (deviceId.isNotEmpty()) {
        perDeviceTestResults.getOrPut(deviceId) { mutableListOf() }.add(testResult)
      }

      val testCaseFinished = TestResultEvent.TestCaseFinished.newBuilder().setTestCaseResult(Any.pack(testResult)).build()

      val event = TestResultEvent.newBuilder().setTestCaseFinished(testCaseFinished).setDeviceId(deviceId).build()

      printTestResultEvent(event)
    } catch (t: Throwable) {
      logger.log(Level.SEVERE, "failed to report executionFinished for ${testIdentifier.displayName}", t)
    }
  }

  override fun testPlanExecutionFinished(testPlan: TestPlan) {
    // No-op. Test suite events are reported per device in executionStarted/executionFinished.
  }

  /** Encodes the [TestResultEvent] proto as a base64 string and prints it to stdout wrapped in UTP tags, if enabled by system property. */
  @Synchronized
  private fun printTestResultEvent(event: TestResultEvent) {
    if (!getProperty(AndroidTestResultListenerKeys.STREAM_BASE64_ENCODED_RESULT, "false").toBoolean()) {
      return
    }

    val encodedEvent = Base64.getEncoder().encodeToString(event.toByteArray())
    val message = "<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>$encodedEvent</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>"

    val streamingFilePath = getProperty(AndroidTestResultListenerKeys.STREAMING_RESULTS_FILE)
    if (streamingFilePath != null) {
      try {
        File(streamingFilePath).appendText(message + "\n")
      } catch (t: Throwable) {
        logger.log(Level.SEVERE, "failed to write to streaming file: $streamingFilePath", t)
      }
    } else {
      try {
        println(message)
      } catch (t: Throwable) {
        logger.log(Level.SEVERE, "failed to emit event to stdout", t)
      }
    }
  }

  /** Converts a [TestIdentifier] to a [TestCaseProto.TestCase]. */
  private fun TestIdentifier.toTestCaseProto(): TestCaseProto.TestCase {
    val className =
      source
        .map { s ->
          when (s) {
            is org.junit.platform.engine.support.descriptor.ClassSource -> s.className
            is org.junit.platform.engine.support.descriptor.MethodSource -> s.className
            else -> ""
          }
        }
        .orElse("")

    val methodName =
      source
        .map { s ->
          when (s) {
            is org.junit.platform.engine.support.descriptor.MethodSource -> s.methodName
            else -> legacyReportingName
          }
        }
        .orElse(legacyReportingName)

    val pkg =
      if (className.contains(".")) {
        className.substringBeforeLast(".")
      } else {
        ""
      }
    val simpleClassName = className.substringAfterLast(".")

    return TestCaseProto.TestCase.newBuilder().setTestClass(simpleClassName).setTestMethod(methodName).setTestPackage(pkg).build()
  }

  /** Maps JUnit Platform [TestExecutionResult.Status] to test-result [TestStatusProto.TestStatus]. */
  private fun TestExecutionResult.toTestStatus(): TestStatusProto.TestStatus {
    return when (status) {
      TestExecutionResult.Status.SUCCESSFUL -> TestStatusProto.TestStatus.PASSED
      TestExecutionResult.Status.FAILED -> TestStatusProto.TestStatus.FAILED
      TestExecutionResult.Status.ABORTED -> TestStatusProto.TestStatus.ABORTED
      else -> TestStatusProto.TestStatus.TEST_STATUS_UNSPECIFIED
    }
  }

  /**
   * Extracts the device ID from the [TestIdentifier.uniqueId] string.
   *
   * Android Studio expects this to be the device serial ID.
   */
  private fun TestIdentifier.getDeviceId(): String? {
    val devicePart = uniqueId.substringAfterLast("[device:", "")
    return if (devicePart.isNotEmpty()) {
      devicePart.substringBefore("]")
    } else {
      null
    }
  }
}
