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

import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.tasks.TestSuiteTestTask.Companion.CONNECTED_TEST_TEST_SUITE_NAME
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import com.google.gson.Gson
import java.io.File
import org.gradle.internal.logging.ConsoleRenderer
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Integration test for [com.android.build.gradle.internal.test.tasks.TestReportTask] and
 * [com.android.build.gradle.internal.test.tasks.TestResultsCollectionTask] evaluating cross-module unit test reporting.
 */
@RunWith(Parameterized::class)
class TestReportAggregationTest(val runWithBuiltInPlatform: Boolean) {

  companion object {
    @ClassRule @JvmField val emulator: ExternalResource = getEmulator()

    @JvmStatic
    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }

  @get:Rule
  val rule =
    GradleRule.fromProject("reportAggregation") {
      androidApplication(":app") {
        android {
          namespace = "com.example.app"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          installation { timeOutInMs = 30000 }
          defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
          buildTypes {
            named("debug") {
              it.enableUnitTestCoverage = true
              it.enableAndroidTestCoverage = true
            }
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
          "src/testRelease/java/com/example/app/ReleaseTest.kt",
          """
          package com.example.app
          import org.junit.Test
          class ReleaseTest {
              @Test
              fun testRelease() {}
          }
          """
            .trimIndent(),
        )
      }
      androidLibrary(":lib") {
        android {
          namespace = "com.example.lib"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          installation { timeOutInMs = 30000 }
          defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
          buildTypes {
            named("debug") {
              it.enableUnitTestCoverage = true
              it.enableAndroidTestCoverage = true
            }
          }
        }
        dependencies {
          implementation(project(":lib2"))

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
      androidLibrary(":lib2") {
        android {
          namespace = "com.example.lib2"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          installation { timeOutInMs = 30000 }
          defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
          publishing { singleVariant("debug") }
          buildTypes {
            named("debug") {
              it.enableUnitTestCoverage = true
              it.enableAndroidTestCoverage = true
            }
          }
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
        files.add(
          "src/androidTest/java/com/example/lib2/FailingAndroidTest.kt",
          """
          package com.example.lib2
          import org.junit.Test
          import org.junit.Assert.fail
          class FailingAndroidTest {
              @Test
              fun testFailure() {
                  fail("This test is supposed to fail")
              }
          }
          """
            .trimIndent(),
        )
        files.add(
          "src/test/java/com/example/lib2/FailingUnitTest.kt",
          """
          package com.example.lib2
          import org.junit.Test
          import org.junit.Assert.fail
          class FailingUnitTest {
              @Test
              fun testFailure() {
                  fail("This test is supposed to fail")
              }
          }
          """
            .trimIndent(),
        )
      }
      gradleProperties {
        add(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
        // this is to test the multi-variant support for coverage reporting
        add(BooleanOption.ONLY_ENABLE_UNIT_TEST_BY_DEFAULT_FOR_THE_TESTED_BUILD_TYPE, false)
        add(BooleanOption.REPORT_AGGREGATION_SUPPORT, true)
        add(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
      }
    }

  @Test
  fun testTestAllSuitesWithFailingTest() {
    // unit test is expected to fail if run separately
    rule.build.executor.expectFailure().run(":lib2:testDebugUnitTest")
    // connected test is expected to fail if run separately
    rule.build.executor.expectFailure().run(":lib2:connectedDebugAndroidTest")
    // check failing test case won't fail the build when running the test report task
    val result = rule.build.executor.run(":lib2:testAllSuites")
    val libBuildDir = rule.build.androidLibrary(":lib2").buildDir.toFile()
    val outputDir = FileUtils.join(libBuildDir, "reports", "tests", "test-report")

    verifyHtmlReport(
      outputDir = outputDir,
      taskResult = result,
      expectedModuleCount = 1,
      expectedTotalTests = 18,
      expectedFailedTests = 3,
      expectedUnitTestSummary = TestSummary(total = 16, passed = 14, failed = 2, skipped = 0),
      expectedAndroidTestSummary = TestSummary(total = 2, passed = 1, failed = 1, skipped = 0),
    )
  }

  @Test
  fun testTestAllSuitesIncludingAllVariants() {
    val result = rule.build.executor.run(":app:testAllSuites")
    val appBuildDir = rule.build.androidApplication(":app").buildDir.toFile()
    val outputDir = FileUtils.join(appBuildDir, "reports", "tests", "test-report")

    assertThat(result.tasks.contains(":app:testDebugUnitTest")).isTrue()
    assertThat(result.tasks.contains(":app:testReleaseUnitTest")).isTrue()

    verifyHtmlReport(
      outputDir = outputDir,
      taskResult = result,
      expectedModuleCount = 1,
      expectedTotalTests = 16,
      expectedFailedTests = 0,
      expectedUnitTestSummary = TestSummary(total = 15, passed = 15, failed = 0, skipped = 0),
      expectedAndroidTestSummary = TestSummary(total = 1, passed = 1, failed = 0, skipped = 0),
    )
  }

  @Test
  fun testTestAllSuitesWithDependencies() {
    val result = rule.build.executor.run(":app:testAllSuitesWithDependencies")
    val appBuildDir = rule.build.androidApplication(":app").buildDir.toFile()
    val outputDir = FileUtils.join(appBuildDir, "reports", "tests", "aggregated-test-report")

    assertThat(result.tasks.contains(":app:testDebugUnitTest")).isTrue()
    assertThat(result.tasks.contains(":app:connectedDebugAndroidTest")).isTrue()
    assertThat(result.tasks.contains(":lib:testDebugUnitTest")).isTrue()
    assertThat(result.tasks.contains(":lib:connectedDebugAndroidTest")).isTrue()
    assertThat(result.tasks.contains(":lib2:testDebugUnitTest")).isTrue()
    assertThat(result.tasks.contains(":lib2:connectedDebugAndroidTest")).isTrue()

    verifyHtmlReport(
      outputDir = outputDir,
      taskResult = result,
      expectedModuleCount = 3,
      expectedTotalTests = 49,
      expectedFailedTests = 3,
      expectedUnitTestSummary = TestSummary(total = 45, passed = 43, failed = 2, skipped = 0),
      expectedAndroidTestSummary = TestSummary(total = 4, passed = 3, failed = 1, skipped = 0),
    )
  }

  @Test
  fun testTestAllSuitesLib() {
    val result = rule.build.executor.run(":lib:testAllSuites")
    val libBuildDir = rule.build.androidLibrary(":lib").buildDir.toFile()
    assertThat(result.tasks.contains(":lib:testDebugUnitTest")).isTrue()
    assertThat(result.tasks.contains(":lib:connectedDebugAndroidTest")).isTrue()
    val outputDir = FileUtils.join(libBuildDir, "reports", "tests", "test-report")

    verifyHtmlReport(
      outputDir = outputDir,
      taskResult = result,
      expectedModuleCount = 1,
      expectedTotalTests = 15,
      expectedFailedTests = 0,
      expectedUnitTestSummary = TestSummary(total = 14, passed = 14, failed = 0, skipped = 0),
      expectedAndroidTestSummary = TestSummary(total = 1, passed = 1, failed = 0, skipped = 0),
    )
  }

  @Test
  fun testTestAllSuitesWithDependenciesLib() {
    val result = rule.build.executor.run(":lib2:testAllSuitesWithDependencies")
    val libBuildDir = rule.build.androidLibrary(":lib2").buildDir.toFile()
    val outputDir = FileUtils.join(libBuildDir, "reports", "tests", "aggregated-test-report")

    verifyHtmlReport(
      outputDir = outputDir,
      taskResult = result,
      expectedModuleCount = 1,
      expectedTotalTests = 10,
      expectedFailedTests = 2,
      expectedUnitTestSummary = TestSummary(total = 8, passed = 7, failed = 1, skipped = 0),
      expectedAndroidTestSummary = TestSummary(total = 2, passed = 1, failed = 1, skipped = 0),
    )
  }

  @Test
  fun testTestAllSuitesWithDependenciesForNonPublishedLibModule() {
    val build = rule.build
    val aggregatedReportLibResult = build.executor.expectFailure().run(":lib:testAllSuitesWithDependencies")
    aggregatedReportLibResult.assertFailureMessage().contains("task 'testAllSuitesWithDependencies' not found in project ':lib'")
  }

  private val gson = Gson()

  private fun verifyHtmlReport(
    outputDir: File,
    taskResult: GradleBuildResult,
    expectedProjectName: String = "project",
    expectedModuleCount: Int = 1,
    expectedTotalTests: Int? = null,
    expectedFailedTests: Int? = null,
    expectedUnitTestSummary: TestSummary? = null,
    expectedAndroidTestSummary: TestSummary? = null,
  ) {
    assertThat(outputDir).exists()
    assertThat(outputDir).isDirectory()

    val indexFile = File(outputDir, "index.html")
    assertThat(indexFile).exists()
    assertThat(indexFile).isFile()

    val reportLocation = ConsoleRenderer().asClickableFileUrl(indexFile)
    taskResult.assertOutputContains("Test report generated at: $reportLocation")

    assertThat(File(outputDir, "script.js")).exists()
    assertThat(File(outputDir, "styles.css")).exists()
    assertThat(File(outputDir, "data.js")).exists()

    val coverageOutputDir =
      if (outputDir.name == "aggregated-test-report") {
        outputDir.parentFile.parentFile.resolve("coverage/aggregated-coverage-report")
      } else {
        outputDir.parentFile.parentFile.resolve("coverage/coverage-report")
      }

    if (coverageOutputDir.exists()) {
      assertThat(coverageOutputDir).isDirectory()
      assertThat(File(coverageOutputDir, "index.html")).exists()
    }

    val report = parseReportJs<TestReport>(File(outputDir, "data.js"))

    assertThat(report.projectName).isEqualTo(expectedProjectName)
    assertThat(report.numberOfModules).isEqualTo(expectedModuleCount)

    // Aggregate from the internal "Aggregated" suite
    val totalSummary = aggregateFromAggregatedSuite(report)
    expectedTotalTests?.let { assertThat(totalSummary.total).isEqualTo(it) }
    expectedFailedTests?.let { assertThat(totalSummary.failed).isEqualTo(it) }

    expectedUnitTestSummary?.let { expected ->
      val actual = aggregateSuiteSummaries(report, "UnitTest")
      verifySummary(actual, expected, "UnitTest")
    }

    expectedAndroidTestSummary?.let { expected ->
      val actual = aggregateSuiteSummaries(report, CONNECTED_TEST_TEST_SUITE_NAME)
      verifySummary(actual, expected, CONNECTED_TEST_TEST_SUITE_NAME)
    }
  }

  private fun aggregateFromAggregatedSuite(report: TestReport): TestSummary {
    val summaries = report.modules.flatMap { m -> m.testSuiteSummaries.find { it.name == "Aggregated" }?.variantSummaries ?: emptyList() }
    return TestSummary(
      total = summaries.sumOf { it.total },
      passed = summaries.sumOf { it.passed },
      failed = summaries.sumOf { it.failed },
      skipped = summaries.sumOf { it.skipped },
    )
  }

  private fun aggregateSuiteSummaries(report: TestReport, suiteName: String): TestSummary {
    val summaries =
      report.modules
        .flatMap { it.testSuiteSummaries }
        .filter { it.name.contains(suiteName, ignoreCase = true) }
        .flatMap { it.variantSummaries }
    return TestSummary(
      total = summaries.sumOf { it.total },
      passed = summaries.sumOf { it.passed },
      failed = summaries.sumOf { it.failed },
      skipped = summaries.sumOf { it.skipped },
    )
  }

  private fun verifySummary(actual: TestSummary, expected: TestSummary, suiteName: String) {
    assertThat(actual.total).named("$suiteName total").isEqualTo(expected.total)
    assertThat(actual.passed).named("$suiteName passed").isEqualTo(expected.passed)
    assertThat(actual.failed).named("$suiteName failed").isEqualTo(expected.failed)
  }

  @Test
  fun testCollectDebugCoverage() {
    val build = rule.build
    build.executor.run(":app:testAllSuites")

    val appBuildDir = build.androidApplication(":app").buildDir.toFile()

    val taskOutputDir = FileUtils.join(appBuildDir, "intermediates", "code_coverage_data")
    assertThat(taskOutputDir).exists()
    assertThat(taskOutputDir).isDirectory()

    val xmlReports = taskOutputDir.walkTopDown().filter { it.name.endsWith("XmlReport.xml") }.toList()
    assertThat(xmlReports.size).isEqualTo(3)

    val xmlReport1 = xmlReports.filter { it.name == "debugAppAggregatedXmlReport.xml" }
    assertThat(xmlReport1.size).isEqualTo(1)
    verifyReportName(xmlReport1.first(), "debugAppAggregated")
    val aggregatedCoverageReportXml = xmlReport1.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyCoverageData(
      xmlReportString = aggregatedCoverageReportXml,
      instructionCovered = 28, // 23 (unit test) + 5 (android test)
      instructionMissed = 14, // 42 (total) - 28
      branchCovered = 1, // 1 (unit test) + 0 (android test)
      branchMissed = 3, // 4 (total) - 1
    )
    verifyProperties(aggregatedCoverageReportXml, ":app", "Aggregated", "debug")
    verifySources(aggregatedCoverageReportXml, "app")

    val xmlReport2 = xmlReports.filter { it.name == "debugAppUnitTestXmlReport.xml" }
    assertThat(xmlReport2.size).isEqualTo(1)
    val unitTestCoverageReportXml = xmlReport2.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport2.first(), "debugAppUnitTest")
    verifyCoverageData(
      xmlReportString = unitTestCoverageReportXml,
      instructionCovered = 23,
      instructionMissed = 19,
      branchCovered = 1,
      branchMissed = 3,
    )
    verifyProperties(unitTestCoverageReportXml, ":app", "UnitTest", "debug")
    verifySources(unitTestCoverageReportXml, "app")

    val xmlReport3 = xmlReports.filter { it.name == "debugAppAndroidTestXmlReport.xml" }
    assertThat(xmlReport3.size).isEqualTo(1)
    val androidTestCoverageReportXml = xmlReport3.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReport3.first(), "debugAppAndroidTest")
    verifyCoverageData(
      xmlReportString = androidTestCoverageReportXml,
      instructionCovered = 5,
      instructionMissed = 37,
      branchCovered = 0,
      branchMissed = 4,
    )
    verifyProperties(androidTestCoverageReportXml, ":app", CONNECTED_TEST_TEST_SUITE_NAME, "debug")
    verifySources(androidTestCoverageReportXml, "app")
  }

  @Test
  fun testCollectDebugAggregatedCoverage() {
    val build = rule.build
    build.executor.run(":app:testAllSuitesWithDependencies")

    val appBuildDir = build.androidApplication(":app").buildDir.toFile()

    val taskOutputDir = FileUtils.join(appBuildDir, "intermediates", "aggregated_code_coverage_data")
    assertThat(taskOutputDir).exists()

    val xmlReports = taskOutputDir.walkTopDown().filter { it.name.endsWith("XmlReport.xml") }.toList()
    assertThat(xmlReports.size).isEqualTo(9)

    // Verify debugAppAggregatedXmlReport.xml
    val xmlReportAppAggregated = xmlReports.filter { it.name == "debugAppAggregatedXmlReport.xml" }
    assertThat(xmlReportAppAggregated.size).isEqualTo(1)
    val appAggregatedCoverageXml = xmlReportAppAggregated.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReportAppAggregated.first(), "debugAppAggregated")
    verifyCoverageData(
      xmlReportString = appAggregatedCoverageXml,
      instructionCovered = 28, // 23 (unit test) + 5 (android test)
      instructionMissed = 14, // 42 (total) - 28
      branchCovered = 1, // 1 (unit test) + 0 (android test)
      branchMissed = 3, // 4 (total) - 1
    )
    verifyProperties(appAggregatedCoverageXml, ":app", "Aggregated", "debug")
    verifySources(appAggregatedCoverageXml, "app")

    // Verify debugLibAggregatedXmlReport.xml
    val xmlReportLibAggregated = xmlReports.filter { it.name == "debugLibAggregatedXmlReport.xml" }
    assertThat(xmlReportLibAggregated.size).isEqualTo(1)
    val libAggregatedCoverageXml = xmlReportLibAggregated.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReportLibAggregated.first(), "debugLibAggregated")
    verifyCoverageData(
      xmlReportString = libAggregatedCoverageXml,
      instructionCovered = 37, // 32 (unit test) + 5 (android test)
      instructionMissed = 5, // 42 (total) - 37
      branchCovered = 3, // 3 (unit test) + 0 (android test)
      branchMissed = 1, // 4 (total) - 3
    )
    verifyProperties(libAggregatedCoverageXml, ":lib", "Aggregated", "debug")
    verifySources(libAggregatedCoverageXml, "lib")

    // Verify debugAppUnitTestXmlReport.xml
    val xmlReportAppUnitTest = xmlReports.filter { it.name == "debugAppUnitTestXmlReport.xml" }
    assertThat(xmlReportAppUnitTest.size).isEqualTo(1)
    val appUnitTestCoverageXml = xmlReportAppUnitTest.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReportAppUnitTest.first(), "debugAppUnitTest")
    verifyCoverageData(
      xmlReportString = appUnitTestCoverageXml,
      instructionCovered = 23,
      instructionMissed = 19,
      branchCovered = 1,
      branchMissed = 3,
    )
    verifyProperties(appUnitTestCoverageXml, ":app", "UnitTest", "debug")
    verifySources(appUnitTestCoverageXml, "app")

    // Verify debugAppAndroidTestXmlReport.xml
    val xmlReportAppAndroidTest = xmlReports.filter { it.name == "debugAppAndroidTestXmlReport.xml" }
    assertThat(xmlReportAppAndroidTest.size).isEqualTo(1)
    val appAndroidTestCoverageXml = xmlReportAppAndroidTest.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReportAppAndroidTest.first(), "debugAppAndroidTest")
    verifyCoverageData(
      xmlReportString = appAndroidTestCoverageXml,
      instructionCovered = 5,
      instructionMissed = 37,
      branchCovered = 0,
      branchMissed = 4,
    )
    verifyProperties(appAndroidTestCoverageXml, ":app", CONNECTED_TEST_TEST_SUITE_NAME, "debug")
    verifySources(appAndroidTestCoverageXml, "app")

    // Verify debugLibUnitTestXmlReport.xml
    val xmlReportLibUnitTest = xmlReports.filter { it.name == "debugLibUnitTestXmlReport.xml" }
    assertThat(xmlReportLibUnitTest.size).isEqualTo(1)
    val libUnitTestCoverageXml = xmlReportLibUnitTest.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReportLibUnitTest.first(), "debugLibUnitTest")
    verifyCoverageData(
      xmlReportString = libUnitTestCoverageXml,
      instructionCovered = 32,
      instructionMissed = 10,
      branchCovered = 3,
      branchMissed = 1,
    )
    verifyProperties(libUnitTestCoverageXml, ":lib", "UnitTest", "debug")
    verifySources(libUnitTestCoverageXml, "lib")

