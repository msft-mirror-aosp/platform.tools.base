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

package com.android.build.gradle.integration.manageddevice.application

import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.project.AndroidDynamicFeatureProject
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomAndroidSdk
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomSdkDir
import com.android.build.gradle.integration.manageddevice.utils.addManagedDevice
import com.android.build.gradle.integration.utp.AndroidTestUtil
import com.android.build.gradle.integration.utp.applyAndroidTestConfiguration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** An integration test for Gradle Managed Device. */
@RunWith(Parameterized::class)
class AndroidManagedDeviceTest(val runWithBuiltInPlatform: Boolean) {

  @get:Rule val customAndroidSdkRule = CustomAndroidSdkRule()

  val ruleBuilder = GradleRule.configure().withCustomSdkDir(customAndroidSdkRule)

  @get:Rule val rule = ruleBuilder.from { applyAndroidTestConfiguration(runWithBuiltInPlatform) }

  private val util =
    AndroidTestUtil(
      runWithBuiltInPlatform = runWithBuiltInPlatform,
      rule = rule,
      customExecutor = { it.apply { withCustomAndroidSdk(customAndroidSdkRule) } },
      onSelectModule = { moduleName, util ->
        rule.build.subProject(":$moduleName").reconfigure {
          this as AndroidProjectDefinition<out CommonExtension>
          addManagedDevice(DSL_DEVICE_NAME)
        }

        util.testTaskName = ":${moduleName}:allDevicesCheck"
        val deviceName = DSL_DEVICE_NAME
        val deviceOutputDir = "$TEST_RESULTS/$deviceName"

        util.testResultXmlPath =
          if (util.runWithBuiltInPlatform) {
            "${moduleName}/$deviceOutputDir/TEST-$deviceName.xml"
          } else {
            "${moduleName}/$deviceOutputDir/TEST-$DSL_DEVICE_NAME-_$moduleName-.xml"
          }

        util.testReportPath =
          "${moduleName}/$REPORTS/androidTests/managedDevice/debug/$deviceName/com.example.android.kotlin${if (rule.build.subProject(":$moduleName") is AndroidDynamicFeatureProject) ".feature" else ""}.html"
        util.testLogcatPath =
          "${moduleName}/$deviceOutputDir/logcat-com.example.android.kotlin${if (rule.build.subProject(":$moduleName") is AndroidDynamicFeatureProject) ".feature" else ""}.ExampleInstrumentedTest-useAppContext.txt"

        util.testResultPbPath = "${moduleName}/$deviceOutputDir/test-result.pb"
        util.testCoverageXmlPath = "${moduleName}/$TEST_COV_XML"
        util.testAdditionalOutputPath = "${moduleName}/build/outputs/managed_device_android_test_additional_output/debug/$deviceName"
      },
    )

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

    private const val DSL_DEVICE_NAME = "device1"
    private const val BUILT_IN_DEVICE_NAME = "emulator-5554 - 13"

    private const val OUTPUTS = "build/outputs"
    private const val TEST_ADDITIONAL_OUTPUT = "$OUTPUTS/managed_device_android_test_additional_output/debug/$DSL_DEVICE_NAME"
    private const val TEST_RESULTS = "$OUTPUTS/androidTest-results/managedDevice/debug"
    private const val TEST_RESULT_XML = "$TEST_RESULTS/$DSL_DEVICE_NAME/TEST-$DSL_DEVICE_NAME-_"
    private const val LOGCAT = "$TEST_RESULTS/$DSL_DEVICE_NAME/logcat-com.example.android.kotlin.ExampleInstrumentedTest-useAppContext.txt"
    private const val LOGCAT_FOR_DYNAMIC_FEATURE =
      "$TEST_RESULTS/$DSL_DEVICE_NAME/logcat-com.example.android.kotlin.feature.ExampleInstrumentedTest-useAppContext.txt"
    private const val TEST_RESULT_PB = "$TEST_RESULTS/$DSL_DEVICE_NAME/test-result.pb"

    private const val REPORTS = "build/reports"
    private const val TEST_REPORT = "$REPORTS/androidTests/managedDevice/debug/$DSL_DEVICE_NAME/com.example.android.kotlin.html"
    private const val TEST_REPORT_FOR_DYNAMIC_FEATURE =
      "$REPORTS/androidTests/managedDevice/debug/$DSL_DEVICE_NAME/com.example.android.kotlin.feature.html"
    private const val TEST_COV_XML = "$REPORTS/coverage/androidTest/debug/managedDevice/report.xml"
  }

  @Test
  fun runAndroidTestWithNoTestClasses() {
    // TODO(b/476442048): Implement built-in test platform for Managed Device.
    util.runAndroidTestWithNoTestClasses()
  }

  @Test
  fun androidTestWithCodeCoverage() {
    util.androidTestWithCodeCoverage()
  }

  @Test
  fun androidTestWithTestFailures() {
    util.androidTestWithTestFailures()
  }

  @Test fun androidTest() = util.androidTest()

  @Test
  fun androidTestWithOrchestrator() {
    util.androidTestWithOrchestrator()
  }

  @Test
  fun androidTestWithOrchestratorAndCodeCoverage() {
    util.androidTestWithOrchestratorAndCodeCoverage()
  }

  @Test
  fun connectedAndroidTestWithLogcat() {
    util.connectedAndroidTestWithLogcat()
  }

  @Test
  fun connectedAndroidTestFromTestOnlyModule() {
    util.connectedAndroidTestFromTestOnlyModule()
  }

  @Test
  fun additionalTestOutputWithTestStorageService() {
    util.additionalTestOutputWithTestStorageService()
  }

  @Test
  fun additionalTestOutputWithoutTestStorageService() {
    util.additionalTestOutputWithoutTestStorageService()
  }

  @Test
  fun additionalTestOutputWithBenchmarkFiles() {
    util.additionalTestOutputWithBenchmarkFiles()
  }

  @Test
  fun additionalTestOutputWithBenchmarkV3Files() {
    util.additionalTestOutputWithBenchmarkV3Files()
  }

  @Test
  fun androidTestWithDynamicFeature() {
    util.androidTestWithDynamicFeature()
  }

  @Test
  fun androidTestWithOrchestratorWithDynamicFeature() {
    util.androidTestWithOrchestratorWithDynamicFeature()
  }

  @Test
  fun connectedAndroidTestWithLogcatWithDynamicFeature() {
    util.connectedAndroidTestWithLogcatWithDynamicFeature()
  }

  @Test
  fun connectedAndroidTestWithAdditionalTestOutputUsingTestStorageServiceWithDynamicFeature() {
    util.connectedAndroidTestWithAdditionalTestOutputUsingTestStorageServiceWithDynamicFeature()
  }

  @Test
  fun androidTestWithForceCompilation() {
    util.androidTestWithForceCompilation()
  }

  @Test
  fun androidTestWithOrchestratorAndCodeCoverageWithDynamicFeature() = util.androidTestWithOrchestratorAndCodeCoverageWithDynamicFeature()

  @Test fun androidTestWithCodeCoverageWithDynamicFeature() = util.androidTestWithCodeCoverageWithDynamicFeature()

  @Test
  fun connectedAndroidTestDoesNotOutputNoClassDefFoundError() {
    util.connectedAndroidTestDoesNotOutputNoClassDefFoundError()
  }
}
