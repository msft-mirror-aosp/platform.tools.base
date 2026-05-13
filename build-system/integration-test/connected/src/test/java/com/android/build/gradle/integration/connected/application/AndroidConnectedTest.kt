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

import com.android.build.gradle.integration.common.fixture.project.AndroidDynamicFeatureProject
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.integration.common.utils.SdkHelper
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.integration.utp.AndroidTestUtil
import com.android.build.gradle.integration.utp.applyAndroidTestConfiguration
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils
import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.perflogger.Benchmark
import com.google.common.truth.Truth.assertThat
import java.io.Closeable
import java.util.concurrent.TimeUnit
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Connected tests using Android Test executor. */
@RunWith(Parameterized::class)
class AndroidConnectedTest(val runWithBuiltInPlatform: Boolean) {
  private val connectedAndroidTestWithUtpBenchmark: Benchmark =
    Benchmark.Builder("connectedAndroidTestWithUtp").setProject("Android Studio Gradle").build()

  @Rule @JvmField val EMULATOR = getEmulator()

  val ruleBuilder = GradleRule.configure()

  @get:Rule val rule = ruleBuilder.from { applyAndroidTestConfiguration(runWithBuiltInPlatform) }

  private val util =
    AndroidTestUtil(
      runWithBuiltInPlatform = runWithBuiltInPlatform,
      rule = rule,
      onSelectModule = { moduleName, util ->
        val moduleTestOutputRootDir = "$moduleName/$TEST_OUTPUT_ROOT_DIR"
        util.testTaskName = ":$moduleName:connectedAndroidTest"
        util.testResultXmlPath =
          if (util.runWithBuiltInPlatform) {
            "$moduleTestOutputRootDir/TEST-$DEVICE_NAME.xml"
          } else {
            "$moduleTestOutputRootDir/TEST-$DEVICE_NAME-_$moduleName-.xml"
          }
        if (rule.build.subProject(":$moduleName") is AndroidDynamicFeatureProject) {
          util.testReportPath = "$moduleName/$TEST_REPORT_FOR_DYNAMIC_FEATURE"
          util.testLogcatPath = "$moduleName/$LOGCAT_FOR_DYNAMIC_FEATURE"
        } else {
          util.testReportPath = "$moduleName/$TEST_REPORT"
          util.testLogcatPath = "$moduleName/$LOGCAT"
        }
        util.testResultPbPath = "$moduleName/$TEST_RESULT_PB"
        util.testCoverageXmlPath = "$moduleName/$TEST_COV_XML"
        util.testAdditionalOutputPath = "$moduleName/$TEST_ADDITIONAL_OUTPUT"
      },
    )

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

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

  @Test fun androidTestWithCodeCoverage() = util.androidTestWithCodeCoverage()

  @Test fun androidTestWithTestFailures() = util.androidTestWithTestFailures()

  @Test fun androidTest() = util.androidTest()

  @Test fun androidTestWithOrchestrator() = util.androidTestWithOrchestrator()

  @Test fun androidTestWithOrchestratorAndCodeCoverage() = util.androidTestWithOrchestratorAndCodeCoverage()

  @Test fun connectedAndroidTestWithLogcat() = util.connectedAndroidTestWithLogcat()

  @Test fun connectedAndroidTestFromTestOnlyModule() = util.connectedAndroidTestFromTestOnlyModule()

  @Test fun additionalTestOutputWithTestStorageService() = util.additionalTestOutputWithTestStorageService()

  @Test fun additionalTestOutputWithoutTestStorageService() = util.additionalTestOutputWithoutTestStorageService()

  @Test fun additionalTestOutputWithBenchmarkFiles() = util.additionalTestOutputWithBenchmarkFiles()

  @Test fun additionalTestOutputWithBenchmarkV3Files() = util.additionalTestOutputWithBenchmarkV3Files()

  @Test fun androidTestWithDynamicFeature() = util.androidTestWithDynamicFeature()

