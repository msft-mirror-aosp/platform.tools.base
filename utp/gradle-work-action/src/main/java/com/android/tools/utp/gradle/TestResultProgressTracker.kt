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

import java.util.Timer
import kotlin.concurrent.timer
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

private const val DEFAULT_LOG_STATUS_DELAY_MS = 10 * 1000L

/**
 * Tracks the progress of a test suite running on a single device and periodically logs a status line such as `"<device> Tests 3/10
 * completed. (1 skipped) (0 failed)"` to the Gradle console.
 *
 * The status is only logged when it has changed since the last time it was logged.
 *
 * This is a port of `com.android.build.gradle.internal.testing.TestResultProgressTracker` in gradle-core, which cannot be used here because
 * this work action runs in an isolated class loader. Consider applying fixes to both copies.
 */
internal class TestResultProgressTracker(
  deviceName: String,
  val logStatusDelayMS: Long = DEFAULT_LOG_STATUS_DELAY_MS,
  val logger: Logger = Logging.getLogger(TestResultProgressTracker::class.java),
  val timerFactory: (Long, () -> Unit) -> Timer = { delay, action ->
    // Use a daemon thread so that a test suite which never finishes cannot keep the worker process alive.
    timer(name = "TestResultProgressTracker", daemon = true, initialDelay = delay, period = delay) { action() }
  },
) {

  private var logTimer: Timer? = null

  val status = TestSuiteStatus(deviceName)

  class TestSuiteStatus(val deviceName: String) {
    private var testsFailedCount = 0
    private var testsSkippedCount = 0
    private var testsScheduledCount = 0
    private var testsCompletedCount = 0
    private var statusChanged = false

    val finishedTests: Int
      get() = synchronized(this) { testsCompletedCount }

    /** Returns the current status and clears the changed flag if the status has changed since the last call, or null otherwise. */
    fun getStatusIfChanged(): String? =
      synchronized(this) {
        if (!statusChanged) return null
        statusChanged = false
        getStatus()
      }

    fun addCompletedTest() {
      synchronized(this) {
        statusChanged = true
        ++testsCompletedCount
      }
    }

    fun addFailedTest() {
      synchronized(this) {
        statusChanged = true
        ++testsCompletedCount
        ++testsFailedCount
      }
    }

    fun addSkippedTest() {
      synchronized(this) {
        statusChanged = true
        ++testsCompletedCount
        ++testsSkippedCount
      }
    }

    fun scheduleTests(amount: Int) {
      synchronized(this) {
        statusChanged = true
        testsScheduledCount = amount
      }
    }

    fun getStatus(): String =
      synchronized(this) {
        "$deviceName Tests $testsCompletedCount/$testsScheduledCount completed. " +
          "($testsSkippedCount skipped) ($testsFailedCount failed)"
      }
  }

  fun logStatus() {
    status.getStatusIfChanged()?.let { logger.lifecycle(it) }
  }

  /**
   * Called by the timer. [Timer.cancel] does not stop a task which is already running, so check under the lock that the timer has not been
   * cancelled to make sure that no status line is logged after the summary.
   */
  @Synchronized
  private fun logStatusIfRunning() {
    if (logTimer != null) {
      logStatus()
    }
  }

  @Synchronized
  fun onTestSuiteStarted(scheduledTestCaseCount: Int) {
    status.scheduleTests(scheduledTestCaseCount)
    logTimer?.cancel()
    logTimer = timerFactory(logStatusDelayMS, ::logStatusIfRunning)
  }

  fun onTestFailed() = status.addFailedTest()

  fun onTestPassed() = status.addCompletedTest()

  fun onTestSkipped() = status.addSkippedTest()

  @Synchronized
  fun onTestSuiteFinished() {
    cancel()
    logger.lifecycle("Finished ${status.finishedTests} tests on ${status.deviceName}")
  }

  /** Stops periodic status logging without logging the final summary. */
  @Synchronized
  fun cancel() {
    logTimer?.cancel()
    logTimer = null
  }
}
