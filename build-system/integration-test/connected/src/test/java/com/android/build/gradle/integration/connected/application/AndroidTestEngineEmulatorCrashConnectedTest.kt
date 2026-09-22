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

package com.android.build.gradle.integration.connected.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.utils.SdkHelper
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.integration.utp.applyAndroidTestConfiguration
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Connected integration test verifying that when an Android emulator or instrumentation process terminates or crashes unexpectedly
 * mid-test, the test execution reports (both XML and HTML) accurately record the in-flight test as a failure.
 *
 * This test ensures that if the test runner's adb connection drops or the instrumentation process abruptly exits while a test is executing,
 * [XmlTestRunListener] captures the premature termination and marks the in-flight test as failed in the generated `TEST-*.xml` and HTML
 * reports, rather than omitting the failure or incorrectly reporting all tests as passed.
 */
@RunWith(Parameterized::class)
class AndroidTestEngineEmulatorCrashConnectedTest(val runWithBuiltInPlatform: Boolean) {

  @Rule @JvmField val EMULATOR = getEmulator()

  @get:Rule
  val rule =
    GradleRule.configure().from {
      applyAndroidTestConfiguration(runWithBuiltInPlatform = runWithBuiltInPlatform)
      androidApplication {
        files {
          remove("src/androidTest/java/com/example/android/kotlin/InstrumentedTest.kt")
          add(
            "src/androidTest/java/com/example/android/kotlin/InstrumentedTest.kt",
            """
            package com.example.android.kotlin

            import android.util.Log
            import androidx.test.ext.junit.runners.AndroidJUnit4
            import org.junit.Test
            import org.junit.runner.RunWith

            @RunWith(AndroidJUnit4::class)
            class ExampleInstrumentedTest {
                @Test
                fun testWaitForever() {
                    // Log a marker so the host-side test knows execution has started on device.
                    Log.i("CrashTest", "TEST_STARTED")
                    // Sleep to wait for the host thread to kill the emulator while this test is running.
                    Thread.sleep(60_000)
                }
            }
            """
              .trimIndent(),
          )
        }
      }
    }

  @Test
  fun testReportRecordsFailureWhenEmulatorKilledMidTest() {
    val adbPath = SdkHelper.getAdb().absolutePath

    // Clear logcat buffer before running test
    try {
      ProcessBuilder(adbPath, "-s", "emulator-5554", "logcat", "-c").start().waitFor(5, TimeUnit.SECONDS)
    } catch (ignored: Exception) {}

    val emulatorKilled = AtomicBoolean(false)

    // Spawn a background killer thread on the host that polls for the logcat marker.
    // As soon as the test begins running on the device, the marker is detected and the emulator
    // is immediately killed via `adb emu kill`, creating an abrupt crash mid-test.
    val killerThread =
      Thread(
        {
          val startTime = System.currentTimeMillis()
          while (System.currentTimeMillis() - startTime < 120_000 && !emulatorKilled.get()) {
            try {
              val checkProcess = ProcessBuilder(adbPath, "-s", "emulator-5554", "logcat", "-d", "-s", "CrashTest:I").start()
              val output = checkProcess.inputStream.bufferedReader().readText().trim()
              checkProcess.waitFor(5, TimeUnit.SECONDS)
              if (output.contains("TEST_STARTED")) {
                // The test is actively executing on the emulator. Kill the emulator process now!
                val killProcess = ProcessBuilder(adbPath, "-s", "emulator-5554", "emu", "kill").start()
                killProcess.waitFor(5, TimeUnit.SECONDS)
                emulatorKilled.set(true)
                break
              }
            } catch (ignored: Exception) {}
            Thread.sleep(500)
          }
        },
        "EmulatorKillerThread",
      )

    killerThread.start()

    try {
      // Expect the Gradle connected test run to fail because the emulator died mid-test.
      rule.build.executor.expectFailure().run(":app:connectedAndroidTest")
    } finally {
      killerThread.interrupt()
      killerThread.join(5000)
    }

    // Verify that our killer thread successfully triggered and killed the emulator.
    assertThat(emulatorKilled.get()).isTrue()

    // Verify that the test result XML report was generated and explicitly recorded the test failure.
    val testOutputDir = rule.build.androidApplication().resolve("build/outputs/androidTest-results/connected/debug").toFile()
    val xmlFiles = testOutputDir.walkTopDown().filter { it.name.startsWith("TEST-") && it.name.endsWith(".xml") }.toList()
    assertThat(xmlFiles).isNotEmpty()

    val xmlContent = xmlFiles.first().readText()
    assertThat(xmlContent).contains("<failure>")
    assertThat(xmlContent).contains("testWaitForever")

    // If HTML report aggregation data (data.js) was generated, verify that it also reflects the failure.
    val reportsDir = rule.build.androidApplication().resolve("build/reports/androidTests").toFile()
    if (reportsDir.exists()) {
      val dataJsFiles = reportsDir.walkTopDown().filter { it.name == "data.js" }.toList()
      for (dataJs in dataJsFiles) {
        assertThat(dataJs.readText()).contains("\"status\":\"fail\"")
      }
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(true))
  }
}
