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

import com.android.Version
import com.android.build.api.dsl.AgpTestSuite
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption
import com.android.testutils.truth.PathSubject
import com.android.utils.FileUtils
import com.google.common.truth.Truth
import java.io.File
import kotlin.jvm.java
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.rules.ExternalResource

/** Integration test for [com.android.build.gradle.internal.coverage.tasks.CodeCoverageCollectionTask]. */
class CodeCoverageCollectionTest {

  companion object {
    @ClassRule @JvmField val emulator: ExternalResource = getEmulator()

    const val APP_TOTAL_INSTRUCTION = 42
    const val APP_TOTAL_BRANCH = 4

    const val APP_EXPECTED_COVERED_INSTRUCTION_UNIT_TEST = 23
    const val APP_EXPECTED_MISSED_INSTRUCTION_UNIT_TEST = 19
    const val APP_EXPECTED_COVERED_BRANCH_UNIT_TEST = 1
    const val APP_EXPECTED_MISSED_BRANCH_UNIT_TEST = 3

    const val APP_EXPECTED_COVERED_INSTRUCTION_ANDROID_TEST = 5
    const val APP_EXPECTED_MISSED_INSTRUCTION_ANDROID_TEST = 37
    const val APP_EXPECTED_COVERED_BRANCH_ANDROID_TEST = 0
    const val APP_EXPECTED_MISSED_BRANCH_ANDROID_TEST = 4

    const val APP_EXPECTED_COVERED_INSTRUCTION_AGGREGATED =
      APP_EXPECTED_COVERED_INSTRUCTION_UNIT_TEST + APP_EXPECTED_COVERED_INSTRUCTION_ANDROID_TEST
    const val APP_EXPECTED_MISSED_INSTRUCTION_AGGREGATED = APP_TOTAL_INSTRUCTION - APP_EXPECTED_COVERED_INSTRUCTION_AGGREGATED
    const val APP_EXPECTED_COVERED_BRANCH_AGGREGATED = APP_EXPECTED_COVERED_BRANCH_UNIT_TEST + APP_EXPECTED_COVERED_BRANCH_ANDROID_TEST
    const val APP_EXPECTED_MISSED_BRANCH_AGGREGATED = APP_TOTAL_BRANCH - APP_EXPECTED_COVERED_BRANCH_AGGREGATED

    const val LIB_TOTAL_INSTRUCTION = 42
    const val LIB_TOTAL_BRANCH = 4

    const val LIB_EXPECTED_COVERED_INSTRUCTION_UNIT_TEST = 32
    const val LIB_EXPECTED_MISSED_INSTRUCTION_UNIT_TEST = 10
    const val LIB_EXPECTED_COVERED_BRANCH_UNIT_TEST = 3
    const val LIB_EXPECTED_MISSED_BRANCH_UNIT_TEST = 1

    const val LIB_EXPECTED_COVERED_INSTRUCTION_ANDROID_TEST = 5
    const val LIB_EXPECTED_MISSED_INSTRUCTION_ANDROID_TEST = 37
    const val LIB_EXPECTED_COVERED_BRANCH_ANDROID_TEST = 0
    const val LIB_EXPECTED_MISSED_BRANCH_ANDROID_TEST = 4

    const val LIB_EXPECTED_COVERED_INSTRUCTION_AGGREGATED =
      LIB_EXPECTED_COVERED_INSTRUCTION_UNIT_TEST + LIB_EXPECTED_COVERED_INSTRUCTION_ANDROID_TEST
    const val LIB_EXPECTED_MISSED_INSTRUCTION_AGGREGATED = LIB_TOTAL_INSTRUCTION - LIB_EXPECTED_COVERED_INSTRUCTION_AGGREGATED
    const val LIB_EXPECTED_COVERED_BRANCH_AGGREGATED = LIB_EXPECTED_COVERED_BRANCH_UNIT_TEST + LIB_EXPECTED_COVERED_BRANCH_ANDROID_TEST
    const val LIB_EXPECTED_MISSED_BRANCH_AGGREGATED = LIB_TOTAL_BRANCH - LIB_EXPECTED_COVERED_BRANCH_AGGREGATED
  }

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("org.junit.platform:junit-platform-engine:1.10.1")
        jar("org.junit.platform:junit-platform-launcher:1.10.1")
        jar("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
        jar("com.test:custom-junit-engine:1.0")
          .addClasses(CustomJunitEngineForTesting::class.java, CustomTestDescriptor::class.java, CustomEngineDescriptor::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", CustomJunitEngineForTesting::class.java.name)
      }
      .fromProject("reportAggregation") {
        androidApplication(":app") {
          android {
            namespace = "com.example.app"
            compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }

            installation { timeOutInMs = 30000 }

            defaultConfig {
              minSdk { version = release(24) }
              targetSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
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

            testOptions.suites.create("first", AgpTestSuite::class.java) {
              it.useJunitEngine.apply {
                inputs.add(AgpTestSuiteInputParameters.MERGED_MANIFEST)
                includeEngines.add("[engine:custom-junit-engine-for-tests]")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                enginesDependencies.add("com.test:custom-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
              }
              it.assets {}
              it.targetVariants.add("debug")
              it.targets.create("t1") {}
              it.targets.create("t2") {}
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

        androidLibrary(":lib2") {
          android {
            namespace = "com.example.lib2"

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
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
      }

  @Test
  fun testCollectDebugCoverage() {
    val build = rule.build
    build.executor.run(":app:collectDebugCoverage")

    val appBuildDir = build.androidApplication(":app").buildDir.toFile()

    val taskOutputDir = FileUtils.join(appBuildDir, "intermediates", "code_coverage_data", "global", "collectDebugCoverage")

    PathSubject.assertThat(taskOutputDir).exists()
    PathSubject.assertThat(taskOutputDir).isDirectory()

    val xmlReports = taskOutputDir.listFiles().toList()

    Truth.assertThat(xmlReports.size).isEqualTo(3)

    val xmlReport1 = xmlReports.filter { it.name == "debugAppAggregatedXmlReport.xml" }
    Truth.assertThat(xmlReport1.size).isEqualTo(1)
    verifyReportName(xmlReport1.first(), "debugAppAggregated")
    val aggregatedCoverageReportXml = xmlReport1.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyCoverageData(
      aggregatedCoverageReportXml,
      APP_EXPECTED_COVERED_INSTRUCTION_AGGREGATED,
      APP_EXPECTED_MISSED_INSTRUCTION_AGGREGATED,
      APP_EXPECTED_COVERED_BRANCH_AGGREGATED,
      APP_EXPECTED_MISSED_BRANCH_AGGREGATED,
    )
    verifyProperties(aggregatedCoverageReportXml, ":app", "Aggregated", "debug")
    verifySources(aggregatedCoverageReportXml, "app")

    val xmlReport2 = xmlReports.filter { it.name == "debugAppUnitTestXmlReport.xml" }
    Truth.assertThat(xmlReport2.size).isEqualTo(1)
    val unitTestCoverageReportXml = xmlReport2.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport2.first(), "debugAppUnitTest")
    verifyCoverageData(
      unitTestCoverageReportXml,
      APP_EXPECTED_COVERED_INSTRUCTION_UNIT_TEST,
      APP_EXPECTED_MISSED_INSTRUCTION_UNIT_TEST,
      APP_EXPECTED_COVERED_BRANCH_UNIT_TEST,
      APP_EXPECTED_MISSED_BRANCH_UNIT_TEST,
    )
    verifyProperties(unitTestCoverageReportXml, ":app", "UnitTest", "debug")
    verifySources(unitTestCoverageReportXml, "app")

    val xmlReport3 = xmlReports.filter { it.name == "debugAppAndroidTestXmlReport.xml" }
    Truth.assertThat(xmlReport3.size).isEqualTo(1)
    val androidTestCoverageReportXml = xmlReport3.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport3.first(), "debugAppAndroidTest")
    verifyCoverageData(
      androidTestCoverageReportXml,
      APP_EXPECTED_COVERED_INSTRUCTION_ANDROID_TEST,
      APP_EXPECTED_MISSED_INSTRUCTION_ANDROID_TEST,
      APP_EXPECTED_COVERED_BRANCH_ANDROID_TEST,
      APP_EXPECTED_MISSED_BRANCH_ANDROID_TEST,
    )
    verifyProperties(androidTestCoverageReportXml, ":app", "AndroidTest", "debug")
    verifySources(androidTestCoverageReportXml, "app")
  }

  @Test
  fun testCollectDebugCoverageForLibraryModule() {
    val build = rule.build
    build.executor.run(":lib:collectDebugCoverage")

    val appBuildDir = build.androidLibrary(":lib").buildDir.toFile()

    val taskOutputDir = FileUtils.join(appBuildDir, "intermediates", "code_coverage_data", "global", "collectDebugCoverage")

    PathSubject.assertThat(taskOutputDir).exists()
    PathSubject.assertThat(taskOutputDir).isDirectory()

    val xmlReports = taskOutputDir.listFiles().toList()

    Truth.assertThat(xmlReports.size).isEqualTo(3)
  }

  @Test
  fun testCollectDebugAggregatedCoverage() {
    val build = rule.build
    build.executor.run(":app:collectDebugAggregatedCoverage")

    val appBuildDir = build.androidApplication(":app").buildDir.toFile()

    val taskOutputDir =
      FileUtils.join(appBuildDir, "intermediates", "aggregated_code_coverage_data", "global", "collectDebugAggregatedCoverage")

    // Check data is collected for app and lib modules.
    PathSubject.assertThat(taskOutputDir).exists()
    PathSubject.assertThat(taskOutputDir).isDirectory()

    val xmlReports = taskOutputDir.listFiles()

    Truth.assertThat(xmlReports.size).isEqualTo(6)

    // Check the contents of generated report for current (app) module and reports copied from
    // dependant modules (lib).
    val xmlReport1 = xmlReports.filter { it.name == "debugAppAggregatedXmlReport.xml" }
    Truth.assertThat(xmlReport1.size).isEqualTo(1)
    val aggregatedCoverageReportXml = xmlReport1.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport1.first(), "debugAppAggregated")
    verifyCoverageData(
      aggregatedCoverageReportXml,
      APP_EXPECTED_COVERED_INSTRUCTION_AGGREGATED,
      APP_EXPECTED_MISSED_INSTRUCTION_AGGREGATED,
      APP_EXPECTED_COVERED_BRANCH_AGGREGATED,
      APP_EXPECTED_MISSED_BRANCH_AGGREGATED,
    )
    verifyProperties(aggregatedCoverageReportXml, ":app", "Aggregated", "debug")
    verifySources(aggregatedCoverageReportXml, "app")

    val xmlReport2 = xmlReports.filter { it.name == "debugLibAggregatedXmlReport.xml" }
    Truth.assertThat(xmlReport2.size).isEqualTo(1)
    val aggregatedCoverageReportXmlLibModule = xmlReport2.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport2.first(), "debugLibAggregated")
    verifyCoverageData(
      aggregatedCoverageReportXmlLibModule,
      LIB_EXPECTED_COVERED_INSTRUCTION_AGGREGATED,
      LIB_EXPECTED_MISSED_INSTRUCTION_AGGREGATED,
      LIB_EXPECTED_COVERED_BRANCH_AGGREGATED,
      LIB_EXPECTED_MISSED_BRANCH_AGGREGATED,
    )
    verifyProperties(aggregatedCoverageReportXmlLibModule, ":lib", "Aggregated", "debug")
    verifySources(aggregatedCoverageReportXmlLibModule, "lib")

    val xmlReport3 = xmlReports.filter { it.name == "debugAppUnitTestXmlReport.xml" }
    Truth.assertThat(xmlReport3.size).isEqualTo(1)
    val unitTestCoverageReportXml = xmlReport3.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport3.first(), "debugAppUnitTest")
    verifyCoverageData(
      unitTestCoverageReportXml,
      APP_EXPECTED_COVERED_INSTRUCTION_UNIT_TEST,
      APP_EXPECTED_MISSED_INSTRUCTION_UNIT_TEST,
      APP_EXPECTED_COVERED_BRANCH_UNIT_TEST,
      APP_EXPECTED_MISSED_BRANCH_UNIT_TEST,
    )
    verifyProperties(unitTestCoverageReportXml, ":app", "UnitTest", "debug")
    verifySources(unitTestCoverageReportXml, "app")

    val xmlReport4 = xmlReports.filter { it.name == "debugAppAndroidTestXmlReport.xml" }
    Truth.assertThat(xmlReport4.size).isEqualTo(1)
    val androidTestCoverageReportXml = xmlReport4.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport4.first(), "debugAppAndroidTest")
    verifyCoverageData(
      androidTestCoverageReportXml,
      APP_EXPECTED_COVERED_INSTRUCTION_ANDROID_TEST,
      APP_EXPECTED_MISSED_INSTRUCTION_ANDROID_TEST,
      APP_EXPECTED_COVERED_BRANCH_ANDROID_TEST,
      APP_EXPECTED_MISSED_BRANCH_ANDROID_TEST,
    )
    verifyProperties(androidTestCoverageReportXml, ":app", "AndroidTest", "debug")
    verifySources(androidTestCoverageReportXml, "app")

    val xmlReport5 = xmlReports.filter { it.name == "debugLibUnitTestXmlReport.xml" }
    Truth.assertThat(xmlReport5.size).isEqualTo(1)
    val unitTestCoverageReportXmlLibModule = xmlReport5.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport5.first(), "debugLibUnitTest")
    verifyCoverageData(
      unitTestCoverageReportXmlLibModule,
      LIB_EXPECTED_COVERED_INSTRUCTION_UNIT_TEST,
      LIB_EXPECTED_MISSED_INSTRUCTION_UNIT_TEST,
      LIB_EXPECTED_COVERED_BRANCH_UNIT_TEST,
      LIB_EXPECTED_MISSED_BRANCH_UNIT_TEST,
    )
    verifyProperties(unitTestCoverageReportXmlLibModule, ":lib", "UnitTest", "debug")
    verifySources(unitTestCoverageReportXmlLibModule, "lib")

    val xmlReport6 = xmlReports.filter { it.name == "debugLibAndroidTestXmlReport.xml" }
    Truth.assertThat(xmlReport6.size).isEqualTo(1)
    val androidTestCoverageReportXmlLibModule = xmlReport6.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport6.first(), "debugLibAndroidTest")
    verifyCoverageData(
      androidTestCoverageReportXmlLibModule,
      LIB_EXPECTED_COVERED_INSTRUCTION_ANDROID_TEST,
      LIB_EXPECTED_MISSED_INSTRUCTION_ANDROID_TEST,
      LIB_EXPECTED_COVERED_BRANCH_ANDROID_TEST,
      LIB_EXPECTED_MISSED_BRANCH_ANDROID_TEST,
    )
    verifyProperties(androidTestCoverageReportXmlLibModule, ":lib", "AndroidTest", "debug")
    verifySources(androidTestCoverageReportXmlLibModule, "lib")

