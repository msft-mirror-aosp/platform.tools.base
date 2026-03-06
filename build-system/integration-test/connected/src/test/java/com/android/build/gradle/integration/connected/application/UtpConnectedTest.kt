/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.AndroidDynamicFeatureProject
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.integration.common.utils.SdkHelper
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.integration.utp.UtpTestBase
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils
import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.perflogger.Benchmark
import com.google.common.truth.Truth.assertThat
import java.io.Closeable
import java.util.concurrent.TimeUnit
import org.junit.Assume
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Connected tests using UTP test executor. */
@RunWith(Parameterized::class)
class UtpConnectedTest(runWithBuiltInPlatform: Boolean) : UtpTestBase(runWithBuiltInPlatform) {
  private val connectedAndroidTestWithUtpBenchmark: Benchmark =
    Benchmark.Builder("connectedAndroidTestWithUtp").setProject("Android Studio Gradle").build()

  companion object {
    @ClassRule @JvmField val EMULATOR = getEmulator()
    private const val DEVICE_NAME = "emulator-5554 - 13"
    private const val TEST_OUTPUT_ROOT_DIR = "build/outputs/androidTest-results/connected/debug"
    private const val DEVICE_OUTPUT_DIR = "$TEST_OUTPUT_ROOT_DIR/$DEVICE_NAME"
    private const val LOGCAT = "$DEVICE_OUTPUT_DIR/logcat-com.example.android.kotlin.ExampleInstrumentedTest-useAppContext.txt"
    private const val LOGCAT_FOR_DYNAMIC_FEATURE =
      "$DEVICE_OUTPUT_DIR/logcat-com.example.android.kotlin.feature.ExampleInstrumentedTest-useAppContext.txt"
    private const val TEST_REPORT = "build/reports/androidTests/connected/debug/com.example.android.kotlin.html"
    private const val TEST_REPORT_FOR_DYNAMIC_FEATURE = "build/reports/androidTests/connected/debug/com.example.android.kotlin.feature.html"
    private const val TEST_RESULT_PB = "$DEVICE_OUTPUT_DIR/test-result.pb"
    private const val TEST_COV_XML = "build/reports/coverage/androidTest/debug/connected/report.xml"
    private const val ENABLE_UTP_TEST_REPORT_PROPERTY = "com.android.tools.utp.GradleAndroidProjectResolverExtension.enable"
    private const val TEST_ADDITIONAL_OUTPUT =
      "build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/$DEVICE_NAME"
  }

  override fun selectModule(moduleName: String) {
    val moduleTestOutputRootDir = "$moduleName/$TEST_OUTPUT_ROOT_DIR"
    testTaskName = ":$moduleName:connectedAndroidTest"
    testResultXmlPath =
      if (runWithBuiltInPlatform) {
        "$moduleTestOutputRootDir/TEST-$DEVICE_NAME.xml"
      } else {
        "$moduleTestOutputRootDir/TEST-$DEVICE_NAME-_$moduleName-.xml"
      }
    if (rule.build.subProject(":$moduleName") is AndroidDynamicFeatureProject) {
      testReportPath = "$moduleName/$TEST_REPORT_FOR_DYNAMIC_FEATURE"
      testLogcatPath = "$moduleName/$LOGCAT_FOR_DYNAMIC_FEATURE"
    } else {
      testReportPath = "$moduleName/$TEST_REPORT"
      testLogcatPath = "$moduleName/$LOGCAT"
    }
    testResultPbPath = "$moduleName/$TEST_RESULT_PB"
    testCoverageXmlPath = "$moduleName/$TEST_COV_XML"
    testAdditionalOutputPath = "$moduleName/$TEST_ADDITIONAL_OUTPUT"
  }

  @Test
  @Throws(Exception::class)
  fun connectedAndroidTestWithUtpTestResultListener() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    val benchmark: Benchmark =
      Benchmark.Builder("connectedAndroidTestWithUtpTestResultListener").setProject("Android Studio Gradle").build()
    val startTime: Long = System.currentTimeMillis()
    selectModule("app")
    val initScriptPath = TestUtils.resolveWorkspacePath("tools/adt/idea/utp/resources/utp/addGradleAndroidTestListener.gradle")

    var testExecutionStartTime: Long = System.currentTimeMillis()
    val result =
      executor
        .withArgument("--init-script")
        .withArgument(initScriptPath.toString())
        .withArgument("-P${ENABLE_UTP_TEST_REPORT_PROPERTY}=true")
        .run(testTaskName)
    var testExecutionTime = System.currentTimeMillis() - testExecutionStartTime
    connectedAndroidTestWithUtpBenchmark.log("connectedAndroidTestWithUtpTestResultListenerExecution_time", testExecutionTime)

