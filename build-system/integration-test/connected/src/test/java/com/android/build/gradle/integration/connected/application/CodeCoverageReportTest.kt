/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import org.gradle.api.JavaVersion
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/**
 * Integration test for [com.android.build.gradle.internal.coverage.tasks.CodeCoverageReportTask].
 *
 * This test verifies the generation of the final HTML code coverage report by running the `createCoverageReport` and
 * `createAggregatedCoverageReport` tasks. It checks for the existence of the report files, parses the generated JSON data, and verifies the
 * accuracy of the aggregated coverage metrics.
 */
class CodeCoverageReportTest {

  companion object {
    @ClassRule @JvmField val emulator: ExternalResource = getEmulator()

    const val APP_EXPECTED_COVERED_INSTRUCTION_AGGREGATED = 28
    const val APP_EXPECTED_COVERED_BRANCH_AGGREGATED = 1
    const val LIB_EXPECTED_COVERED_INSTRUCTION_AGGREGATED = 37
    const val LIB_EXPECTED_COVERED_BRANCH_AGGREGATED = 3
  }

  @get:Rule
  val rule =
    GradleRule.fromProject("reportAggregation", "reportAggregation") {
      androidApplication(":app") {
        android {
          namespace = "com.example.app"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          defaultConfig {
            minSdk { version = release(24) }
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          buildTypes {
            named("debug") {
              it.enableUnitTestCoverage = true
              it.enableAndroidTestCoverage = true
            }
          }
          kotlin { jvmToolchain(17) }
          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
          }
        }
        dependencies {
          implementation(project(":lib"))

          testImplementation("junit:junit:4.13.2")
          testImplementation("org.mockito:mockito-core:5.12.0")
          testImplementation("org.jdeferred:jdeferred-android-aar:1.2.3")
          testImplementation("commons-logging:commons-logging:1.1.1")

          androidTestImplementation("androidx.test:core:1.4.0-alpha06")
          androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
          androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
          androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
          androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
        }
      }
      androidLibrary(":lib") {
        android {
          namespace = "com.example.lib"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          defaultConfig {
            minSdk { version = release(24) }
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          buildTypes {
            named("debug") {
              it.enableUnitTestCoverage = true
              it.enableAndroidTestCoverage = true
            }
          }
          kotlin { jvmToolchain(17) }
          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
          }
          dependencies {
            testImplementation("junit:junit:4.13.2")
            testImplementation("org.mockito:mockito-core:5.12.0")
            testImplementation("org.jdeferred:jdeferred-android-aar:1.2.3")
            testImplementation("commons-logging:commons-logging:1.1.1")

            androidTestImplementation("androidx.test:core:1.4.0-alpha06")
            androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
            androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
            androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
            androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
          }
        }
      }
      androidLibrary(":lib2") {
        android {
          namespace = "com.example.lib"

          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }

          installation { timeOutInMs = 30000 }

          defaultConfig {
            minSdk { version = release(24) }
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }

          buildTypes {
            named("debug") {
              it.enableUnitTestCoverage = true
              it.enableAndroidTestCoverage = true
            }
          }

          publishing { singleVariant("debug") }

          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
          }

          dependencies {
            testImplementation("junit:junit:4.13.2")
            testImplementation("org.mockito:mockito-core:5.12.0")
            testImplementation("org.jdeferred:jdeferred-android-aar:1.2.3")
            testImplementation("commons-logging:commons-logging:1.1.1")

            androidTestImplementation("androidx.test:core:1.4.0-alpha06")
            androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
            androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
            androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
            androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
          }
          kotlin { jvmToolchain(17) }
        }
      }
    }

  private val gson = Gson()

  @Test
  fun testCreateCoverageReport() {
    val result = rule.build.executor.run(":app:createCoverageReport")

    val appBuildDir = rule.build.androidApplication(":app").buildDir.toFile()
    val outputDir = FileUtils.join(appBuildDir, "reports", "code_coverage_html_report", "global")

    verifyHtmlReport(
      outputDir = outputDir,
      expectedProjectName = "reportAggregation",
      expectedModuleCount = 1,
      verifyLibModuleIsPresent = false,
      taskResult = result,
    )
  }

  @Test
  fun testCreateCoverageReportTaskForLibraryModule() {
    val build = rule.build
    build.executor.run(":lib:createCoverageReport")

    val appBuildDir = build.androidLibrary(":lib").buildDir.toFile()

    val taskOutputDir = FileUtils.join(appBuildDir, "reports", "code_coverage_html_report", "global")

    PathSubject.assertThat(taskOutputDir).exists()
    PathSubject.assertThat(taskOutputDir).isDirectory()

    val indexFile = FileUtils.join(taskOutputDir, "index.html")

    PathSubject.assertThat(indexFile).exists()
    PathSubject.assertThat(indexFile).isFile()
  }

  @Test
  fun testCreateAggregatedCoverageReport() {
    val result = rule.build.executor.run(":app:createAggregatedCoverageReport")

    val appBuildDir = rule.build.androidApplication(":app").buildDir.toFile()
    val outputDir = FileUtils.join(appBuildDir, "reports", "aggregated_code_coverage_html_report", "global")

    verifyHtmlReport(
      outputDir = outputDir,
      expectedProjectName = "reportAggregation",
      expectedModuleCount = 2,
      verifyLibModuleIsPresent = true,
      taskResult = result,
    )
  }

  @Test
  fun testCreateAggregatedCoverageReportTaskForLibraryModule() {
    val build = rule.build

    // Expect the build to fail
    val aggregatedReportLibResult = build.executor.expectFailure().run(":lib:createAggregatedCoverageReport")

    // Assert that the failure reason is because the task was not found
    aggregatedReportLibResult.assertFailureMessage().contains("task 'createAggregatedCoverageReport' not found in project ':lib'")
  }

  @Test
  fun testCreateAggregatedCoverageReportTaskForLibraryModuleWithPublicationEnabled() {
    val build = rule.build
    build.executor.run(":lib2:createAggregatedCoverageReport")

    val libBuildDir = build.androidLibrary(":lib2").buildDir.toFile()

    val taskOutputDir = FileUtils.join(libBuildDir, "reports", "aggregated_code_coverage_html_report", "global")

    PathSubject.assertThat(taskOutputDir).exists()
    PathSubject.assertThat(taskOutputDir).isDirectory()

    val indexFile = FileUtils.join(taskOutputDir, "index.html")

    PathSubject.assertThat(indexFile).exists()
    PathSubject.assertThat(indexFile).isFile()
  }

  @Test
  fun testCreateCoverageReportWithCoverageDisabled() {
    val app = rule.build.androidApplication(":app")
    app.reconfigure {
      android.buildTypes {
        named("debug") {
          it.enableUnitTestCoverage = false
          it.enableAndroidTestCoverage = false
        }
      }
    }

    val result = rule.build.executor.run(":app:createCoverageReport")

    result.assertOutputContains("No code coverage data found.")
    result.assertOutputDoesNotContain("View coverage report at")
  }

  @Test
  fun testCreateCoverageReportWithFeatureDisabled() {
    val build = rule.build { gradleProperties { add(BooleanOption.REPORT_AGGREGATION_SUPPORT, false) } }

    val result = build.executor.run(":app:createCoverageReport")

    result.assertOutputContains("Report aggregation feature is disabled. Task execution is skipped.")
    result.assertOutputDoesNotContain("View coverage report at")
    assertThat(result.didWorkTasks).doesNotContain(":app:collectDebugCoverage")
  }

  private fun verifyHtmlReport(
    outputDir: File,
    expectedProjectName: String,
    expectedModuleCount: Int,
    verifyLibModuleIsPresent: Boolean,
    taskResult: GradleBuildResult,
  ) {
    assertThat(outputDir).exists()
    assertThat(outputDir).isDirectory()

    val indexFile = File(outputDir, "index.html")
    assertThat(indexFile).exists()
    assertThat(indexFile).isFile()
    taskResult.assertOutputContains("View coverage report at file://${indexFile.absolutePath}")

    val cssFile = File(outputDir, "css/style.css")
    assertThat(cssFile).exists()
    assertThat(cssFile).isFile()

    val mainScriptFile = File(outputDir, "javascript/codecoveragescript.js")
    assertThat(mainScriptFile).exists()
    assertThat(mainScriptFile).isFile()

    val sourceViewScriptFile = File(outputDir, "javascript/sourceviewscript.js")
    assertThat(sourceViewScriptFile).exists()
    assertThat(sourceViewScriptFile).isFile()

    assertThat(File(outputDir, "data/report-data.js")).exists()
    assertThat(File(outputDir, "sourcefiles")).isDirectory()

    val report = parseReportJs<TestCoverageReport>(File(outputDir, "data/report-data.js"))

    assertThat(report.name).isEqualTo(expectedProjectName)
    assertThat(report.modules).hasSize(expectedModuleCount)

    val appModule = report.modules.find { it.name == ":app" }
    assertThat(appModule).isNotNull()
    val appAggregatedCoverage = appModule!!.testSuiteCoverages.find { it.name == "Aggregated" }
    assertThat(appAggregatedCoverage).isNotNull()
    val appDebugCoverage = appAggregatedCoverage!!.variantCoverages.find { it.name == "debug" }
    assertThat(appDebugCoverage).isNotNull()
    assertThat(appDebugCoverage!!.instruction.covered).isEqualTo(APP_EXPECTED_COVERED_INSTRUCTION_AGGREGATED)
    assertThat(appDebugCoverage.branch.covered).isEqualTo(APP_EXPECTED_COVERED_BRANCH_AGGREGATED)

    val appPackage = appModule.packages.find { it.name == "com.example.app" }
    assertThat(appPackage).isNotNull()
    val appKotlinClass = appPackage!!.classes.find { it.name == "AppKotlinClass" }
    assertThat(appKotlinClass).isNotNull()

    val appSourcePath = appKotlinClass!!.variantSourceFilePaths.find { it.variantName == "debug" }?.path
    assertThat(appSourcePath).isNotNull()

    val appSourceReportFile = File(outputDir, "sourcefiles/${appSourcePath}.json.js")
    assertThat(appSourceReportFile).exists()
    assertThat(appSourceReportFile).isFile()

    val appSourceReport = parseSourceReportJs<TestSourceFileReport>(appSourceReportFile)
    assertThat(appSourceReport.linesCoverages).isNotEmpty()
    val addLine = appSourceReport.linesCoverages.find { it.lineText.contains("return n1+n2") }
    assertThat(addLine).isNotNull()

    val addLineDetails = addLine!!.variantCoverageDetails.find { it.variantName == "debug" }
    assertThat(addLineDetails).isNotNull()

    val unitTestCoverage = addLineDetails!!.testSuiteCoverages.find { it.testSuiteName == "UnitTest" }
    assertThat(unitTestCoverage).isNotNull()
    assertThat(unitTestCoverage!!.variantCoverage.instruction.covered).isEqualTo(5)

    val androidTestCoverage = addLineDetails.testSuiteCoverages.find { it.testSuiteName == "AndroidTest" }
    assertThat(androidTestCoverage).isNotNull()
    assertThat(androidTestCoverage!!.variantCoverage.instruction.covered).isEqualTo(0)

    if (verifyLibModuleIsPresent) {
      val libModule = report.modules.find { it.name == ":lib" }
      assertThat(libModule).isNotNull()
      val libAggregatedCoverage = libModule!!.testSuiteCoverages.find { it.name == "Aggregated" }
      assertThat(libAggregatedCoverage).isNotNull()
      val libDebugCoverage = libAggregatedCoverage!!.variantCoverages.find { it.name == "debug" }
      assertThat(libDebugCoverage).isNotNull()
      assertThat(libDebugCoverage!!.instruction.covered).isEqualTo(LIB_EXPECTED_COVERED_INSTRUCTION_AGGREGATED)
      assertThat(libDebugCoverage.branch.covered).isEqualTo(LIB_EXPECTED_COVERED_BRANCH_AGGREGATED)

      val libPackage = libModule.packages.find { it.name == "com.example.lib" }
      assertThat(libPackage).isNotNull()
      val libKotlinClass = libPackage!!.classes.find { it.name == "LibKotlinClass" }
      assertThat(libKotlinClass).isNotNull()

      val libSourcePath = libKotlinClass!!.variantSourceFilePaths.find { it.variantName == "debug" }?.path
      assertThat(libSourcePath).isNotNull()

      val libSourceReportFile = File(outputDir, "sourcefiles/${libSourcePath}.json.js")
      assertThat(libSourceReportFile).exists()
      assertThat(libSourceReportFile).isFile()

      val libSourceReport = parseSourceReportJs<TestSourceFileReport>(libSourceReportFile)
      assertThat(libSourceReport.linesCoverages).isNotEmpty()
      val subtractLine = libSourceReport.linesCoverages.find { it.lineText.contains("return n1-n2") }
      assertThat(subtractLine).isNotNull()

      val subtractLineDetails = subtractLine!!.variantCoverageDetails.find { it.variantName == "debug" }
      assertThat(subtractLineDetails).isNotNull()
      val libUnitTestCoverage = subtractLineDetails!!.testSuiteCoverages.find { it.testSuiteName == "UnitTest" }
      assertThat(libUnitTestCoverage).isNotNull()
      assertThat(libUnitTestCoverage!!.variantCoverage.instruction.covered).isEqualTo(5)

      val libAndroidTestCoverage = subtractLineDetails.testSuiteCoverages.find { it.testSuiteName == "AndroidTest" }!!
      assertThat(libAndroidTestCoverage.variantCoverage.instruction.covered).isEqualTo(0)
    } else {
      val libModule = report.modules.find { it.name == ":lib" }
      assertThat(libModule).isNull()
    }
  }

  private inline fun <reified T> parseReportJs(file: File): T {
    val content = file.readText().removePrefix("const fullReport = ").removeSuffix(";")
    return gson.fromJson(content, T::class.java)
  }

  private inline fun <reified T> parseSourceReportJs(file: File): T {
    val content = file.readText().substringAfterLast("= ").removeSuffix(";")
    return gson.fromJson(content, T::class.java)
  }

  // --- Data classes for parsing JSON from report files ---

  data class TestCoverageReport(
    val name: String,
    val modules: List<TestModuleReport>,
    @SerializedName("numberOfTestsSuites") val numberOfTestsSuites: Int,
  )

  data class TestModuleReport(
    val name: String,
    val testSuiteCoverages: List<TestTestSuiteReportCoverage>,
    val packages: List<TestPackageReport>,
  )

  data class TestTestSuiteReportCoverage(val name: String, val variantCoverages: List<TestVariantCoverage>)

  data class TestPackageReport(val name: String, val classes: List<TestClassReport>)

  data class TestClassReport(val name: String, val sourceFileName: String, val variantSourceFilePaths: List<TestVariantSourceFilePath>)

  data class TestVariantSourceFilePath(val variantName: String, val path: String)

  data class TestVariantCoverage(val name: String, val instruction: TestCoverageInfo, val branch: TestCoverageInfo)

  data class TestCoverageInfo(val percent: Int, val covered: Int, val total: Int)

  data class TestSourceFileReport(val linesCoverages: List<TestLineCoverage>)

  data class TestLineCoverage(val lineNumber: Int, val lineText: String, val variantCoverageDetails: List<TestVariantCoverageDetails>)

  data class TestVariantCoverageDetails(val variantName: String, val testSuiteCoverages: List<TestSuiteCoverage>)

  data class TestSuiteCoverage(val testSuiteName: String, val variantCoverage: TestVariantCoverage)
}