    val libBuildDir = build.androidLibrary(":lib").buildDir.toFile()

    val dependantTaskOutputDir = FileUtils.join(libBuildDir, "intermediates", "code_coverage_data", "global", "collectDebugCoverage")

    // Check collect task is executed for dependant module and correct xml reports are generated
    PathSubject.assertThat(dependantTaskOutputDir).exists()
    PathSubject.assertThat(dependantTaskOutputDir).isDirectory()

    val libModuleXmlReports = dependantTaskOutputDir.listFiles()
    Truth.assertThat(libModuleXmlReports.size).isEqualTo(3)

    val xmlReport7 = xmlReports.filter { it.name == "debugLibAggregatedXmlReport.xml" }
    Truth.assertThat(xmlReport7.size).isEqualTo(1)
    val aggregatedCoverageReportXmlLibModule2 = xmlReport7.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport7.first(), "debugLibAggregated")
    verifyCoverageData(
      aggregatedCoverageReportXmlLibModule2,
      LIB_EXPECTED_COVERED_INSTRUCTION_AGGREGATED,
      LIB_EXPECTED_MISSED_INSTRUCTION_AGGREGATED,
      LIB_EXPECTED_COVERED_BRANCH_AGGREGATED,
      LIB_EXPECTED_MISSED_BRANCH_AGGREGATED,
    )
    verifyProperties(aggregatedCoverageReportXmlLibModule2, ":lib", "Aggregated", "debug")
    verifySources(aggregatedCoverageReportXmlLibModule2, "lib")

    val xmlReport8 = xmlReports.filter { it.name == "debugLibUnitTestXmlReport.xml" }
    Truth.assertThat(xmlReport8.size).isEqualTo(1)
    val unitTestCoverageReportXmlLibModule2 = xmlReport8.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport8.first(), "debugLibUnitTest")
    verifyCoverageData(
      unitTestCoverageReportXmlLibModule2,
      LIB_EXPECTED_COVERED_INSTRUCTION_UNIT_TEST,
      LIB_EXPECTED_MISSED_INSTRUCTION_UNIT_TEST,
      LIB_EXPECTED_COVERED_BRANCH_UNIT_TEST,
      LIB_EXPECTED_MISSED_BRANCH_UNIT_TEST,
    )
    verifyProperties(unitTestCoverageReportXmlLibModule2, ":lib", "UnitTest", "debug")
    verifySources(unitTestCoverageReportXmlLibModule2, "lib")

    val xmlReport9 = xmlReports.filter { it.name == "debugLibAndroidTestXmlReport.xml" }
    Truth.assertThat(xmlReport9.size).isEqualTo(1)
    val androidTestCoverageReportXmlLibModule2 = xmlReport9.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport9.first(), "debugLibAndroidTest")
    verifyCoverageData(
      androidTestCoverageReportXmlLibModule2,
      LIB_EXPECTED_COVERED_INSTRUCTION_ANDROID_TEST,
      LIB_EXPECTED_MISSED_INSTRUCTION_ANDROID_TEST,
      LIB_EXPECTED_COVERED_BRANCH_ANDROID_TEST,
      LIB_EXPECTED_MISSED_BRANCH_ANDROID_TEST,
    )
    verifyProperties(androidTestCoverageReportXmlLibModule2, ":lib", "AndroidTest", "debug")
    verifySources(androidTestCoverageReportXmlLibModule2, "lib")
  }

  @Test
  fun testCollectDebugAggregatedCoverageForLibraryModule() {
    val build = rule.build

    // Expect the build to fail
    val result = build.executor.expectFailure().run(":lib:collectDebugAggregatedCoverage")

    // Assert that the failure reason is because the task was not found
    result.assertFailureMessage().contains("task 'collectDebugAggregatedCoverage' not found in project ':lib'")
  }

  @Test
  fun testCollectDebugAggregatedCoverageForLibraryModuleWithPublicationEnabled() {
    val build = rule.build
    build.executor.run(":lib2:collectDebugAggregatedCoverage")

    val appBuildDir = build.androidLibrary(":lib2").buildDir.toFile()

    val taskOutputDir =
      FileUtils.join(appBuildDir, "intermediates", "aggregated_code_coverage_data", "global", "collectDebugAggregatedCoverage")

    // Check data is collected for app and lib modules.
    PathSubject.assertThat(taskOutputDir).exists()
    PathSubject.assertThat(taskOutputDir).isDirectory()

    val xmlReports = taskOutputDir.listFiles().toList()

    Truth.assertThat(xmlReports.size).isEqualTo(3)
  }

  @Test
  fun testCollectDebugCoverageWithCorruptedFile() {
    val build = rule.build { androidApplication(":app") { pluginCallbacks += CodeCoverageCollectionTaskCallback::class.java } }

    val result = build.executor.expectFailure().run(":app:collectDebugCoverage")

    result.assertErrorContains("Unable to generate Jacoco XML report")
    result.assertTask(":app:collectDebugCoverage").failed()
  }

  @Test
  fun testCollectDebugCoverageFailsWhenJacocoVersionMismatch() {
    val build = rule.build { gradleProperties { add(StringOption.JACOCO_TOOL_VERSION, "0.8.12") } }

    val result = build.executor.expectFailure().run(":app:collectDebugCoverage")

    result.assertErrorContains("Cannot generate report. Please ensure a single Jacoco version is configured.")
    result.assertTask(":app:collectDebugCoverage").failed()
  }

  @Test
  fun testCollectDebugCoverageWithTestSuites() {
    val build = rule.build

    val result = build.executor.run(":app:collectDebugCoverage")

    Truth.assertThat(result.didWorkTasks).contains(":app:testFirstT1DebugTestSuite")
    Truth.assertThat(result.didWorkTasks).contains(":app:testFirstT2DebugTestSuite")
  }

  class CodeCoverageCollectionTaskCallback : GenericCallback {
    override fun handleProject(project: Project) {
      project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach { task ->
        task.doLast {
          val output = project.fileTree("${project.buildDir}/outputs/unit_test_code_coverage") { fileTree -> fileTree.include("**/*.exec") }
          output.files.forEach { file -> file.writeText("CORRUPTED") }
        }
      }
    }
  }

  /** Verifies that the report file has the expected name, and the report name inside the XML matches the expected report name. */
  private fun verifyReportName(xmlReport: File, expectedReportName: String) {
    val xmlReportString = xmlReport.readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    val xmlReportName = Regex("<report name=\"(.*?)\">").find(xmlReportString)!!.groups[1]!!.value
    Truth.assertThat(xmlReportName).isEqualTo(expectedReportName)
  }

  /**
   * Verifies the coverage data in the XML report string.
   *
   * @param xmlReportString The XML content of the report.
   * @param instructionCovered Expected number of covered instructions.
   * @param instructionMissed Expected number of missed instructions.
   * @param branchCovered Expected number of covered branches.
   * @param branchMissed Expected number of missed branches.
   */
  private fun verifyCoverageData(
    xmlReportString: String,
    instructionCovered: Int,
    instructionMissed: Int,
    branchCovered: Int,
    branchMissed: Int,
  ) {

    val expectedCounters =
      "<counter covered=\"${instructionCovered}\" missed=\"${instructionMissed}\" type=\"INSTRUCTION\"/>" +
        "<counter covered=\"${branchCovered}\" missed=\"${branchMissed}\" type=\"BRANCH\"/>"

    Truth.assertThat(xmlReportString.contains(expectedCounters)).isTrue()
  }

  /** Verifies that the report contains the correct properties identifying the module, test suite and variant. */
  private fun verifyProperties(xmlReportString: String, moduleName: String, testSuiteName: String, testedVariantName: String) {

    val expectedProperties =
      "<properties>" +
        "<property name=\"moduleName\" value=\"${moduleName}\"/>" +
        "<property name=\"testSuiteName\" value=\"${testSuiteName}\"/>" +
        "<property name=\"testedVariantName\" value=\"${testedVariantName}\"/>" +
        "</properties>"

    Truth.assertThat(xmlReportString.contains(expectedProperties)).isTrue()
  }

  /** Verifies that the report contains the expected source file paths. */
  private fun verifySources(xmlReportString: String, moduleName: String) {
    val expectedSources =
      "<sources>" +
        "<file path=\"$moduleName/src/main/java\"/>" +
        "<file path=\"$moduleName/src/debug/java\"/>" +
        "<file path=\"$moduleName/src/main/kotlin\"/>" +
        "<file path=\"$moduleName/src/debug/kotlin\"/>" +
        "</sources>"

    Truth.assertThat(xmlReportString.contains(expectedSources)).isTrue()
  }

  class CustomEngineDescriptor(uniqueId: UniqueId) : AbstractTestDescriptor(uniqueId, "Custom Engine Root") {
    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER
  }

  class CustomTestDescriptor(uniqueId: UniqueId, testDescriptor: String) : AbstractTestDescriptor(uniqueId, testDescriptor) {
    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST
  }

  class CustomJunitEngineForTesting : TestEngine {

    override fun getId(): String {
      return "[engine:custom-junit-engine-for-tests]"
    }

    override fun discover(discoveryRequest: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor {
      val engineDescriptor = CustomEngineDescriptor(uniqueId)

      val testId = uniqueId.append("test", "some-test")
      val testDescriptor = CustomTestDescriptor(testId, "testFunctionName")
      engineDescriptor.addChild(testDescriptor)

      return engineDescriptor
    }

    override fun execute(request: ExecutionRequest) {
      val listener: EngineExecutionListener = request.engineExecutionListener
      val rootDescriptor = request.rootTestDescriptor

      listener.executionStarted(rootDescriptor)

      for (testDescriptor in rootDescriptor.children) {
        listener.executionStarted(testDescriptor)
        val testResult =
          try {
            val testSucceeded = System.getenv("CUSTOM_ENGINE_SUCCEED")?.toBoolean() ?: true
            if (testSucceeded) {
              TestExecutionResult.successful()
            } else {
              TestExecutionResult.failed(Exception("Test failed"))
            }
          } catch (t: Throwable) {
            TestExecutionResult.failed(t)
          }
        listener.executionFinished(testDescriptor, testResult)
      }

      listener.executionFinished(rootDescriptor, TestExecutionResult.successful())
    }
  }
}
