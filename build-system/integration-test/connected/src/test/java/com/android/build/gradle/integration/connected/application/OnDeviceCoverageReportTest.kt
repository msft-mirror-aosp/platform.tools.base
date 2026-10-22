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

import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.tasks.TestSuiteTestTask.Companion.CONNECTED_TEST_TEST_SUITE_NAME
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import java.io.File
import org.gradle.api.JavaVersion
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Integration test for the unified coverage reports of `testAllSuites` and `testAllSuitesWithDependencies` when device tests collect
 * coverage with the on-device agent (`android.experimental.androidTest.coverage.useOnDeviceAgent`) instead of offline JaCoCo.
 *
 * The agent counts dex instructions where JaCoCo counts bytecode instructions, so unlike [CodeCoverageReportTest] this test checks which
 * suites cover what, not exact counts.
 */
@RunWith(Parameterized::class)
class OnDeviceCoverageReportTest(val runWithBuiltInPlatform: Boolean) {

  companion object {
    @ClassRule @JvmField val emulator: ExternalResource = getEmulator()

    @JvmStatic
    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

    private const val DEVICE_SUITE = "deviceSuite"
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
          // A device test suite that only covers the "subtract" branch of AppKotlinClass.calculate().
          testOptions.customSuites.create(DEVICE_SUITE) {
            it.codeCoverage = true
            it.testApk {
              dependencies {
                implementation.add("junit:junit:4.13.2")
                implementation.add("androidx.test:core:1.4.0-alpha06")
                implementation.add("androidx.test.ext:junit:1.1.3-alpha02")
                implementation.add("androidx.test:monitor:1.4.0-alpha06")
                implementation.add("androidx.test:rules:1.4.0-alpha06")
                implementation.add("androidx.test:runner:1.4.0-alpha06")
              }
            }
            it.useJunitEngine.apply {
              inputs.add(AgpTestSuiteInputParameters.TESTED_APKS)
              inputs.add(AgpTestSuiteInputParameters.TEST_APKS)
              inputs.add(AgpTestSuiteInputParameters.ADB_EXECUTABLE)
              inputs.add(AgpTestSuiteInputParameters.AAPT2_EXECUTABLE)
              includeEngines.add("android-test-engine")
              addInputProperty("android-test.instrumentation-runner-class", "androidx.test.runner.AndroidJUnitRunner")
              addInputProperty("android-test.test-package-id", "com.example.app.test")
              addInputProperty("android-test.instrumentation-target-package-id", "com.example.app")
              addInputProperty("android-test.uninstall-after-tests", "true")
              enginesDependencies.add("com.android.tools.androidtest:android-test-engine:+")
              enginesDependencies.add("com.android.tools.androidtest:android-test-engine-result-listener:+")
              enginesDependencies.add("org.junit.platform:junit-platform-engine:+")
              enginesDependencies.add("org.junit.platform:junit-platform-launcher:+")
            }
            it.targetVariants.add("debug")
            it.targets.create("t1") {}
          }
        }
        dependencies {
          implementation(project(":lib"))

          testImplementation("junit:junit:4.13.2")
          testImplementation("org.mockito:mockito-core:5.20.0")
          testImplementation("org.jdeferred:jdeferred-android-aar:1.2.3")
          testImplementation("commons-logging:commons-logging:1.1.1")

          androidTestImplementation("androidx.test:core:1.4.0-alpha06")
          androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
          androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
          androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
          androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
        }
        files.add(
          "src/$DEVICE_SUITE/kotlin/com/example/app/SubtractTest.kt",
          """
          package com.example.app

          import androidx.test.ext.junit.runners.AndroidJUnit4
          import org.junit.Assert.assertEquals
          import org.junit.Test
          import org.junit.runner.RunWith

          @RunWith(AndroidJUnit4::class)
          class SubtractTest {
              @Test
              fun subtract() {
                  assertEquals(1, AppKotlinClass().calculate(3, 2, "subtract"))
              }
          }
          """
            .trimIndent(),
        )
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
            testImplementation("org.mockito:mockito-core:5.20.0")
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
      gradleProperties {
        add(BooleanOption.REPORT_AGGREGATION_SUPPORT, true)
        add(BooleanOption.TEST_SUITE_SUPPORT, true)
        add(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
        add(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
      }
    }

  private val gson = Gson()

  @Test
  fun testAllSuitesReportsOnDeviceCoverage() {
    rule.build.executor.run(":app:testAllSuites")

    val report = coverageReport(":app", "testAllSuites")
    val app = report.modules.single { it.name == ":app" }

    // Every suite has its own row, and "Aggregated" includes the coverage of the device tests.
    val unitTest = app.coveredInstructions("UnitTest")
    val androidTest = app.coveredInstructions(CONNECTED_TEST_TEST_SUITE_NAME)
    val deviceSuite = app.coveredInstructions(DEVICE_SUITE)
    assertThat(unitTest).isGreaterThan(0)
    assertThat(androidTest).isGreaterThan(0)
    assertThat(deviceSuite).isGreaterThan(0)
    assertThat(app.coveredInstructions("Aggregated")).isGreaterThan(unitTest)

    // Only the device suite runs the "subtract" branch: it is covered in the suite's row and in "Aggregated".
    val subtractLine =
      sourceFileReport(":app", "testAllSuites", app, "AppKotlinClass").linesCoverages.single { "return n1-n2" in it.lineText }
    assertThat(subtractLine.coveredInstructions("UnitTest")).isEqualTo(0)
    assertThat(subtractLine.coveredInstructions(DEVICE_SUITE)).isGreaterThan(0)
    assertThat(subtractLine.coveredInstructions("Aggregated")).isGreaterThan(0)

    // The suite's own report, and androidTest's on the built-in test platform, are built from the on-device data too.
    val appBuildDir = rule.build.subProject(":app").buildDir.toFile()
    assertThat(FileUtils.join(appBuildDir, "reports", "coverage", DEVICE_SUITE, "index.html")).exists()
    if (runWithBuiltInPlatform) {
      assertThat(FileUtils.join(appBuildDir, "reports", "coverage", CONNECTED_TEST_TEST_SUITE_NAME, "index.html")).exists()
    }
  }

  @Test
  fun testAllSuitesWithDependenciesReportsOnDeviceCoverage() {
    rule.build.executor.run(":app:testAllSuitesWithDependencies")

    val report = coverageReport(":app", "testAllSuitesWithDependencies")
    assertThat(report.modules.map { it.name }).containsExactly(":app", ":lib")
    for (module in report.modules) {
      val unitTest = module.coveredInstructions("UnitTest")
      assertThat(module.coveredInstructions(CONNECTED_TEST_TEST_SUITE_NAME)).isGreaterThan(0)
      assertThat(module.coveredInstructions("Aggregated")).isGreaterThan(unitTest)
    }
  }

  private fun reportDir(modulePath: String, taskName: String): File {
    val buildDir = rule.build.subProject(modulePath).buildDir.toFile()
    return FileUtils.join(buildDir, "reports", taskName, "coverage-report")
  }

  private fun coverageReport(modulePath: String, taskName: String): CoverageReport {
    val reportData = File(reportDir(modulePath, taskName), "data/report-data.js")
    assertThat(reportData).exists()
    return gson.fromJson(reportData.readText().removePrefix("const fullReport = ").removeSuffix(";"), CoverageReport::class.java)
  }

  private fun sourceFileReport(modulePath: String, taskName: String, module: ModuleReport, className: String): SourceFileReport {
    // Every XML report of the variant adds the source file path of the class again, so take the first one.
    val path =
      module.packages.flatMap { it.classes }.first { it.name == className }.variantSourceFilePaths.first { it.variantName == "debug" }.path
    val sourceFile = File(reportDir(modulePath, taskName), "sourcefiles/$path.json.js")
    assertThat(sourceFile).exists()
    return gson.fromJson(sourceFile.readText().substringAfterLast("= ").removeSuffix(";"), SourceFileReport::class.java)
  }

  private fun ModuleReport.coveredInstructions(testSuite: String): Int =
    testSuiteCoverages.single { it.name == testSuite }.variantCoverages.single { it.name == "debug" }.instruction.covered

  private fun LineCoverage.coveredInstructions(testSuite: String): Int =
    variantCoverageDetails
      .filter { it.variantName == "debug" }
      .flatMap { it.testSuiteCoverages }
      .filter { it.testSuiteName == testSuite }
      .maxOfOrNull { it.variantCoverage.instruction.covered } ?: 0

  // --- Data classes for parsing the JSON of the report ---

  data class CoverageReport(val name: String, val modules: List<ModuleReport>)

  data class ModuleReport(val name: String, val testSuiteCoverages: List<TestSuiteReportCoverage>, val packages: List<PackageReport>)

  data class TestSuiteReportCoverage(val name: String, val variantCoverages: List<VariantCoverage>)

  data class PackageReport(val name: String, val classes: List<ClassReport>)

  data class ClassReport(val name: String, val variantSourceFilePaths: List<VariantSourceFilePath>)

  data class VariantSourceFilePath(val variantName: String, val path: String)

  data class VariantCoverage(val name: String, val instruction: CoverageInfo)

  data class CoverageInfo(val covered: Int, val total: Int)

  data class SourceFileReport(val linesCoverages: List<LineCoverage>)

  data class LineCoverage(val lineNumber: Int, val lineText: String, val variantCoverageDetails: List<VariantCoverageDetails>)

  data class VariantCoverageDetails(val variantName: String, val testSuiteCoverages: List<LineTestSuiteCoverage>)

  data class LineTestSuiteCoverage(val testSuiteName: String, val variantCoverage: VariantCoverage)
}