    // Verify debugLibAndroidTestXmlReport.xml
    val xmlReportLibAndroidTest = xmlReports.filter { it.name == "debugLibAndroidTestXmlReport.xml" }
    assertThat(xmlReportLibAndroidTest.size).isEqualTo(1)
    val libAndroidTestCoverageXml = xmlReportLibAndroidTest.first().readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    verifyReportName(xmlReportLibAndroidTest.first(), "debugLibAndroidTest")
    verifyCoverageData(
      xmlReportString = libAndroidTestCoverageXml,
      instructionCovered = 5,
      instructionMissed = 37,
      branchCovered = 0,
      branchMissed = 4,
    )
    verifyProperties(libAndroidTestCoverageXml, ":lib", CONNECTED_TEST_TEST_SUITE_NAME, "debug")
    verifySources(libAndroidTestCoverageXml, "lib")
  }

  private fun verifyReportName(xmlReport: File, expectedReportName: String) {
    val xmlReportString = xmlReport.readLines().joinToString("\n").replace(Regex("[\\n\\t\\r]"), "")
    val xmlReportName = Regex("<report name=\"(.*?)\">").find(xmlReportString)!!.groups[1]!!.value
    assertThat(xmlReportName).isEqualTo(expectedReportName)
  }

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

    assertThat(xmlReportString.contains(expectedCounters)).isTrue()
  }

  private fun verifyProperties(xmlReportString: String, moduleName: String, testSuiteName: String, testedVariantName: String) {
    val expectedProperties =
      "<properties>" +
        "<property name=\"modulePath\" value=\"${moduleName}\"/>" +
        "<property name=\"testSuiteName\" value=\"${testSuiteName}\"/>" +
        "<property name=\"testedVariantName\" value=\"${testedVariantName}\"/>" +
        "</properties>"

    assertThat(xmlReportString.contains(expectedProperties)).isTrue()
  }

  private fun verifySources(xmlReportString: String, moduleName: String) {
    val expectedSources =
      "<sources>" +
        "<file path=\"$moduleName/src/main/java\"/>" +
        "<file path=\"$moduleName/src/debug/java\"/>" +
        "<file path=\"$moduleName/src/main/kotlin\"/>" +
        "<file path=\"$moduleName/src/debug/kotlin\"/>" +
        "</sources>"

    assertThat(xmlReportString.contains(expectedSources)).isTrue()
  }

  private inline fun <reified T> parseReportJs(file: File): T {
    val content = file.readText().removePrefix("const TEST_DATA_SOURCE = ")
    return gson.fromJson(content, T::class.java)
  }

  data class TestReport(val projectName: String, val numberOfModules: Int, val modules: List<ModuleReport>)

  data class ModuleReport(val name: String, val testSuiteSummaries: List<TestSuiteSummary>)

  data class TestSuiteSummary(val name: String, val variantSummaries: List<VariantSummary>)

  data class VariantSummary(val total: Int, val passed: Int, val failed: Int, val skipped: Int)

  data class TestSummary(val total: Int, val passed: Int, val failed: Int, val skipped: Int)
}
