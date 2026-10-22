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
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import java.util.Timer
import org.gradle.api.logging.Logger
import org.junit.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ConsoleTestProgressListenerTest {

  private val mockLogger: Logger = mock()
  private val timers = mutableMapOf<String, Timer>()
  private val timerActions = mutableMapOf<String, () -> Unit>()

  private val listener =
    ConsoleTestProgressListener(
      deviceNames = mapOf("serial1" to "Pixel_3a(AVD) - 13"),
      logger = mockLogger,
      trackerFactory = { deviceName ->
        TestResultProgressTracker(deviceName, 200L, mockLogger) { _, action ->
          timerActions[deviceName] = action
          mock<Timer>().also { timers[deviceName] = it }
        }
      },
    )

  private fun deviceContainer(serial: String): TestIdentifier {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", serial)
    val identifier = mock<TestIdentifier>()
    whenever(identifier.isContainer).thenReturn(true)
    whenever(identifier.isTest).thenReturn(false)
    whenever(identifier.uniqueId).thenReturn(uniqueId.toString())
    whenever(identifier.uniqueIdObject).thenReturn(uniqueId)
    whenever(identifier.displayName).thenReturn(serial)
    whenever(identifier.source).thenReturn(Optional.empty())
    return identifier
  }

  private fun testCase(serial: String, className: String, methodName: String): TestIdentifier {
    val uniqueId = UniqueId.forEngine("android-test-engine").append("device", serial).append("test", "$className.$methodName")
    val identifier = mock<TestIdentifier>()
    whenever(identifier.isContainer).thenReturn(false)
    whenever(identifier.isTest).thenReturn(true)
    whenever(identifier.uniqueId).thenReturn(uniqueId.toString())
    whenever(identifier.uniqueIdObject).thenReturn(uniqueId)
    whenever(identifier.displayName).thenReturn("$className.$methodName")
    whenever(identifier.source).thenReturn(Optional.of(MethodSource.from(className, methodName)))
    return identifier
  }

  // The Android Test Engine publishes each key in a separate report entry.
  private fun testCountEntry(testCount: Int): ReportEntry = ReportEntry.from(AndroidTestReportKeys.TEST_COUNT, testCount.toString())

  private fun displayNameEntry(displayName: String): ReportEntry = ReportEntry.from(AndroidTestReportKeys.DEVICE_DISPLAY_NAME, displayName)

  private fun runFailureEntry(errorMessage: String): ReportEntry = ReportEntry.from(AndroidTestReportKeys.RUN_FAILURE, errorMessage)

  @Test
  fun logsProgressAndSummaryForDevice() {
    val device = deviceContainer("serial1")
    val test1 = testCase("serial1", "com.example.ExampleTest", "test1")
    val test2 = testCase("serial1", "com.example.ExampleTest", "test2")
    val test3 = testCase("serial1", "com.example.ExampleTest", "test3")
    val test4 = testCase("serial1", "com.example.ExampleTest", "test4")

    listener.executionStarted(device)
    listener.reportingEntryPublished(device, testCountEntry(4))
    listener.reportingEntryPublished(device, displayNameEntry("Pixel 3a"))
    listener.executionFinished(test1, TestExecutionResult.successful())
    timerActions.getValue("Pixel_3a(AVD) - 13").invoke()
    listener.executionFinished(
      test2,
      TestExecutionResult.failed(RuntimeException("java.lang.AssertionError: boom\n\tat Foo.bar\n\tat Baz")),
    )
    listener.executionFinished(test3, TestExecutionResult.aborted(RuntimeException("Test ignored")))
    timerActions.getValue("Pixel_3a(AVD) - 13").invoke()
    // No change since the last status, so nothing should be logged.
    timerActions.getValue("Pixel_3a(AVD) - 13").invoke()
    listener.executionSkipped(test4, "disabled")
    listener.executionFinished(device, TestExecutionResult.successful())

    inOrder(mockLogger, timers.getValue("Pixel_3a(AVD) - 13")).apply {
      verify(mockLogger).lifecycle("Starting 4 tests on Pixel_3a(AVD) - 13")
      verify(mockLogger).info("\ncom.example.ExampleTest > test1[Pixel_3a(AVD) - 13] \u001B[32mSUCCESS \u001B[0m")
      verify(mockLogger).lifecycle("Pixel_3a(AVD) - 13 Tests 1/4 completed. (0 skipped) (0 failed)")
      verify(mockLogger).warn("\ncom.example.ExampleTest > test2[Pixel_3a(AVD) - 13] \u001B[31mFAILED \u001B[0m")
      verify(mockLogger).warn("\tjava.lang.AssertionError: boom\n\t\tat Foo.bar")
      // The placeholder message of ignored tests is not logged.
      verify(mockLogger).warn("\ncom.example.ExampleTest > test3[Pixel_3a(AVD) - 13] \u001B[33mSKIPPED \u001B[0m")
      verify(mockLogger).lifecycle("Pixel_3a(AVD) - 13 Tests 3/4 completed. (1 skipped) (1 failed)")
      verify(mockLogger).warn("\ncom.example.ExampleTest > test4[Pixel_3a(AVD) - 13] \u001B[33mSKIPPED \u001B[0m")
      verify(timers.getValue("Pixel_3a(AVD) - 13")).cancel()
      verify(mockLogger).lifecycle("Finished 4 tests on Pixel_3a(AVD) - 13")
      verifyNoMoreInteractions()
    }
  }

  @Test
  fun assumptionFailure_logsSkippedWithTrace() {
    val device = deviceContainer("serial1")
    val test = testCase("serial1", "com.example.ExampleTest", "test")

    listener.reportingEntryPublished(device, testCountEntry(1))
    listener.executionFinished(
      test,
      TestExecutionResult.aborted(RuntimeException("org.junit.AssumptionViolatedException: nope\n\tat Foo.bar\n\tat Baz")),
    )

    verify(mockLogger)
      .warn(
        "\ncom.example.ExampleTest > test[Pixel_3a(AVD) - 13] \u001B[33mSKIPPED \u001B[0m\n" +
          "\torg.junit.AssumptionViolatedException: nope\n\t\tat Foo.bar"
      )
  }

  @Test
  fun tracksDevicesIndependently() {
    val device1 = deviceContainer("serial1")
    val device2 = deviceContainer("serial2")

    listener.reportingEntryPublished(device1, testCountEntry(2))
    listener.reportingEntryPublished(device2, testCountEntry(3))
    listener.executionFinished(testCase("serial1", "com.example.A", "a"), TestExecutionResult.successful())
    listener.executionFinished(testCase("serial2", "com.example.B", "b"), TestExecutionResult.successful())
    listener.executionFinished(testCase("serial2", "com.example.B", "c"), TestExecutionResult.successful())
    timerActions.getValue("Pixel_3a(AVD) - 13").invoke()
    // serial2 is not in deviceNames, so its serial is used.
    timerActions.getValue("serial2").invoke()

    verify(mockLogger).lifecycle("Starting 2 tests on Pixel_3a(AVD) - 13")
    verify(mockLogger).lifecycle("Starting 3 tests on serial2")
    verify(mockLogger).lifecycle("Pixel_3a(AVD) - 13 Tests 1/2 completed. (0 skipped) (0 failed)")
    verify(mockLogger).lifecycle("serial2 Tests 2/3 completed. (0 skipped) (0 failed)")
  }

  @Test
  fun testCountReportedTwice_startsOnlyOnce() {
    val device = deviceContainer("serial1")

    listener.reportingEntryPublished(device, testCountEntry(2))
    listener.reportingEntryPublished(device, testCountEntry(5))

    verify(mockLogger).lifecycle("Starting 2 tests on Pixel_3a(AVD) - 13")
    verify(mockLogger, never()).lifecycle("Starting 5 tests on Pixel_3a(AVD) - 13")
  }

  @Test
  fun ignoresUnrelatedReportEntries() {
    val device = deviceContainer("serial1")

    listener.reportingEntryPublished(device, ReportEntry.from(AndroidTestReportKeys.LOGCAT_PATH, "/tmp/logcat"))
    listener.reportingEntryPublished(device, displayNameEntry("Pixel 3a"))

    assertThat(timers).isEmpty()
    verify(mockLogger, never()).lifecycle(any<String>())
    verify(mockLogger, never()).warn(any<String>())
  }

  @Test
  fun runFailureReported_logsFailure() {
    val device = deviceContainer("serial1")

    listener.reportingEntryPublished(device, testCountEntry(0))
    listener.reportingEntryPublished(device, runFailureEntry("Test run failed to complete. Process crashed."))
    listener.executionFinished(device, TestExecutionResult.successful())

    inOrder(mockLogger).apply {
      verify(mockLogger).lifecycle("Starting 0 tests on Pixel_3a(AVD) - 13")
      verify(mockLogger).warn("Tests on Pixel_3a(AVD) - 13 failed: Test run failed to complete. Process crashed.")
      verify(mockLogger).lifecycle("Finished 0 tests on Pixel_3a(AVD) - 13")
    }
  }

  @Test
  fun runFailureReportedBeforeTestCount_logsFailure() {
    val device = deviceContainer("serial1")

    listener.reportingEntryPublished(device, runFailureEntry("Failed to install APKs"))

    verify(mockLogger).warn("Tests on Pixel_3a(AVD) - 13 failed: Failed to install APKs")
  }

  @Test
  fun deviceRunFailed_logsFailureAndSummary() {
    val device = deviceContainer("serial1")

    listener.reportingEntryPublished(device, testCountEntry(1))
    listener.executionFinished(device, TestExecutionResult.failed(RuntimeException("Instrumentation run failed")))

    inOrder(mockLogger).apply {
      verify(mockLogger).lifecycle("Starting 1 tests on Pixel_3a(AVD) - 13")
      verify(mockLogger).warn("Tests on Pixel_3a(AVD) - 13 failed: Instrumentation run failed")
      verify(mockLogger).lifecycle("Finished 0 tests on Pixel_3a(AVD) - 13")
    }
  }

  @Test
  fun testFinishedBeforeTestCountReported_stillTracksProgress() {
    val device = deviceContainer("serial1")

    listener.executionFinished(testCase("serial1", "com.example.A", "a"), TestExecutionResult.successful())
    timerActions.getValue("Pixel_3a(AVD) - 13").invoke()
    listener.executionFinished(device, TestExecutionResult.successful())

    verify(mockLogger).lifecycle("Pixel_3a(AVD) - 13 Tests 1/0 completed. (0 skipped) (0 failed)")
    verify(mockLogger).lifecycle("Finished 1 tests on Pixel_3a(AVD) - 13")
  }

  @Test
  fun testFinishedBeforeTestCountReported_updatesScheduledTestCount() {
    val device = deviceContainer("serial1")

    listener.executionSkipped(testCase("serial1", "com.example.A", "a"), "disabled")
    listener.reportingEntryPublished(device, testCountEntry(3))
    listener.executionFinished(testCase("serial1", "com.example.A", "b"), TestExecutionResult.successful())
    timerActions.getValue("Pixel_3a(AVD) - 13").invoke()
    listener.executionFinished(device, TestExecutionResult.successful())

    inOrder(mockLogger).apply {
      verify(mockLogger).lifecycle("Starting 3 tests on Pixel_3a(AVD) - 13")
      verify(mockLogger).lifecycle("Pixel_3a(AVD) - 13 Tests 2/3 completed. (1 skipped) (0 failed)")
      verify(mockLogger).lifecycle("Finished 2 tests on Pixel_3a(AVD) - 13")
    }
  }

  @Test
  fun testPlanExecutionFinished_cancelsUnfinishedTrackers() {
    val device = deviceContainer("serial1")

    listener.reportingEntryPublished(device, testCountEntry(1))
    listener.testPlanExecutionFinished(mock<TestPlan>())

    verify(timers.getValue("Pixel_3a(AVD) - 13")).cancel()
    verify(mockLogger, never()).lifecycle("Finished 0 tests on Pixel_3a(AVD) - 13")
  }
}
