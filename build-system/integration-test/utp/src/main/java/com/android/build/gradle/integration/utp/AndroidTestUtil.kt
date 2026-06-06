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

package com.android.build.gradle.integration.utp

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.utp.plugins.host.device.info.proto.AndroidTestDeviceInfoProto.AndroidTestDeviceInfo
import com.google.common.truth.Truth.assertThat
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.Assume

class AndroidTestUtil(
  val runWithBuiltInPlatform: Boolean,
  val rule: GradleRule,
  val customExecutor: (GradleTaskExecutor) -> GradleTaskExecutor = { it },
  val onSelectModule: (String, AndroidTestUtil) -> Unit,
) {
  companion object {
    const val ANDROIDX_TEST_VERSION = "1.5.0-alpha02"
  }

  lateinit var testTaskName: String
  lateinit var testReportPath: String
  lateinit var testResultXmlPath: String
  lateinit var testResultPbPath: String
  lateinit var testCoverageXmlPath: String
  lateinit var testLogcatPath: String
  lateinit var testAdditionalOutputPath: String
  lateinit var moduleName: String

  val project: Path
    get() = rule.build.directory

  val executor: GradleTaskExecutor
    get() =
      customExecutor(
        rule.build.executor.withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.PROJECT_ISOLATION).withEnableInfoLogging(false)
      )

  fun selectModule(moduleName: String) {
    this.moduleName = moduleName
    onSelectModule(moduleName, this)
  }

  private fun resolvePath(relativePath: String): Path {
    val path = project.resolve(relativePath)
    if (runWithBuiltInPlatform && !path.exists()) {
      // For built-in platform, the output might be in a different level of directory.
      // Search for the file in the parent or siblings.
      val fileName = path.fileName.toString()
      // Search in the parent directory and its children.
      val parent = path.parent
      if (parent.exists()) {
        val found = parent.toFile().walkTopDown().maxDepth(3).find { it.name == fileName }
        if (found != null) {
          return found.toPath()
        }
      }
      // If not found, try searching from the parent of the parent (siblings of parent).
      val grandParent = parent.parent
      if (grandParent.exists()) {
        val foundInGrandParent = grandParent.toFile().walkTopDown().maxDepth(3).find { it.name == fileName }
        if (foundInGrandParent != null) {
          return foundInGrandParent.toPath()
        }
      }
    }
    return path
  }

  private fun resolveTestResultPbPath(): Path {
    val path = project.resolve(testResultPbPath)
    if (runWithBuiltInPlatform && !path.exists()) {
      // For built-in platform, the listener might have appended a device-specific subdirectory.
      val pbFile = path.parent.toFile().walkTopDown().maxDepth(2).find { it.name == "test-result.pb" }
      if (pbFile != null) {
        return pbFile.toPath()
      }
    }
    return path
  }

  fun enableAndroidTestOrchestrator(projectDef: AndroidProjectDefinition<out CommonExtension>) {
    projectDef.android.testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"
    projectDef.android.defaultConfig.testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    projectDef.android.defaultConfig.testInstrumentationRunnerArguments["clearPackageData"] = "true"

    projectDef.dependencies {
      add("androidTestUtil", "androidx.test:orchestrator:$ANDROIDX_TEST_VERSION")
      add("androidTestUtil", "androidx.test.services:test-services:$ANDROIDX_TEST_VERSION")
    }
  }

  fun enableForceCompilation(projectDef: AndroidProjectDefinition<out CommonExtension>) {
    projectDef.android.experimentalProperties["android.experimental.force-aot-compilation"] = true
  }

  fun enableCodeCoverage(projectDef: AndroidProjectDefinition<out CommonExtension>) {
    projectDef.android.buildTypes.apply { named("debug") { it.enableAndroidTestCoverage = true } }
    projectDef.android.defaultConfig.testInstrumentationRunnerArguments["useTestStorageService"] = "true"

    projectDef.dependencies { add("androidTestUtil", "androidx.test.services:test-services:$ANDROIDX_TEST_VERSION") }
  }

  fun enableOnTheFlyCoverage(projectDef: AndroidProjectDefinition<out CommonExtension>) {
    projectDef.android.experimentalProperties["android.experimental.testOptions.coverage.coverageType"] = "ON_THE_FLY"
    projectDef.dependencies { add("androidTestImplementation", "com.android.tools.test:coverage-agent:1.0.0") }
  }

  fun enableTestStorageService(projectDef: AndroidProjectDefinition<out CommonExtension>) {
    projectDef.android.defaultConfig.testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    projectDef.dependencies { add("androidTestUtil", "androidx.test.services:test-services:$ANDROIDX_TEST_VERSION") }
  }

  fun enableDynamicFeature(projectDef: AndroidProjectDefinition<out ApplicationExtension>, subProjectName: String) {
    projectDef.android.dynamicFeatures.add(":$subProjectName")
  }

  fun getDeviceInfo(testResultPb: File): AndroidTestDeviceInfo? {
    val testSuiteResult = testResultPb.inputStream().use { TestSuiteResult.parseFrom(it) }
    return (testSuiteResult.testResultList.asSequence().flatMap { it.outputArtifactList } + testSuiteResult.outputArtifactList.asSequence())
      .filter { artifact -> artifact.label.label == "device-info" && artifact.label.namespace == "android" }
      .map { artifact -> File(artifact.sourcePath.path).inputStream().use { AndroidTestDeviceInfo.parseFrom(it) } }
      .firstOrNull()
  }

  fun androidTestWithCodeCoverage() {
    selectModule("app")
    rule.build.androidApplication().reconfigure { enableCodeCoverage(this) }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(resolveTestResultPbPath()).exists()
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<method name="stubFuncForTestingCodeCoverage" desc="()V" line="9">""")
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<counter type="INSTRUCTION" missed="3" covered="5"/>""")
  }

  fun androidTestWithOnTheFlyCoverage() {
    Assume.assumeTrue("On-the-fly coverage is only supported on the built-in platform", runWithBuiltInPlatform)
    selectModule("app")
    rule.build.androidApplication().reconfigure { enableOnTheFlyCoverage(this) }

    val result = executor.with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true).withEnableInfoLogging(true).run(testTaskName)

    result.assertOutputContains("Agent extraction VERIFIED")
    result.assertOutputContains("-e listener com.android.tools.coverage.CoverageAgentAttacher")
    result.assertOutputContains("-e coverage-agent-config")

    // Verify binary artifacts were pulled to the host.
    // We search in a targeted directory based on the test platform.
    val coverageSearchDir =
      if (runWithBuiltInPlatform) {
          project.resolve("$moduleName/build/intermediates/test_suite_code_coverage")
        } else {
          project.resolve("$moduleName/build/reports/coverage")
        }
        .toFile()

    val hitsFile = coverageSearchDir.walkTopDown().find { it.name == "coverage_hits.pb" }
    val metadataFile = coverageSearchDir.walkTopDown().find { it.name == "coverage_metadata.pb" }

    assertThat(hitsFile).named("coverage_hits.pb in $coverageSearchDir").isNotNull()
    assertThat(metadataFile).named("coverage_metadata.pb in $coverageSearchDir").isNotNull()
    assertThat(hitsFile?.exists()).isTrue()
    assertThat(metadataFile?.exists()).isTrue()
  }

  fun androidTestWithTestFailures() {
    selectModule("app")

    rule.build.androidApplication().reconfigure {
      files {
        add(
          "src/androidTest/java/com/example/android/kotlin/FailingInstrumentedTest.kt",
          // language=kotlin
          """
          package com.example.android.kotlin

          import androidx.test.ext.junit.runners.AndroidJUnit4

          import org.junit.Assert
          import org.junit.Test
          import org.junit.runner.RunWith

          @RunWith(AndroidJUnit4::class)
          class FailingInstrumentedTest {

              @Test
              fun useAppContext() {
                  Assert.fail()
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.expectFailure().run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(resolveTestResultPbPath()).exists()
  }

  fun androidTest() {
    selectModule("app")

    executor.run(testTaskName)

    verifyReport(enableReportAggregation = false)

    val testResultPb = resolveTestResultPbPath()
    assertThat(testResultPb).exists()

    val testSuiteResult = testResultPb.toFile().inputStream().use { TestSuiteResult.parseFrom(it) }
    assertThat(testSuiteResult.testResultCount).isAtLeast(1)
    assertThat(testSuiteResult.testResultList.any { it.testCase.testMethod == "useAppContext" }).isTrue()
  }

  fun androidTestWithNewReportFormat() {
    selectModule("app")

    rule.build.reconfigureGradleProperties { add(BooleanOption.REPORT_AGGREGATION_SUPPORT, true) }

    executor.run(testTaskName)

    verifyReport(enableReportAggregation = true)

    val testResultPb = resolveTestResultPbPath()
    assertThat(testResultPb).exists()

    val testSuiteResult = testResultPb.toFile().inputStream().use { TestSuiteResult.parseFrom(it) }
    assertThat(testSuiteResult.testResultCount).isAtLeast(1)
    assertThat(testSuiteResult.testResultList.any { it.testCase.testMethod == "useAppContext" }).isTrue()
  }

  fun androidTestWithOrchestrator() {
    selectModule("app")

    rule.build.androidApplication().reconfigure { enableAndroidTestOrchestrator(this) }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(resolveTestResultPbPath()).exists()
  }

  fun androidTestWithOrchestratorAndCodeCoverage() {
    selectModule("app")

    rule.build.androidApplication().reconfigure {
      enableAndroidTestOrchestrator(this)
      enableCodeCoverage(this)
    }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(resolveTestResultPbPath()).exists()
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<method name="stubFuncForTestingCodeCoverage" desc="()V" line="9">""")
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<counter type="INSTRUCTION" missed="3" covered="5"/>""")
  }

  fun connectedAndroidTestWithLogcat() {
    selectModule("app")

    executor.run(testTaskName)

    assertThat(resolvePath(testLogcatPath)).exists()
    val logcatText = resolvePath(testLogcatPath).readText()
    assertThat(logcatText).contains("TestRunner: started: useAppContext(com.example.android.kotlin.ExampleInstrumentedTest)")
    assertThat(logcatText).contains("TestLogger: test logs")
    assertThat(logcatText).contains("TestRunner: finished: useAppContext(com.example.android.kotlin.ExampleInstrumentedTest)")
  }

  fun connectedAndroidTestFromTestOnlyModule() {
    selectModule("test")

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(resolveTestResultPbPath()).exists()
  }

  fun additionalTestOutputWithTestStorageService() {
    selectModule("app")

    rule.build.androidApplication().reconfigure {
      enableTestStorageService(this)
      files {
        add(
          "src/androidTest/java/com/example/helloworld/TestStorageServiceExampleTest.kt",
          // language=kotlin
          """
          package com.example.helloworld

          import android.util.Log
          import androidx.test.ext.junit.runners.AndroidJUnit4
          import androidx.test.services.storage.TestStorage
          import org.junit.Test
          import org.junit.runner.RunWith

          @RunWith(AndroidJUnit4::class)
          class TestStorageServiceExampleTest {
              @Test
              fun writeFileUsingTestStorageService() {
                  TestStorage().openOutputFile("myTestStorageOutputFile1").use {
                      it.write("output message1".toByteArray())
                  }
                  TestStorage().openOutputFile("myTestStorageOutputFile2.txt").use {
                      it.write("output message2".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/myTestStorageOutputFile3").use {
                      it.write("output message3".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/nested/myTestStorageOutputFile4").use {
                      it.write("output message4".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/white space/myTestStorageOutputFile5").use {
                      it.write("output message5".toByteArray())
                  }
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.run(testTaskName)

    assertThat(resolvePath("${testAdditionalOutputPath}/myTestStorageOutputFile1")).contains("output message1")
    assertThat(resolvePath("${testAdditionalOutputPath}/myTestStorageOutputFile2.txt")).contains("output message2")
    assertThat(resolvePath("${testAdditionalOutputPath}/subdir/myTestStorageOutputFile3")).contains("output message3")
    assertThat(resolvePath("${testAdditionalOutputPath}/subdir/nested/myTestStorageOutputFile4")).contains("output message4")
    assertThat(resolvePath("${testAdditionalOutputPath}/subdir/white space/myTestStorageOutputFile5")).contains("output message5")
  }

  fun additionalTestOutputWithoutTestStorageService() {
    selectModule("app")

    rule.build.androidApplication().reconfigure {
      files {
        add(
          "src/androidTest/java/com/example/helloworld/AdditionalTestOutputExampleTest.kt",
          // language=kotlin
          """
          package com.example.helloworld

          import android.util.Log
          import androidx.test.ext.junit.runners.AndroidJUnit4
          import org.junit.Test
          import org.junit.runner.RunWith
          import java.io.File

          @RunWith(AndroidJUnit4::class)
          class AdditionalTestOutputExampleTest {
              @Test
              fun writeFileWithoutTestStorageService() {
                  Log.i("AdditionalTestOutputExampleTest", "writeFileWithoutTestStorageService: started")
                  val dir = File("/sdcard/Android/media/com.example.android.kotlin/additional_test_output").also {
                      it.mkdirs()
                  }
                  Log.i("AdditionalTestOutputExampleTest", "writeFileWithoutTestStorageService: dir created: " + dir.absolutePath)
                  File(dir,"myTestFile1").apply {
                      createNewFile()
                      writeText("output message 1")
                  }
                  Log.i("AdditionalTestOutputExampleTest", "writeFileWithoutTestStorageService: finished")
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.run(testTaskName)

    assertThat(resolvePath("${testAdditionalOutputPath}/myTestFile1")).contains("output message 1")
  }

  fun additionalTestOutputWithBenchmarkFiles() {
    selectModule("app")

    rule.build.androidApplication().reconfigure {
      files {
        add(
          "src/androidTest/java/com/example/helloworld/AdditionalTestOutputExampleTest.kt",
          // language=kotlin
          """
          package com.example.helloworld

          import android.os.Bundle
          import android.os.Environment
          import android.util.Log
          import androidx.test.platform.app.InstrumentationRegistry
          import androidx.test.ext.junit.runners.AndroidJUnit4

          import org.junit.Test
          import org.junit.runner.RunWith

          import java.io.File

          @RunWith(AndroidJUnit4::class)
          class AdditionalTestOutputExampleTest {
              @Test
              fun createSampleFileAndReportIt() {
                  Log.i("AdditionalTestOutputExampleTest", "createSampleFileAndReportIt: started")
                  val instrumentation = InstrumentationRegistry.getInstrumentation()
                  // Tries to report a bundle with additional test output
                  @Suppress("DEPRECATION")
                  val outputFolder = instrumentation
                      .targetContext
                      .externalMediaDirs.firstOrNull {
                          Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                      }
                      ?: throw Exception("Cannot get external storage due to not mounted")

                  Log.i("AdditionalTestOutputExampleTest", "createSampleFileAndReportIt: outputFolder: " + outputFolder.absolutePath)

                  val sampleFile = File(outputFolder, "sampleFile_1")
                      .apply { writeText("This is a sample file.") }

                  Log.i("AdditionalTestOutputExampleTest", "createSampleFileAndReportIt: sampleFile created: " + sampleFile.absolutePath)

                  // Note that the path used here should be relative to outputFolder, so just the filename.
                  val summary = "[sample file](file://" + sampleFile.name + ")"

                  val bundle = Bundle().apply {
                      putString("android.studio.display.benchmark", summary)
                      putString("android.studio.v2display.benchmark", summary)
                      putString("android.studio.v2display.benchmark.outputDirPath", outputFolder.absolutePath)
                      putString("additionalTestOutputFile_sampleFile", sampleFile.absolutePath)
                  }
                  InstrumentationRegistry
                      .getInstrumentation()
                      .sendStatus(2, bundle)
                  Log.i("AdditionalTestOutputExampleTest", "createSampleFileAndReportIt: finished")
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.run(testTaskName)

    assertThat(resolvePath("${testAdditionalOutputPath}/sampleFile_1")).contains("This is a sample file.")
    assertThat(
        resolvePath(
          "${testAdditionalOutputPath}/" +
            "additionaltestoutput.benchmark.message_com.example.helloworld" +
            ".AdditionalTestOutputExampleTest.createSampleFileAndReportIt.txt"
        )
      )
      .contains("[sample file](file://sampleFile_1)")
  }

  fun additionalTestOutputWithBenchmarkV3Files() {
    selectModule("app")

    rule.build.androidApplication().reconfigure {
      files {
        add(
          "src/androidTest/java/com/example/helloworld/AdditionalTestOutputExampleTest.kt",
          // language=kotlin
          """
          package com.example.helloworld

          import android.os.Bundle
          import android.os.Environment
          import android.util.Log
          import androidx.test.platform.app.InstrumentationRegistry
          import androidx.test.ext.junit.runners.AndroidJUnit4

          import org.junit.Test
          import org.junit.runner.RunWith

          import java.io.File

          @RunWith(AndroidJUnit4::class)
          class AdditionalTestOutputExampleTest {
              @Test
              fun createSampleFileAndReportIt() {
                  Log.i("AdditionalTestOutputExampleTest", "createSampleFileAndReportIt: started")
                  val instrumentation = InstrumentationRegistry.getInstrumentation()
                  // Tries to report a bundle with additional test output
                  @Suppress("DEPRECATION")
                  val outputFolder = instrumentation
                      .targetContext
                      .externalMediaDirs.firstOrNull {
                          Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                      }
                      ?: throw Exception("Cannot get external storage due to not mounted")

                  Log.i("AdditionalTestOutputExampleTest", "createSampleFileAndReportIt: outputFolder: " + outputFolder.absolutePath)

                  val sampleFile = File(outputFolder, "sampleFile_1")
                      .apply { writeText("This is a sample file.") }

                  Log.i("AdditionalTestOutputExampleTest", "createSampleFileAndReportIt: sampleFile created: " + sampleFile.absolutePath)

                  // Note that the path used here should be relative to outputFolder, so just the filename.
                  val summary = "[sample file](file://" + sampleFile.name + ")"
                  val summaryV3 = "[sample file](uri://" + sampleFile.name + ")"

                  val bundle = Bundle().apply {
                      putString("android.studio.display.benchmark", summary)
                      putString("android.studio.v2display.benchmark", summary)
                      putString("android.studio.v2display.benchmark.outputDirPath", outputFolder.absolutePath)
                      putString("android.studio.v3display.benchmark", summaryV3)
                      putString("android.studio.v3display.benchmark.outputDirPath", outputFolder.absolutePath)
                      putString("additionalTestOutputFile_sampleFile", sampleFile.absolutePath)
                  }
                  InstrumentationRegistry
                      .getInstrumentation()
                      .sendStatus(2, bundle)
                  Log.i("AdditionalTestOutputExampleTest", "createSampleFileAndReportIt: finished")
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.run(testTaskName)

    assertThat(resolvePath("${testAdditionalOutputPath}/sampleFile_1")).contains("This is a sample file.")
    assertThat(
        resolvePath(
          "${testAdditionalOutputPath}/" +
            "additionaltestoutput.benchmark.message_com.example.helloworld" +
            ".AdditionalTestOutputExampleTest.createSampleFileAndReportIt.txt"
        )
      )
      .contains("[sample file](uri://sampleFile_1)")
  }

  fun androidTestWithDynamicFeature() {
    selectModule("feature")

    rule.build.androidApplication().reconfigure { enableDynamicFeature(this, "feature") }

    executor.run(testTaskName)

    assertThat(project.resolve(testResultXmlPath)).exists()
    assertThat(project.resolve(testReportPath)).exists()

    val testResultPb = resolveTestResultPbPath()
    assertThat(testResultPb).exists()

    val deviceInfo = getDeviceInfo(testResultPb.toFile())
    assertThat(deviceInfo).isNotNull()
    assertThat(deviceInfo?.name).isNotEmpty()

    // Run the task again after clean. This time the task configuration is
    // restored from the configuration cache. We expect no crashes.
    executor.run("clean")

    assertThat(project.resolve(testResultXmlPath)).doesNotExist()
    assertThat(project.resolve(testReportPath)).doesNotExist()
    assertThat(resolveTestResultPbPath()).doesNotExist()

    executor.run(testTaskName)

    assertThat(project.resolve(testResultXmlPath)).exists()
    assertThat(project.resolve(testReportPath)).exists()
    assertThat(resolveTestResultPbPath()).exists()
  }

  fun androidTestWithOrchestratorWithDynamicFeature() {
    selectModule("feature")

    rule.build.androidApplication().reconfigure { enableDynamicFeature(this, "feature") }
    rule.build.androidFeature().reconfigure { enableAndroidTestOrchestrator(this) }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(resolveTestResultPbPath()).exists()
  }

  fun connectedAndroidTestWithLogcatWithDynamicFeature() {
    selectModule("feature")

    rule.build.androidApplication().reconfigure { enableDynamicFeature(this, "feature") }

    executor.run(testTaskName)

    assertThat(resolvePath(testLogcatPath)).exists()
    val logcatText = resolvePath(testLogcatPath).readText()
    assertThat(logcatText).contains("TestRunner: started: useAppContext(com.example.android.kotlin.feature.ExampleInstrumentedTest)")
    assertThat(logcatText).contains("TestLogger: test logs")
    assertThat(logcatText).contains("TestRunner: finished: useAppContext(com.example.android.kotlin.feature.ExampleInstrumentedTest)")
  }

  fun connectedAndroidTestWithAdditionalTestOutputUsingTestStorageServiceWithDynamicFeature() {
    selectModule("feature")

    rule.build.androidApplication().reconfigure { enableDynamicFeature(this, "feature") }
    rule.build.androidFeature().reconfigure {
      enableTestStorageService(this)
      files {
        add(
          "src/androidTest/java/com/example/helloworld/TestStorageServiceExampleTest.kt",
          // language=kotlin
          """
          package com.example.helloworld

          import android.util.Log
          import androidx.test.ext.junit.runners.AndroidJUnit4
          import androidx.test.services.storage.TestStorage
          import org.junit.Test
          import org.junit.runner.RunWith

          @RunWith(AndroidJUnit4::class)
          class TestStorageServiceExampleTest {
              @Test
              fun writeFileUsingTestStorageService() {
                  TestStorage().openOutputFile("myTestStorageOutputFile1").use {
                      it.write("output message1".toByteArray())
                  }
                  TestStorage().openOutputFile("myTestStorageOutputFile2.txt").use {
                      it.write("output message2".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/myTestStorageOutputFile3").use {
                      it.write("output message3".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/nested/myTestStorageOutputFile4").use {
                      it.write("output message4".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/white space/myTestStorageOutputFile5").use {
                      it.write("output message5".toByteArray())
                  }
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.run(testTaskName)

    assertThat(resolvePath("${testAdditionalOutputPath}/myTestStorageOutputFile1")).contains("output message1")
    assertThat(resolvePath("${testAdditionalOutputPath}/myTestStorageOutputFile2.txt")).contains("output message2")
    assertThat(resolvePath("${testAdditionalOutputPath}/subdir/myTestStorageOutputFile3")).contains("output message3")
    assertThat(resolvePath("${testAdditionalOutputPath}/subdir/nested/myTestStorageOutputFile4")).contains("output message4")
    assertThat(resolvePath("${testAdditionalOutputPath}/subdir/white space/myTestStorageOutputFile5")).contains("output message5")
  }

  fun androidTestWithForceCompilation() {
    selectModule("app")

    rule.build.androidApplication().reconfigure { enableForceCompilation(this) }

    val result = executor.withEnableInfoLogging(true).run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(resolveTestResultPbPath()).exists()

    result.assertOutputContains("Running force AOT compilation (speed) for com.example.android.kotlin")
    result.assertOutputContains("Running force AOT compilation (speed) for com.example.android.kotlin.test")
  }

  fun androidTestWithOrchestratorAndCodeCoverageWithDynamicFeature() {
    selectModule("feature")

    rule.build.androidApplication().reconfigure {
      enableAndroidTestOrchestrator(this)
      enableCodeCoverage(this)
      enableDynamicFeature(this, "feature")
    }
    rule.build.androidFeature().reconfigure {
      enableAndroidTestOrchestrator(this)
      enableCodeCoverage(this)
    }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(resolveTestResultPbPath()).exists()
    assertThat(project.resolve(testCoverageXmlPath))
      .contains("""<method name="stubDynamicFeature1FuncForTestingCodeCoverage" desc="()V" line="8">""")
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<counter type="INSTRUCTION" missed="3" covered="5"/>""")
  }

  fun androidTestWithCodeCoverageWithDynamicFeature() {
    selectModule("feature")

    rule.build.androidApplication().reconfigure {
      enableDynamicFeature(this, "feature")
      enableCodeCoverage(this)
    }
    rule.build.androidFeature().reconfigure { enableCodeCoverage(this) }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(resolveTestResultPbPath()).exists()
    assertThat(project.resolve(testCoverageXmlPath))
      .contains("""<method name="stubDynamicFeature1FuncForTestingCodeCoverage" desc="()V" line="8">""")
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<counter type="INSTRUCTION" missed="3" covered="5"/>""")
  }

  fun runAndroidTestWithNoTestClasses() {
    selectModule("emptyAppProject")

    val result =
      executor
        .withEnableInfoLogging(true) // "No tests found" message is info level.
        .run(testTaskName)

    if (runWithBuiltInPlatform) {
      val skippedTask = result.skippedTasks.find { it.endsWith("AndroidTest") }
      assertThat(skippedTask).isNotNull()
    } else {
      result.assertOutputContains("No tests found, nothing to do.")
    }
  }

  fun connectedAndroidTestDoesNotOutputNoClassDefFoundError() {
    selectModule("test")

    repeat(10) {
      executor.run(testTaskName).apply {
        assertOutputDoesNotContain("java.lang.NoClassDefFoundError")
        assertErrorDoesNotContain("java.lang.NoClassDefFoundError")
      }
    }
  }

  fun verifyReport(enableReportAggregation: Boolean = false) {
    val reportFile = project.resolve(testReportPath)
    val reportDir = reportFile.parent

    if (enableReportAggregation) {
      assertThat(reportDir.resolve("index.html")).exists()
      assertThat(reportDir.resolve("script.js")).exists()
      assertThat(reportDir.resolve("styles.css")).exists()
      assertThat(reportDir.resolve("data.js")).exists()

      val dataJsContent = reportDir.resolve("data.js").readText()
      assertThat(dataJsContent).contains("const TEST_DATA_SOURCE = ")
    } else {
      assertThat(reportFile).exists()
      assertThat(reportDir.resolve("index.html")).exists()
    }
  }
}

fun GradleBuildDefinition.applyAndroidTestConfiguration(runWithBuiltInPlatform: Boolean) {
  androidApplication {
    android {
      namespace = "com.example.android.kotlin"
      installation { timeOutInMs = 30000 }
      defaultConfig {
        minSdk = 21
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
      }
      dependencies {
        androidTestImplementation("androidx.test:core:1.4.0-alpha06")
        androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
        androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
        androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
        androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
      }
    }
    kotlin { jvmToolchain(17) }
    files {
      add(
        "src/main/java/com/example/android/kotlin/MainActivity.kt",
        // language=kotlin
        """
        package com.example.android.kotlin

        import android.app.Activity
        import java.util.logging.Logger.getLogger

        class MainActivity : Activity() {
            companion object {
                fun stubFuncForTestingCodeCoverage() {
                    getLogger("MainActivity").info("stubFuncForTestingCodeCoverage()")
                }
            }
        }
        """
          .trimIndent(),
      )
      add(
        "src/androidTest/java/com/example/android/kotlin/InstrumentedTest.kt",
        // language=kotlin
        """
        package com.example.android.kotlin

        import androidx.test.ext.junit.runners.AndroidJUnit4

        import org.junit.Test
        import org.junit.runner.RunWith

        import java.util.logging.Logger.getLogger

        @RunWith(AndroidJUnit4::class)
        class ExampleInstrumentedTest {
            private val logger = getLogger("TestLogger")

            @Test
            fun useAppContext() {
                logger.info("test logs")
                MainActivity.stubFuncForTestingCodeCoverage()
            }
        }
        """
          .trimIndent(),
      )
      add(
        "src/main/res/values/strings.xml",
        // language=xml
        """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="title_dynamicfeature1">dynamicfeature1</string>
        </resources>
        """
          .trimIndent(),
      )
    }
  }

  androidLibrary {
    android {
      namespace = "com.example.android.kotlin.library"
      installation { timeOutInMs = 30000 }
      defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
      }
      dependencies {
        androidTestImplementation("androidx.test:core:1.4.0-alpha06")
        androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
        androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
        androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
        androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
      }
    }
    kotlin { jvmToolchain(17) }
    files {
      add(
        "src/androidTest/java/com/example/android/kotlin/lib/InstrumentedTest.kt",
        // language=kotlin
        """
        package com.example.android.kotlin.lib

        import androidx.test.ext.junit.runners.AndroidJUnit4

        import org.junit.Test
        import org.junit.runner.RunWith

        @RunWith(AndroidJUnit4::class)
        class ExampleInstrumentedTest {
            @Test
            fun useAppContext() {}
        }
        """
          .trimIndent(),
      )
    }
  }

  androidTest {
    android {
      namespace = "com.example.android.kotlin.testonly"
      targetProjectPath = ":app"
      installation { timeOutInMs = 30000 }
      defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
      }
      dependencies {
        implementation("androidx.test:core:1.4.0-alpha06")
        implementation("androidx.test.ext:junit:1.1.3-alpha02")
        implementation("androidx.test:monitor:1.4.0-alpha06")
        implementation("androidx.test:rules:1.4.0-alpha06")
        implementation("androidx.test:runner:1.4.0-alpha06")
      }
    }
    kotlin { jvmToolchain(17) }
    files {
      add(
        "src/main/java/com/example/android/kotlin/InstrumentedTest.kt",
        // language=kotlin
        """
        package com.example.android.kotlin

        import androidx.test.ext.junit.runners.AndroidJUnit4
        import org.junit.Test
        import org.junit.runner.RunWith

        @RunWith(AndroidJUnit4::class)
        class ExampleInstrumentedTest {
            @Test
            fun useAppContext() {}
        }
        """
          .trimIndent(),
      )
    }
  }

  androidFeature {
    android {
      namespace = "com.example.android.kotlin.feature"
      installation { timeOutInMs = 30000 }
      defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
      }
      dependencies {
        implementation(project(":app"))
        implementation("androidx.test:core:1.4.0-alpha06")
        implementation("androidx.test.ext:junit:1.1.3-alpha02")
        implementation("androidx.test:monitor:1.4.0-alpha06")
        implementation("androidx.test:rules:1.4.0-alpha06")
        implementation("androidx.test:runner:1.4.0-alpha06")
      }
    }
    kotlin { jvmToolchain(17) }
    files {
      remove("src/main/AndroidManifest.xml")
      add(
        "src/main/AndroidManifest.xml",
        // language=xml
        """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  xmlns:dist="http://schemas.android.com/apk/distribution"
                  android:versionCode="1">

            <dist:module
                dist:instant="false"
                dist:title="@string/title_dynamicfeature1">
                <dist:delivery>
                    <dist:on-demand />
                </dist:delivery>
                <dist:fusing dist:include="true" />
            </dist:module>
        </manifest>
        """
          .trimIndent(),
      )
      add(
        "src/main/java/com/example/android/kotlin/feature/DynamicFeature1.kt",
        // language=kotlin
        """
        package com.example.android.kotlin.feature

        import java.util.logging.Logger.getLogger

        class DynamicFeature1 () {
            companion object {
                fun stubDynamicFeature1FuncForTestingCodeCoverage() {
                    getLogger("DynamicFeature1").info("stubDynamicFeature1FuncForTestingCodeCoverage()")
                }
            }
        }
        """
          .trimIndent(),
      )
      add(
        "src/androidTest/java/com/example/android/kotlin/feature/InstrumentedTest.kt",
        // language=kotlin
        """
        package com.example.android.kotlin.feature

        import com.example.android.kotlin.MainActivity
        import androidx.test.ext.junit.runners.AndroidJUnit4

        import org.junit.Test
        import org.junit.runner.RunWith

        import java.util.logging.Logger.getLogger

        @RunWith(AndroidJUnit4::class)
        class ExampleInstrumentedTest {
            private val logger = getLogger("TestLogger")

            @Test
            fun useAppContext() {
                logger.info("test logs")
                MainActivity.stubFuncForTestingCodeCoverage()
                DynamicFeature1.stubDynamicFeature1FuncForTestingCodeCoverage()
            }
        }
        """
          .trimIndent(),
      )
    }
  }

  androidApplication(":emptyAppProject") {}

  gradleProperties { add(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform) }
}