  @Test fun androidTestWithOrchestratorWithDynamicFeature() = util.androidTestWithOrchestratorWithDynamicFeature()

  @Test fun connectedAndroidTestWithLogcatWithDynamicFeature() = util.connectedAndroidTestWithLogcatWithDynamicFeature()

  @Test
  fun connectedAndroidTestWithAdditionalTestOutputUsingTestStorageServiceWithDynamicFeature() =
    util.connectedAndroidTestWithAdditionalTestOutputUsingTestStorageServiceWithDynamicFeature()

  @Test fun androidTestWithForceCompilation() = util.androidTestWithForceCompilation()

  @Test
  fun androidTestWithOrchestratorAndCodeCoverageWithDynamicFeature() = util.androidTestWithOrchestratorAndCodeCoverageWithDynamicFeature()

  @Test fun androidTestWithCodeCoverageWithDynamicFeature() = util.androidTestWithCodeCoverageWithDynamicFeature()

  @Test fun runAndroidTestWithNoTestClasses() = util.runAndroidTestWithNoTestClasses()

  @Test fun connectedAndroidTestDoesNotOutputNoClassDefFoundError() = util.connectedAndroidTestDoesNotOutputNoClassDefFoundError()

  @Test
  @Throws(Exception::class)
  fun connectedAndroidTestWithUtpTestResultListener() {
    val benchmark: Benchmark =
      Benchmark.Builder("connectedAndroidTestWithUtpTestResultListener").setProject("Android Studio Gradle").build()
    val startTime: Long = System.currentTimeMillis()
    util.selectModule("app")
    val initScriptPath = TestUtils.resolveWorkspacePath("tools/adt/idea/utp/resources/utp/addGradleAndroidTestListener.gradle")

    var testExecutionStartTime: Long = System.currentTimeMillis()
    val result =
      util.executor
        .withArgument("--init-script")
        .withArgument(initScriptPath.toString())
        .withArgument("-P${ENABLE_UTP_TEST_REPORT_PROPERTY}=true")
        .run(util.testTaskName)
    var testExecutionTime = System.currentTimeMillis() - testExecutionStartTime
    connectedAndroidTestWithUtpBenchmark.log("connectedAndroidTestWithUtpTestResultListenerExecution_time", testExecutionTime)

    result.stdout.use {
      assertThat(it).contains("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
      assertThat(it).contains("</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
    }
    assertThat(util.project.resolve(util.testReportPath)).exists()
    assertThat(util.project.resolve(util.testResultPbPath)).exists()

    // Run the task again after clean. This time the task configuration is
    // restored from the configuration cache. We expect no crashes.
    util.executor.run("clean")

    assertThat(util.project.resolve(util.testReportPath)).doesNotExist()
    assertThat(util.project.resolve(util.testResultPbPath)).doesNotExist()

    testExecutionStartTime = System.currentTimeMillis()
    val resultWithConfigCache =
      util.executor
        .withArgument("--init-script")
        .withArgument(initScriptPath.toString())
        .withArgument("-P${ENABLE_UTP_TEST_REPORT_PROPERTY}=true")
        .run(util.testTaskName)
    testExecutionTime = System.currentTimeMillis() - testExecutionStartTime
    connectedAndroidTestWithUtpBenchmark.log(
      "connectedAndroidTestWithUtpTestResultListenerWithConfigCacheExecution_time",
      testExecutionTime,
    )

    resultWithConfigCache.stdout.use {
      assertThat(it).contains("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
      assertThat(it).contains("</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
    }
    assertThat(util.project.resolve(util.testReportPath)).exists()
    assertThat(util.project.resolve(util.testResultPbPath)).exists()
    val timeTaken = System.currentTimeMillis() - startTime
    benchmark.log("connectedAndroidTestWithUtpTestResultListener_time", timeTaken)
  }

  @Test
  @Throws(Exception::class)
  fun connectedAndroidTestWithUtpTestResultListenerAndTestReportingDisabled() {
    val benchmark: Benchmark =
      Benchmark.Builder("connectedAndroidTestWithUtpTestResultListenerAndTestReportingDisabled").setProject("Android Studio Gradle").build()
    val startTime: Long = System.currentTimeMillis()
    util.selectModule("app")
    val initScriptPath = TestUtils.resolveWorkspacePath("tools/adt/idea/utp/resources/utp/addGradleAndroidTestListener.gradle")

    val testExecutionStartTime: Long = System.currentTimeMillis()
    val result = util.executor.withArgument("--init-script").withArgument(initScriptPath.toString()).run(util.testTaskName)
    val testExecutionTime = System.currentTimeMillis() - testExecutionStartTime
    connectedAndroidTestWithUtpBenchmark.log(
      "connectedAndroidTestWithUtpTestResultListenerAndTestReportingDisabledExecution_time",
      testExecutionTime,
    )

    result.stdout.use {
      assertThat(it).doesNotContain("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
      assertThat(it).doesNotContain("</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
    }
    assertThat(util.project.resolve(util.testReportPath)).exists()
    assertThat(util.project.resolve(util.testResultPbPath)).exists()
    val timeTaken = System.currentTimeMillis() - startTime
    benchmark.log("connectedAndroidTestWithUtpTestResultListenerAndTestReportingDisabled_time", timeTaken)
  }

  @Test
  @Throws(Exception::class)
  fun androidTestWithOrchestratorAndCodeCoverageAndCorruptedLeftover() {
    util.selectModule("app")

    util.rule.build.androidApplication().reconfigure {
      android.testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"
      android.defaultConfig.testInstrumentationRunnerArguments["useTestStorageService"] = "true"
      android.defaultConfig.testInstrumentationRunnerArguments["clearPackageData"] = "true"

      dependencies {
        add("androidTestUtil", "androidx.test:orchestrator:${AndroidTestUtil.ANDROIDX_TEST_VERSION}")
        add("androidTestUtil", "androidx.test.services:test-services:${AndroidTestUtil.ANDROIDX_TEST_VERSION}")
      }
      android.buildTypes.apply { named("debug") { it.enableAndroidTestCoverage = true } }
    }

    val adb = SdkHelper.getAdb().absolutePath
    val coverageDir = "/sdcard/googletest/internal_use/data/data/com.example.android.kotlin/coverage_data"
    val corruptedFile = "$coverageDir/corrupted.ec"

    // Manually create a corrupted leftover file on device.
    // We create it after the emulator rule has started the emulator.
    ProcessBuilder(adb, "shell", "mkdir", "-p", coverageDir).start().waitFor(1, TimeUnit.MINUTES)
    ProcessBuilder(adb, "shell", "echo", "not-a-jacoco-file", ">", corruptedFile).start().waitFor(1, TimeUnit.MINUTES)

    // Run the test. The plugin should clean up the directory before pulling files.
    // If it doesn't, JacocoReportTask will fail with "Unknown block type".
    util.executor.run(util.testTaskName)

    assertThat(util.project.resolve(util.testReportPath)).exists()
    assertThat(util.project.resolve(util.testCoverageXmlPath)).exists()

    // Verify the corrupted file is gone from the device.
    val checkProcess = ProcessBuilder(adb, "shell", "ls", corruptedFile).start()
    checkProcess.waitFor(1, TimeUnit.MINUTES)
    assertThat(checkProcess.exitValue()).isNotEqualTo(0)
  }

  @Test
  fun connectedAndroidTestShouldUninstallAppsAfterTest() {
    util.selectModule("lib")

    val result = util.executor.withEnableInfoLogging(true).run(util.testTaskName)

    result.assertOutputContains("Uninstalling com.example.android.kotlin.library.test")

    val result2 =
      util.executor.with(BooleanOption.ANDROID_TEST_LEAVE_APKS_INSTALLED_AFTER_RUN, true).withEnableInfoLogging(true).run(util.testTaskName)

    result2.assertOutputDoesNotContain("Uninstalling com.example.android.kotlin.library.test")
  }

  @Test
  fun additionalTestOutputWithTestStorageServiceInSecondaryUser() {
    SecondaryUser().use { util.additionalTestOutputWithTestStorageService() }
  }

  @Test
  fun additionalTestOutputWithoutTestStorageServiceInSecondaryUser() {
    SecondaryUser().use { util.additionalTestOutputWithoutTestStorageService() }
  }

  @Test
  fun additionalTestOutputWithBenchmarkFilesInSecondaryUser() {
    SecondaryUser().use { util.additionalTestOutputWithBenchmarkFiles() }
  }

  /**
   * Creates a secondary user on device and makes it a current user. After closing this class, it deletes the secondary user and makes the
   * primary user to the current user. Please see https://source.android.com/docs/devices/admin/multi-user-testing.
   */
  private class SecondaryUser : Closeable {
    companion object {
      private fun removeAllSecondaryUsers() {
        // Ensure we are on user 0 before removing others
        switchCurrentUser(0)

        val process = ProcessBuilder(SdkHelper.getAdb().absolutePath, "-s", "emulator-5554", "shell", "pm", "list", "users").start()
        assertThat(process.waitFor(1, TimeUnit.MINUTES)).isTrue()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val userRegex = Regex("""UserInfo\{(\d+):""")
        userRegex.findAll(output).map { it.groupValues[1].toInt() }.filter { it != 0 }.forEach { userId -> removeUser(userId) }
      }

      private fun createSecondaryUser(): Int {
        var processOutput = ""
        var processError = ""
        repeat(3) {
          val process =
            ProcessBuilder(
                SdkHelper.getAdb().absolutePath,
                "-s",
                "emulator-5554",
                "shell",
                "pm",
                "create-user",
                "utpTestUser",
                "--ephemeral",
              )
              .start()
          if (process.waitFor(1, TimeUnit.MINUTES)) {
            processOutput = process.inputStream.bufferedReader().use { it.readText() }
            processError = process.errorStream.bufferedReader().use { it.readText() }
            if (processOutput.contains("Success: created user id")) {
              val regexToExtractUserId = Regex(pattern = "Success: created user id (?<userId>\\d+)")
              val userId = requireNotNull(regexToExtractUserId.find(processOutput)?.groups?.get("userId")?.value?.toInt())
              return userId
            }
          }
        }
        throw IllegalStateException("Failed to create secondary user after 3 attempts. Output: $processOutput, Error: $processError")
      }

      private fun switchCurrentUser(userId: Int) {
        var switched = false
        repeat(3) {
          if (switched) return@repeat
          val process =
            ProcessBuilder(SdkHelper.getAdb().absolutePath, "-s", "emulator-5554", "shell", "am", "switch-user", "-w", userId.toString())
              .start()
          process.waitFor(1, TimeUnit.MINUTES)

          // Double check current user
          val checkProcess =
            ProcessBuilder(SdkHelper.getAdb().absolutePath, "-s", "emulator-5554", "shell", "am", "get-current-user").start()
          if (checkProcess.waitFor(1, TimeUnit.MINUTES)) {
            val currentUser = checkProcess.inputStream.bufferedReader().use { it.readText().trim() }
            if (currentUser == userId.toString()) {
              switched = true
            }
          }
        }
        if (!switched) {
          throw IllegalStateException("Failed to switch to user $userId after 3 attempts")
        }
      }

      private fun removeUser(userId: Int) {
        val process =
          ProcessBuilder(SdkHelper.getAdb().absolutePath, "-s", "emulator-5554", "shell", "pm", "remove-user", userId.toString()).start()
        assertThat(process.waitFor(1, TimeUnit.MINUTES)).isTrue()
      }
    }

    init {
      removeAllSecondaryUsers()
      val secondaryUserId = createSecondaryUser()
      switchCurrentUser(secondaryUserId)
    }

    override fun close() {
      removeAllSecondaryUsers()
    }
  }
}
