package com.android.build.gradle.integration.manageddevice.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomAndroidSdk
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomSdkDir
import com.android.build.gradle.integration.manageddevice.utils.simpleGMDProject
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.IntegerOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import java.io.File
import kotlin.io.path.pathString
import org.gradle.api.Project
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SimpleManagedDeviceTest(val runWithBuiltInPlatform: Boolean) {

  @get:Rule val customAndroidSdkRule = CustomAndroidSdkRule()

  val ruleBuilder = GradleRule.configure().withCustomSdkDir(customAndroidSdkRule)

  @get:Rule
  val rule =
    ruleBuilder.from {
      simpleGMDProject()
      gradleProperties { add(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform) }
      androidApplication { pluginCallbacks += ConfigureTestTaskCallback::class.java }
    }

  private val executor: GradleTaskExecutor
    get() = rule.build.executor.withCustomAndroidSdk(customAndroidSdkRule).withEnableInfoLogging(false)

  class ConfigureTestTaskCallback : GenericCallback {
    override fun handleProject(project: Project) {
      val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
      androidComponents.onVariants(androidComponents.selector().withName("debug")) { variant ->
        project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach { task ->
          if (task.name.contains(variant.name, ignoreCase = true)) {
            task.testLogging.apply {
              events("passed", "skipped", "failed")
              showStandardStreams = true
              showExceptions = true
              exceptionFormat = TestExceptionFormat.FULL
              showCauses = true
              showStackTraces = true
            }
          }
        }
      }
    }
  }

  private fun assertTestReportExists() {
    val reportDir =
      FileUtils.join(rule.build.androidApplication().buildDir.pathString, "reports", "androidTests", "managedDevice", "debug", "device1")
    assertThat(File(reportDir, "index.html")).exists()
    assertThat(File(reportDir, "com.example.android.kotlin.html")).exists()
    assertThat(File(reportDir, "com.example.android.kotlin.ExampleInstrumentedTest.html")).exists()

    val mergedTestReportDir =
      FileUtils.join(rule.build.androidApplication().buildDir.pathString, "reports", "androidTests", "managedDevice", "debug", "allDevices")
    assertThat(File(mergedTestReportDir, "index.html")).exists()
    assertThat(File(mergedTestReportDir, "com.example.android.kotlin.html")).exists()
    assertThat(File(mergedTestReportDir, "com.example.android.kotlin.ExampleInstrumentedTest.html")).exists()
  }

  private fun assertTestPassedInReport() {
    val xmlDir = File(rule.build.androidApplication().buildDir.pathString, "outputs/androidTest-results/managedDevice/debug/device1")
    val xmlFiles =
      xmlDir.walkTopDown().filter { file -> file.isFile && file.name.startsWith("TEST-device1") && file.name.endsWith(".xml") }.toList()
    com.google.common.truth.Truth.assertThat(xmlFiles).isNotEmpty()

    val testPassed =
      xmlFiles.any { file ->
        val content = file.readText()
        content.contains("""<testcase name="useAppContext" classname="com.example.android.kotlin.ExampleInstrumentedTest"""") &&
          content.contains("""failures="0"""") &&
          content.contains("""errors="0"""")
      }
    com.google.common.truth.Truth.assertWithMessage("useAppContext test case not found or failed in XML reports").that(testPassed).isTrue()
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }

  @Test
  fun runBasicManagedDevice() {
    val result = executor.withEnableInfoLogging(true).run(":app:device1DebugAndroidTest")

    assertTestReportExists()
    assertTestPassedInReport()
  }

  @Test
  fun runBasicManagedDeviceWithSharding() {
    val result = executor.with(IntegerOption.MANAGED_DEVICE_SHARD_POOL_SIZE, 2).run(":app:device1DebugAndroidTest")

    assertTestReportExists()
    assertTestPassedInReport()

    result.assertOutputContains("device1_0")
    result.assertOutputContains("device1_1")
  }
}