    result.stdout.use {
      assertThat(it).contains("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
      assertThat(it).contains("</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
    }
    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()

    // Run the task again after clean. This time the task configuration is
    // restored from the configuration cache. We expect no crashes.
    executor.run("clean")

    assertThat(project.resolve(testReportPath)).doesNotExist()
    assertThat(project.resolve(testResultPbPath)).doesNotExist()

    testExecutionStartTime = System.currentTimeMillis()
    val resultWithConfigCache =
      executor
        .withArgument("--init-script")
        .withArgument(initScriptPath.toString())
        .withArgument("-P${ENABLE_UTP_TEST_REPORT_PROPERTY}=true")
        .run(testTaskName)
    testExecutionTime = System.currentTimeMillis() - testExecutionStartTime
    connectedAndroidTestWithUtpBenchmark.log(
      "connectedAndroidTestWithUtpTestResultListenerWithConfigCacheExecution_time",
      testExecutionTime,
    )

    resultWithConfigCache.stdout.use {
      assertThat(it).contains("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
      assertThat(it).contains("</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
    }
    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
    val timeTaken = System.currentTimeMillis() - startTime
    benchmark.log("connectedAndroidTestWithUtpTestResultListener_time", timeTaken)
  }

  @Test
  @Throws(Exception::class)
  fun connectedAndroidTestWithUtpTestResultListenerAndTestReportingDisabled() {
    val benchmark: Benchmark =
      Benchmark.Builder("connectedAndroidTestWithUtpTestResultListenerAndTestReportingDisabled").setProject("Android Studio Gradle").build()
    val startTime: Long = System.currentTimeMillis()
    selectModule("app")
    val initScriptPath = TestUtils.resolveWorkspacePath("tools/adt/idea/utp/resources/utp/addGradleAndroidTestListener.gradle")

    val testExecutionStartTime: Long = System.currentTimeMillis()
    val result = executor.withArgument("--init-script").withArgument(initScriptPath.toString()).run(testTaskName)
    val testExecutionTime = System.currentTimeMillis() - testExecutionStartTime
    connectedAndroidTestWithUtpBenchmark.log(
      "connectedAndroidTestWithUtpTestResultListenerAndTestReportingDisabledExecution_time",
      testExecutionTime,
    )

    result.stdout.use {
      assertThat(it).doesNotContain("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
      assertThat(it).doesNotContain("</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
    }
    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
    val timeTaken = System.currentTimeMillis() - startTime
    benchmark.log("connectedAndroidTestWithUtpTestResultListenerAndTestReportingDisabled_time", timeTaken)
  }

  @Test
  fun connectedAndroidTestShouldUninstallAppsAfterTest() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("lib")

    val result = executor.withEnableInfoLogging(true).run(testTaskName)

    result.assertOutputContains("Uninstalling com.example.android.kotlin.library.test")

    val result2 =
      executor.with(BooleanOption.ANDROID_TEST_LEAVE_APKS_INSTALLED_AFTER_RUN, true).withEnableInfoLogging(true).run(testTaskName)

    result2.assertOutputDoesNotContain("Uninstalling com.example.android.kotlin.library.test")
  }

  @Test
  fun additionalTestOutputWithTestStorageServiceInSecondaryUser() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    SecondaryUser().use { additionalTestOutputWithTestStorageService() }
  }

  @Test
  fun additionalTestOutputWithoutTestStorageServiceInSecondaryUser() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    SecondaryUser().use { additionalTestOutputWithoutTestStorageService() }
  }

  @Test
  fun additionalTestOutputWithBenchmarkFilesInSecondaryUser() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    SecondaryUser().use { additionalTestOutputWithBenchmarkFiles() }
  }

  /**
   * Creates a secondary user on device and makes it a current user. After closing this class, it deletes the secondary user and makes the
   * primary user to the current user. Please see https://source.android.com/docs/devices/admin/multi-user-testing.
   */
  private class SecondaryUser : Closeable {
    companion object {
      private fun createSecondaryUser(): Int {
        val process =
          ProcessBuilder(SdkHelper.getAdb().absolutePath, "-s", "emulator-5554", "shell", "pm", "create-user", "utpTestUser", "--ephemeral")
            .start()
        assertThat(process.waitFor(1, TimeUnit.MINUTES)).isTrue()
        val processOutput = process.inputStream.bufferedReader().use { it.readText() }
        val processError = process.errorStream.bufferedReader().use { it.readText() }
        val regexToExtractUserId = Regex(pattern = "Success: created user id (?<userId>\\d+)")
        return requireNotNull(regexToExtractUserId.find(processOutput)?.groups?.get("userId")?.value?.toInt()) {
          "Failed to create secondary user. pm create-user command failed with the output message: $processError"
        }
      }

      private fun switchCurrentUser(userId: Int) {
        val process =
          ProcessBuilder(SdkHelper.getAdb().absolutePath, "-s", "emulator-5554", "shell", "am", "switch-user", "-w", userId.toString())
            .start()
        assertThat(process.waitFor(1, TimeUnit.MINUTES)).isTrue()
      }

      private fun removeUser(userId: Int) {
        val process =
          ProcessBuilder(SdkHelper.getAdb().absolutePath, "-s", "emulator-5554", "shell", "pm", "remove-user", userId.toString()).start()
        assertThat(process.waitFor(1, TimeUnit.MINUTES)).isTrue()
      }
    }

    val secondaryUserId = createSecondaryUser()

    init {
      switchCurrentUser(secondaryUserId)
    }

    override fun close() {
      switchCurrentUser(0) // Switch back to the primary user (userId = 0).
      removeUser(secondaryUserId)
    }
  }
}
