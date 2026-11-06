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

package com.android.build.gradle.internal.test.report

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Helper to provide more context on test failures
fun <T> List<T>.findOrThrow(predicate: (T) -> Boolean, message: () -> String): T {
  return find(predicate) ?: throw NoSuchElementException(message())
}

class XMLReportAggregatorTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var outputDir: File
  private lateinit var inputDir1: File
  private lateinit var inputDir2: File
  private lateinit var defaultRootDir: File

  @Before
  fun setUp() {
    outputDir = temporaryFolder.newFolder("output")
    inputDir1 = temporaryFolder.newFolder("input1")
    inputDir2 = temporaryFolder.newFolder("input2")
    defaultRootDir = temporaryFolder.newFolder("default_root")
  }

  private fun createAggregator(
    files: List<File>,
    projectName: String,
    rootDir: File = defaultRootDir,
  ): XMLReportAggregator = XMLReportAggregator(files, projectName, rootDir)

  private fun createXmlReport(directory: File, fileName: String, content: String) {
    File(directory, fileName).writeText(content)
  }

  @Test
  fun testGenerateReport_singlePassTest() {
    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testExample" classname="com.example.app.MyClassTest" time="0.1"/>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "test-report.xml", xmlContent)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "MyProject")
    val report = aggregator.generateReport()

    assertThat(report.projectName).isEqualTo("MyProject")
    assertThat(report.timestamp).isNotEmpty()
    assertThat(report.numberOfModules).isEqualTo(1)
    assertThat(report.numberOfPackages).isEqualTo(1)
    assertThat(report.numberOfClasses).isEqualTo(1)
    assertThat(report.modules).hasSize(1)

    val module = report.modules.first()
    assertThat(module.name).isEqualTo(":app")
    // unitTest + Aggregated
    val suiteNames = module.testSuiteSummaries.map { it.name }
    if (suiteNames.size != 2) {
      println("DEBUG: suiteNames for module = $suiteNames")
    }
    assertThat(suiteNames).containsExactly("unitTest", "Aggregated")

    val pkg = module.packages.first()
    assertThat(pkg.name).isEqualTo("com.example.app")
    assertThat(pkg.testSuiteSummaries.map { it.name }).containsExactly("unitTest", "Aggregated")

    val clazz = pkg.classes.first()
    assertThat(clazz.name).isEqualTo("MyClassTest")
    assertThat(clazz.testSuiteSummaries.map { it.name }).containsExactly("unitTest", "Aggregated")

    val testCase = clazz.testCases.first()
    assertThat(testCase.name).isEqualTo("testExample")

    // Extract properties from the first target (Host default)
    val target = testCase.targets.first()
    assertThat(target.name).isEqualTo(UNKNOWN_TARGET)
    assertThat(target.testSuiteSummaries.map { it.name }).containsExactly("unitTest", "Aggregated")
    assertThat(target.testSuiteResults).hasSize(1)

    val suiteResult = target.testSuiteResults.find { it.testSuiteName == "unitTest" }
    assertThat(suiteResult).isNotNull()
    assertThat(suiteResult?.variantResults?.get("debug")?.status).isEqualTo("pass")
  }

  @Test
  fun testGenerateReport_multipleVariantsAndStatuses() {
    val xmlContent1 =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="2" failures="1" errors="0" skipped="0" time="0.2">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testPass" classname="com.example.app.MyClassTest" time="0.1"/>
          <testcase name="testFail" classname="com.example.app.MyClassTest" time="0.1">
              <failure message="assertion failed">stacktrace here</failure>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "test-report-1.xml", xmlContent1)

    val xmlContent2 =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyOtherTestSuite" tests="2" failures="0" errors="0" skipped="1" time="0.3">
          <properties>
              <property name="testedVariantName" value="release"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="otherTestSuite"/>
          </properties>
          <testcase name="testAnotherPass" classname="com.example.app.MyOtherClassTest" time="0.2"/>
          <testcase name="testSkipped" classname="com.example.app.MyOtherClassTest" time="0.1">
              <skipped/>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir2, "test-report-2.xml", xmlContent2)

    val aggregator = createAggregator(files = listOf(inputDir1, inputDir2), projectName = "MyMultiVariantProject")
    val report = aggregator.generateReport()

    assertThat(report.projectName).isEqualTo("MyMultiVariantProject")
    assertThat(report.timestamp).isNotEmpty()
    assertThat(report.numberOfModules).isEqualTo(1)
    assertThat(report.numberOfPackages).isEqualTo(1)
    assertThat(report.numberOfClasses).isEqualTo(2)
    assertThat(report.variants).containsExactly("debug", "release").inOrder()
    assertThat(report.modules).hasSize(1)

    val appModule = report.modules.first()
    assertThat(appModule.name).isEqualTo(":app")
    // unitTest, otherTestSuite + Aggregated
    val suiteNames = appModule.testSuiteSummaries.map { it.name }
    assertThat(suiteNames).containsExactly("unitTest", "otherTestSuite", "Aggregated")

    val pkg = appModule.packages.first()
    assertThat(pkg.name).isEqualTo("com.example.app")
    assertThat(pkg.classes).hasSize(2)

    val unitTestClass = pkg.classes.findOrThrow({ it.name == "MyClassTest" }) { "MyClassTest not found" }
    assertThat(unitTestClass.testCases).hasSize(2)

    val testPass = unitTestClass.testCases.find { it.name == "testPass" }
    assertThat(testPass?.targets?.first()?.testSuiteResults?.first()?.variantResults?.get("debug")?.status).isEqualTo("pass")

    val testFail = unitTestClass.testCases.find { it.name == "testFail" }
    assertThat(testFail?.targets?.first()?.testSuiteResults?.first()?.variantResults?.get("debug")?.status).isEqualTo("fail")

    val stackTraceId = testFail?.targets?.first()?.testSuiteResults?.first()?.variantResults?.get("debug")?.stackTraceId
    assertThat(stackTraceId).isNotNull()
    val group = testFail?.targets?.first()?.commonStackTraces?.find { it.id == stackTraceId }
    assertThat(group?.stackTrace).contains("stacktrace here")

    val otherTestClass = pkg.classes.findOrThrow({ it.name == "MyOtherClassTest" }) { "MyOtherClassTest not found" }
    assertThat(otherTestClass.testCases).hasSize(2)

    val testAnotherPass = otherTestClass.testCases.find { it.name == "testAnotherPass" }
    assertThat(testAnotherPass?.targets?.first()?.testSuiteResults?.first()?.variantResults?.get("release")?.status).isEqualTo("pass")

    val testSkipped = otherTestClass.testCases.find { it.name == "testSkipped" }
    assertThat(testSkipped?.targets?.first()?.testSuiteResults?.first()?.variantResults?.get("release")?.status).isEqualTo("skipped")
  }

  @Test
  fun testWriteReport_outputFilesCreated() {
    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testExample" classname="com.example.app.MyClassTest" time="0.1"/>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "test-report.xml", xmlContent)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "MyProject")
    aggregator.writeReport(outputDir)

    assertThat(aggregator.getTestCount()).isEqualTo(1)
    assertThat(File(outputDir, "data.js").exists()).isTrue()
    assertThat(File(outputDir, "index.html").exists()).isTrue()
    assertThat(File(outputDir, "styles.css").exists()).isTrue()
    assertThat(File(outputDir, "script.js").exists()).isTrue()

    val dataJsContent = File(outputDir, "data.js").readText()
    assertThat(dataJsContent).contains("const TEST_DATA_SOURCE = {")
    assertThat(dataJsContent).contains("\"projectName\":\"MyProject\"")
    assertThat(dataJsContent).contains("\"timestamp\"")
    assertThat(dataJsContent).contains("debug")
    assertThat(dataJsContent).contains("testExample")
  }

  @Test
  fun testGetFailedTestCount_countsFailuresAndErrors() {
    val xmlContent1 =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="2" failures="1" errors="0" skipped="0" time="0.2">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testPass" classname="com.example.app.MyClassTest" time="0.1"/>
          <testcase name="testFail" classname="com.example.app.MyClassTest" time="0.1">
              <failure message="assertion failed">stacktrace here</failure>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "test-report-1.xml", xmlContent1)

    val xmlContent2 =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.lib.MyTestSuite" tests="2" failures="0" errors="1" skipped="1" time="0.2">
          <properties>
              <property name="testedVariantName" value="release"/>
              <property name="modulePath" value=":lib"/>
              <property name="testSuiteName" value="androidTest"/>
          </properties>
          <testcase name="testError" classname="com.example.lib.MyClassTest" time="0.1">
              <error message="crashed">stacktrace here</error>
          </testcase>
          <testcase name="testSkipped" classname="com.example.lib.MyClassTest" time="0.1">
              <skipped/>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir2, "test-report-2.xml", xmlContent2)

    val aggregator = createAggregator(files = listOf(inputDir1, inputDir2), projectName = "MyProject")

    assertThat(aggregator.getFailedTestCount()).isEqualTo(2)
  }

  @Test
  fun testGetFailedTestCount_noFailures() {
    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="2" failures="0" errors="0" skipped="1" time="0.1">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testExample" classname="com.example.app.MyClassTest" time="0.1"/>
          <testcase name="testSkipped" classname="com.example.app.MyClassTest" time="0.0">
              <skipped/>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "test-report.xml", xmlContent)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "MyProject")

    assertThat(aggregator.getFailedTestCount()).isEqualTo(0)
  }

  @Test
  fun testProcessXmlStream_failureStackTrace() {
    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.FailureSuite" tests="1" failures="1" errors="0" skipped="0" time="0.5">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":lib"/>
              <property name="testSuiteName" value="failedUnitTest"/>
          </properties>
          <testcase name="testFailure" classname="com.example.app.MyFailedClassTest" time="0.5">
              <failure message="Expected exception: java.lang.RuntimeException">
                  java.lang.RuntimeException: This is a test exception
                  at com.example.app.MyFailedClassTest.testFailure(MyFailedClassTest.kt:10)
              </failure>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "test-report.xml", xmlContent)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "FailureProject")
    val report = aggregator.generateReport()

    val module = report.modules.findOrThrow({ it.name == ":lib" }) { ":lib module not found" }
    val pkg = module.packages.findOrThrow({ it.name == "com.example.app" }) { "com.example.app package not found" }
    val clazz = pkg.classes.findOrThrow({ it.name == "MyFailedClassTest" }) { "MyFailedClassTest not found" }
    val testCase = clazz.testCases.findOrThrow({ it.name == "testFailure" }) { "testFailure testcase not found" }

    assertThat(testCase.name).isEqualTo("testFailure")

    val suiteResult = testCase.targets.first().testSuiteResults.find { it.testSuiteName == "failedUnitTest" }
    assertThat(suiteResult?.variantResults?.get("debug")?.status).isEqualTo("fail")

    val stackTraceId = suiteResult?.variantResults?.get("debug")?.stackTraceId
    val group = testCase.targets.first().commonStackTraces.find { it.id == stackTraceId }
    assertThat(group?.stackTrace).contains("java.lang.RuntimeException: This is a test exception")
    assertThat(group?.stackTrace).contains("at com.example.app.MyFailedClassTest.testFailure(MyFailedClassTest.kt:10)")
  }

  @Test
  fun testProcessXmlForAggregation_nonExistentDirectory() {
    val nonExistentDir = File(temporaryFolder.root, "nonExistent")

    val aggregator = createAggregator(files = listOf(nonExistentDir), projectName = "ProjectWithMissingFile")
    // This should not throw an exception, but rather log a warning
    val report = aggregator.generateReport()
    assertThat(report.modules).isEmpty()
    assertThat(report.variants).isEmpty()
  }

  @Test
  fun testGenerateReport_variantSpecificTests() {
    val stagingDebugXml =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.mylibrary.AndroidTest" tests="2" failures="0" errors="0" skipped="0" time="0.5">
          <properties>
              <property name="testedVariantName" value="stagingDebug"/>
              <property name="modulePath" value=":mylibrary"/>
              <property name="testSuiteName" value="AndroidTest"/>
          </properties>
          <testcase name="useAppContext" classname="com.example.mylibrary.ExampleInstrumentedTest" time="0.3"/>
          <testcase name="useAppContext" classname="com.example.mylibrary.StagingInstrumentedTest" time="0.2"/>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "staging-report.xml", stagingDebugXml)

    val trialDebugXml =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.mylibrary.AndroidTest" tests="1" failures="0" errors="0" skipped="0" time="0.4">
          <properties>
              <property name="testedVariantName" value="trialDebug"/>
              <property name="modulePath" value=":mylibrary"/>
              <property name="testSuiteName" value="AndroidTest"/>
          </properties>
          <testcase name="useAppContext" classname="com.example.mylibrary.ExampleInstrumentedTest" time="0.4"/>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir2, "trial-report.xml", trialDebugXml)

    val aggregator = createAggregator(files = listOf(inputDir1, inputDir2), projectName = "VariantSpecificProject")
    val report = aggregator.generateReport()

    assertThat(report.variants).containsExactly("stagingDebug", "trialDebug").inOrder()
    val myLibraryModule = report.modules.findOrThrow({ it.name == ":mylibrary" }) { ":mylibrary module not found" }
    val pkg = myLibraryModule.packages.findOrThrow({ it.name == "com.example.mylibrary" }) { "com.example.mylibrary package not found" }

    // Verify ExampleInstrumentedTest (runs on both variants)
    val exampleInstrumentedTest = pkg.classes.findOrThrow({ it.name == "ExampleInstrumentedTest" }) { "ExampleInstrumentedTest not found" }
    val exampleTestCase =
      exampleInstrumentedTest.testCases.findOrThrow({ it.name == "useAppContext" }) { "useAppContext in ExampleInstrumentedTest not found" }

    val exampleSuiteResult = exampleTestCase.targets.first().testSuiteResults.find { it.testSuiteName == "AndroidTest" }
    assertThat(exampleSuiteResult?.variantResults).hasSize(2)
    assertThat(exampleSuiteResult?.variantResults?.get("stagingDebug")?.status).isEqualTo("pass")
    assertThat(exampleSuiteResult?.variantResults?.get("trialDebug")?.status).isEqualTo("pass")

    // Verify StagingInstrumentedTest (runs only on stagingDebug)
    val stagingInstrumentedTest = pkg.classes.findOrThrow({ it.name == "StagingInstrumentedTest" }) { "StagingInstrumentedTest not found" }
    val stagingTestCase =
      stagingInstrumentedTest.testCases.findOrThrow({ it.name == "useAppContext" }) { "useAppContext in StagingInstrumentedTest not found" }

    val stagingSuiteResult = stagingTestCase.targets.first().testSuiteResults.find { it.testSuiteName == "AndroidTest" }
    assertThat(stagingSuiteResult?.variantResults).hasSize(1)
    assertThat(stagingSuiteResult?.variantResults).containsKey("stagingDebug")
    assertThat(stagingSuiteResult?.variantResults?.get("stagingDebug")?.status).isEqualTo("pass")
    assertThat(stagingSuiteResult?.variantResults).doesNotContainKey("trialDebug")
  }

  @Test
  fun testGenerateReport_summaryCalculations() {
    val debugXml =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyClassTest" tests="2" failures="1" errors="0" skipped="0" time="0.2">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testPass" classname="com.example.app.MyClassTest" time="0.1"/>
          <testcase name="testFail" classname="com.example.app.MyClassTest" time="0.1">
              <failure message="assertion failed">stacktrace here</failure>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "debug.xml", debugXml)

    val releaseXml =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyClassTest" tests="2" failures="0" errors="0" skipped="1" time="0.3">
          <properties>
              <property name="testedVariantName" value="release"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testAnotherPass" classname="com.example.app.MyClassTest" time="0.2"/>
          <testcase name="testSkipped" classname="com.example.app.MyClassTest" time="0.1">
              <skipped/>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "release.xml", releaseXml)

    val skippedOnlyXml =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyClassTest" tests="1" failures="0" errors="0" skipped="1" time="0.1">
          <properties>
              <property name="testedVariantName" value="skippedOnly"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testSkippedOnly" classname="com.example.app.MyClassTest" time="0.1">
              <skipped/>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "skippedOnly.xml", skippedOnlyXml)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "SummaryProject")
    val report = aggregator.generateReport()

    val appModule = report.modules.findOrThrow({ it.name == ":app" }) { ":app module not found" }

    // Check Module Summaries
    val unitTestSummary = appModule.testSuiteSummaries.find { it.name == "unitTest" }
    assertThat(unitTestSummary).isNotNull()

    val debugSummary = unitTestSummary?.variantSummaries?.find { it.name == "debug" }!!
    assertThat(debugSummary.total).isEqualTo(2)
    assertThat(debugSummary.passed).isEqualTo(1)
    assertThat(debugSummary.failed).isEqualTo(1)
    assertThat(debugSummary.skipped).isEqualTo(0)
    assertThat(debugSummary.rate).isEqualTo(50.0)

    val releaseSummary = unitTestSummary.variantSummaries.find { it.name == "release" }!!
    assertThat(releaseSummary.total).isEqualTo(2)
    assertThat(releaseSummary.passed).isEqualTo(1)
    assertThat(releaseSummary.failed).isEqualTo(0)
    assertThat(releaseSummary.skipped).isEqualTo(1)
    assertThat(releaseSummary.rate).isEqualTo(100.0)

    val skippedOnlySummary = unitTestSummary.variantSummaries.find { it.name == "skippedOnly" }!!
    assertThat(skippedOnlySummary.total).isEqualTo(1)
    assertThat(skippedOnlySummary.passed).isEqualTo(0)
    assertThat(skippedOnlySummary.failed).isEqualTo(0)
    assertThat(skippedOnlySummary.skipped).isEqualTo(1)
    assertThat(skippedOnlySummary.rate).isEqualTo(0.0)
  }

  @Test
  fun testGenerateReport_deterministicSorting() {
    // Create multiple XML files with names that would be out-of-order if not sorted
    val xmlB =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="variantB"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testExample" classname="com.example.app.MyClassTest" time="0.1"/>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "report-B.xml", xmlB)

    val xmlA =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="variantA"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testExample" classname="com.example.app.MyClassTest" time="0.1"/>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "report-A.xml", xmlA)

    val xmlC =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="variantC"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testExample" classname="com.example.app.MyClassTest" time="0.1"/>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "report-C.xml", xmlC)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "DeterministicProject")
    val report = aggregator.generateReport()

    // Assert that the parsed variants are sorted alphabetically
    assertThat(report.variants).containsExactly("variantA", "variantB", "variantC").inOrder()
  }

  @Test
  fun testGenerateReport_screenshotProperties() {
    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="screenshotTest"/>
          </properties>
          <testcase name="testScreenshot" classname="com.example.app.MyScreenshotTest" time="0.1">
              <properties>
                  <property name="PreviewScreenshot.diffPercent" value="0.08"/>
                  <property name="PreviewScreenshot.previewName" value="Phone"/>
                  <property name="PreviewScreenshot.methodName" value="MessagesScreenPreview"/>
                  <property name="PreviewScreenshot.refImagePath" value="path/to/ref.png"/>
                  <property name="PreviewScreenshot.newImagePath" value="path/to/new.png"/>
                  <property name="PreviewScreenshot.diffImagePath" value="path/to/diff.png"/>
              </properties>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "screenshot-test-report.xml", xmlContent)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "ScreenshotProject")
    val report = aggregator.generateReport()

    val module = report.modules.first()
    val pkg = module.packages.first()
    val clazz = pkg.classes.first()
    val testCase = clazz.testCases.first()

    assertThat(testCase.name).isEqualTo("testScreenshot")

    val suiteResult = testCase.targets.first().testSuiteResults.find { it.testSuiteName == "screenshotTest" }
    val variantResult = suiteResult?.variantResults?.get("debug")

    assertThat(variantResult).isNotNull()
    assertThat(variantResult?.diffPercent).isEqualTo("0.08")
    assertThat(variantResult?.previewName).isEqualTo("Phone")
    assertThat(variantResult?.methodName).isEqualTo("MessagesScreenPreview")
    assertThat(variantResult?.refImagePath).isEqualTo("path/to/ref.png")
    assertThat(variantResult?.newImagePath).isEqualTo("path/to/new.png")
    assertThat(variantResult?.diffImagePath).isEqualTo("path/to/diff.png")
  }

  @Test
  fun testGenerateReport_multipleTargets() {
    val pixel7Xml =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
              <property name="testTarget" value="pixel7"/>
          </properties>
          <testcase name="testExample" classname="com.example.app.MyClassTest" time="0.1"/>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "pixel7-report.xml", pixel7Xml)

    val pixel8Xml =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="1" errors="0" skipped="0" time="0.15">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
              <property name="testTarget" value="pixel8"/>
          </properties>
          <testcase name="testExample" classname="com.example.app.MyClassTest" time="0.15">
              <failure message="assertion failed">stacktrace here</failure>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir2, "pixel8-report.xml", pixel8Xml)

    val aggregator = createAggregator(files = listOf(inputDir1, inputDir2), projectName = "MultiTargetProject")
    val report = aggregator.generateReport()

    assertThat(report.projectName).isEqualTo("MultiTargetProject")
    // Should capture both unique targets
    assertThat(report.targets).containsExactly("pixel7", "pixel8").inOrder()

    val module = report.modules.first()
    val pkg = module.packages.first()
    val clazz = pkg.classes.first()
    val testCase = clazz.testCases.first()

    assertThat(testCase.name).isEqualTo("testExample")
    // There should be two targets under this testcase
    assertThat(testCase.targets).hasSize(2)

    val target7 = testCase.targets.find { it.name == "pixel7" }
    assertThat(target7).isNotNull()
    val suiteResult7 = target7?.testSuiteResults?.find { it.testSuiteName == "unitTest" }
    assertThat(suiteResult7?.variantResults?.get("debug")?.status).isEqualTo("pass")

    val target8 = testCase.targets.find { it.name == "pixel8" }
    assertThat(target8).isNotNull()
    val suiteResult8 = target8?.testSuiteResults?.find { it.testSuiteName == "unitTest" }
    assertThat(suiteResult8?.variantResults?.get("debug")?.status).isEqualTo("fail")

    // Check package level aggregation
    // Both pixel7 (passed) and pixel8 (failed) should be aggregated
    val packageSummary = pkg.testSuiteSummaries.find { it.name == "unitTest" }
    assertThat(packageSummary).isNotNull()
    val debugSummary = packageSummary?.variantSummaries?.find { it.name == "debug" }
    assertThat(debugSummary?.total).isEqualTo(2)
    assertThat(debugSummary?.passed).isEqualTo(1)
    assertThat(debugSummary?.failed).isEqualTo(1)
  }

  @Test
  fun testCalculateRelativeRootDir_nestedModule() {
    val rootDir = File("/repo/project")
    val outputDir = File("/repo/project/nested/designkit/build/reports/tests/screenshotTest")
    val relative = XMLReportAggregator.calculateRelativeRootDir(outputDir, rootDir)
    assertThat(relative).isEqualTo("../../../../../../")
  }

  @Test
  fun testCalculateRelativeRootDir_singleLevelModule() {
    val rootDir = File("/repo/project")
    val outputDir = File("/repo/project/app/build/reports/tests/screenshotTest")
    val relative = XMLReportAggregator.calculateRelativeRootDir(outputDir, rootDir)
    assertThat(relative).isEqualTo("../../../../../")
  }

  @Test
  fun testCalculateRelativeRootDir_rootLevelReport() {
    val rootDir = File("/repo/project")
    val outputDir = File("/repo/project/build/reports/tests/screenshotTest")
    val relative = XMLReportAggregator.calculateRelativeRootDir(outputDir, rootDir)
    assertThat(relative).isEqualTo("../../../../")
  }

  @Test
  fun testCalculateRelativeRootDir_sameDir() {
    val rootDir = File("/repo/project")
    val outputDir = File("/repo/project")
    val relative = XMLReportAggregator.calculateRelativeRootDir(outputDir, rootDir)
    assertThat(relative).isEqualTo("")
  }

  @Test
  fun testCalculateRelativeRootDir_nonExistentPaths() {
    val rootDir = File("/non/existent/root/path")
    val outputDir = File("/non/existent/root/path/module/build/reports")
    val relative = XMLReportAggregator.calculateRelativeRootDir(outputDir, rootDir)
    assertThat(relative).isEqualTo("../../../")
  }

  @Test
  fun testWriteReport_withRootDir_includesRelativeRootDirInDataJs() {
    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":sub:module"/>
              <property name="testSuiteName" value="screenshotTest"/>
          </properties>
          <testcase name="testExample" classname="com.example.app.MyClassTest" time="0.1"/>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "test-report.xml", xmlContent)

    val fakeRootDir = temporaryFolder.newFolder("fake_root")
    val reportOutputDir = File(fakeRootDir, "sub/module/build/reports/tests/screenshotTest").also { it.mkdirs() }

    val aggregator = XMLReportAggregator(files = listOf(inputDir1), projectName = "TestProj", rootDir = fakeRootDir)
    aggregator.writeReport(reportOutputDir)

    val dataJs = File(reportOutputDir, "data.js")
    assertThat(dataJs.exists()).isTrue()
    val dataJsContent = dataJs.readText()
    assertThat(dataJsContent).contains("\"relativeRootDir\":\"../../../../../../\"")

    val generatedReport = aggregator.generateReport(outputDir = reportOutputDir)
    assertThat(generatedReport.relativeRootDir).isEqualTo("../../../../../../")
  }

  @Test
  fun testWriteReport_retainsOriginalRelativeAndAbsolutePathsWithoutCopyingFiles() {
    val srcDir = temporaryFolder.newFolder("src_screenshots")
    val refFile = File(srcDir, "ref.png").also { it.writeText("fake ref content") }
    val newFile = File(srcDir, "new.png").also { it.writeText("fake new content") }
    val diffFile = File(srcDir, "diff.png").also { it.writeText("fake diff content") }

    val relRefPath = "relative/path/to/ref.png"
    val absNewPath = newFile.absolutePath
    val relDiffPath = "relative/diff.png"

    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="1" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="screenshotTest"/>
          </properties>
          <testcase name="testScreenshot" classname="com.example.app.MyScreenshotTest" time="0.1">
              <properties>
                  <property name="PreviewScreenshot.refImagePath" value="$relRefPath"/>
                  <property name="PreviewScreenshot.newImagePath" value="$absNewPath"/>
                  <property name="PreviewScreenshot.diffImagePath" value="$relDiffPath"/>
              </properties>
              <failure message="diff found">diff details</failure>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "screenshot-report.xml", xmlContent)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "ScreenshotProject")
    aggregator.writeReport(outputDir)

    // No files should be copied - aggregator is a pure data aggregator
    val screenshotsDir = File(outputDir, "screenshots")
    assertThat(screenshotsDir.exists()).isFalse()

    // Verify data.js retains original relative and absolute paths
    val dataJs = File(outputDir, "data.js")
    assertThat(dataJs.exists()).isTrue()
    val dataJsContent = dataJs.readText()
    assertThat(dataJsContent).contains("\"refImagePath\":\"$relRefPath\"")
    val escapedAbsNewPath = absNewPath.replace("\\", "\\\\")
    assertThat(dataJsContent).contains("\"newImagePath\":\"$escapedAbsNewPath\"")
    assertThat(dataJsContent).contains("\"diffImagePath\":\"$relDiffPath\"")

    // Verify report data model directly
    val report = aggregator.generateReport(outputDir)
    val variantResult =
      report.modules
        .first()
        .packages
        .first()
        .classes
        .first()
        .testCases
        .first()
        .targets
        .first()
        .testSuiteResults
        .first()
        .variantResults["debug"]
    assertThat(variantResult).isNotNull()
    assertThat(variantResult?.refImagePath).isEqualTo(relRefPath)
    assertThat(variantResult?.newImagePath).isEqualTo(absNewPath)
    assertThat(variantResult?.diffImagePath).isEqualTo(relDiffPath)
  }

  @Test
  fun testWriteReport_handlesMissingAndNonExistentFilesGracefully() {
    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="screenshotTest"/>
          </properties>
          <testcase name="testMissing" classname="com.example.app.MyScreenshotTest" time="0.1">
              <properties>
                  <property name="PreviewScreenshot.refImagePath" value="/non/existent/missing_ref.png"/>
                  <property name="PreviewScreenshot.diffImagePath" value="no diff"/>
              </properties>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "missing-report.xml", xmlContent)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "MissingFileProject")
    // Should not throw or crash
    aggregator.writeReport(outputDir)

    val dataJsContent = File(outputDir, "data.js").readText()
    assertThat(dataJsContent).contains("\"refImagePath\":\"/non/existent/missing_ref.png\"")
    assertThat(dataJsContent).contains("\"diffImagePath\":\"no diff\"")
  }

  @Test
  fun testWriteReport_nestedModuleWithExplicitRootDir() {
    val rootProjectDir = temporaryFolder.newFolder("module_root")
    val submoduleDir = File(rootProjectDir, "feature/submodule").also { it.mkdirs() }
    val submoduleReportDir = File(submoduleDir, "build/reports/tests/screenshotTest").also { it.mkdirs() }
    val submoduleXmlDir = File(submoduleDir, "build/test-results/screenshotTest").also { it.mkdirs() }

    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.submodule.SubmoduleTest" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":feature:submodule"/>
              <property name="testSuiteName" value="screenshotTest"/>
          </properties>
          <testcase name="testPreview" classname="com.example.submodule.SubmoduleScreenshotTest" time="0.1">
              <properties>
                  <property name="PreviewScreenshot.refImagePath" value="feature/submodule/src/test/screenshots/golden_preview.png"/>
              </properties>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(submoduleXmlDir, "submodule-report.xml", xmlContent)

    val aggregator = XMLReportAggregator(files = listOf(submoduleXmlDir), projectName = "MultiModuleProject", rootDir = rootProjectDir)
    aggregator.writeReport(submoduleReportDir)

    val dataJs = File(submoduleReportDir, "data.js")
    assertThat(dataJs.exists()).isTrue()
    val dataJsContent = dataJs.readText()
    assertThat(dataJsContent).contains("\"relativeRootDir\":\"../../../../../../\"")
    assertThat(dataJsContent).contains("\"refImagePath\":\"feature/submodule/src/test/screenshots/golden_preview.png\"")

    // No file copying / disk duplication: no screenshots folder created
    val screenshotsDir = File(submoduleReportDir, "screenshots")
    assertThat(screenshotsDir.exists()).isFalse()
  }

  @Test
  fun testWriteReport_handlesStatusStringsWithoutFileLookup() {
    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.MyTestSuite" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="screenshotTest"/>
          </properties>
          <testcase name="testStatusStrings" classname="com.example.app.MyScreenshotTest" time="0.1">
              <properties>
                  <property name="PreviewScreenshot.diffImagePath" value="images match"/>
                  <property name="PreviewScreenshot.newImagePath" value="none"/>
              </properties>
          </testcase>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "status-report.xml", xmlContent)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "StatusStringProject")
    aggregator.writeReport(outputDir)

    val dataJsContent = File(outputDir, "data.js").readText()
    assertThat(dataJsContent).contains("\"diffImagePath\":\"images match\"")
    assertThat(dataJsContent).contains("\"newImagePath\":\"none\"")
  }

  @Test
  fun testWriteReport_standardUnitTest() {
    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.StandardUnitTest" tests="2" failures="0" errors="0" skipped="0" time="0.5">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testAdd" classname="com.example.app.CalculatorTest" time="0.2"/>
          <testcase name="testSubtract" classname="com.example.app.CalculatorTest" time="0.3"/>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "unit-test-report.xml", xmlContent)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "UnitTestProject")
    aggregator.writeReport(outputDir)

    val dataJs = File(outputDir, "data.js")
    assertThat(dataJs.exists()).isTrue()
    assertThat(File(outputDir, "index.html").exists()).isTrue()
    assertThat(dataJs.readText()).contains("\"testAdd\"")
  }

  @Test
  fun testGenerateReport_parsesDurationPropertiesAndPerTestcaseStreamsWith256KbCap() {
    val attachDir = File(inputDir1, "attachments/1").apply { mkdirs() }
    File(attachDir, "host-log.txt").writeText("trailing attachment past 256 KB stdout cap", Charsets.UTF_8)
    val oversizedOut = "O".repeat(MAX_STREAM_CHARS + 5000) + "\n[[ATTACHMENT|attachments/1/host-log.txt]]\n"
    val normalErr = "Warning line 1\nERROR: failed check\n"

    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.StreamSuite" tests="4" failures="2" errors="0" skipped="0" time="2.15">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testWithStreamsAndProperties" classname="com.example.app.StreamTest" time="1.456">
              <properties>
                  <property name="androidx.report.schemaVersion" value="1"/>
                  <property name="featureFlag" value="enabled"/>
                  <property name="retryCount" value="2"/>
              </properties>
              <failure message="boom">java.lang.AssertionError: boom
                  at com.example.app.StreamTest.testWithStreamsAndProperties(StreamTest.kt:21)</failure>
              <system-out><![CDATA[__OVERSIZED_OUT__]]></system-out>
              <system-err><![CDATA[__NORMAL_ERR__]]></system-err>
          </testcase>
          <testcase name="testPlainFailureDiscardsStreams" classname="com.example.app.StreamTest" time="0.000">
              <failure message="plain">java.lang.AssertionError: plain</failure>
              <system-out><![CDATA[__PLAIN_FAILURE_OUT__]]></system-out>
          </testcase>
          <testcase name="testPlainPassDiscardsStreams" classname="com.example.app.StreamTest" time="0.494">
              <system-out><![CDATA[noisy stdout from passing unit test]]></system-out>
              <system-err><![CDATA[noisy stderr from passing unit test]]></system-err>
          </testcase>
          <testcase name="testPassWithDetailRetainsStreams" classname="com.example.app.StreamTest" time="0.200">
              <properties>
                  <property name="androidx.report.schemaVersion" value="1"/>
                  <property name="androidx.report.suiteType" value="com.example.journey"/>
              </properties>
              <system-out><![CDATA[retained passing suite stdout]]></system-out>
          </testcase>
          <system-out><![CDATA[suite-level output should not attach to testcases]]></system-out>
      </testsuite>
      """
        .trimIndent()
        .replace("__OVERSIZED_OUT__", oversizedOut)
        .replace("__NORMAL_ERR__", normalErr)
        .replace("__PLAIN_FAILURE_OUT__", "stdout of a failing test without report entries\n[[ATTACHMENT|attachments/1/host-log.txt]]\n")
    createXmlReport(inputDir1, "TEST-StreamTest.xml", xmlContent)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "StreamProject")
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    val module = report.modules.single()
    val moduleDebugSummary = module.testSuiteSummaries.find { it.name == "unitTest" }!!.variantSummaries.find { it.name == "debug" }!!
    assertThat(moduleDebugSummary.durationMs).isEqualTo(2150L)

    val testCases = module.packages.single().classes.single().testCases
    val failingCase = testCases.findOrThrow({ it.name == "testWithStreamsAndProperties" }) { "failing test not found" }
    val failingTarget = failingCase.targets.single()
    val failingResult = failingTarget.testSuiteResults.single().variantResults["debug"]!!

    assertThat(failingResult.durationMs).isEqualTo(1456L)
    assertThat(failingResult.adapterId).isEqualTo("default")
    assertThat(failingResult.properties).containsExactly("featureFlag", "enabled", "retryCount", "2")
    val failingDetailId = checkNotNull(failingResult.detailId)

    // Ensure <system-out> following </failure> did not leak into the stack trace
    val stackTrace = failingTarget.commonStackTraces.single().stackTrace
    assertThat(stackTrace).contains("java.lang.AssertionError: boom")
    assertThat(stackTrace).doesNotContain("OOOOO")

    // Verify lazy details/<detailId>.js was written with 256 KB truncated stdout, intact stderr, and extracted trailing attachment
    val lazyDetail = readLazyTestDetailJs(outputDir, failingDetailId)
    assertThat(lazyDetail.systemOut).hasLength(MAX_STREAM_CHARS)
    assertThat(lazyDetail.systemOutTruncated).isTrue()
    assertThat(lazyDetail.systemErr).isEqualTo(normalErr)
    assertThat(lazyDetail.systemErrTruncated).isFalse()
    assertThat(lazyDetail.logs.single().content).isEqualTo("trailing attachment past 256 KB stdout cap")
    assertThat(File(outputDir, "artifacts/$failingDetailId/host-log.txt").readText(Charsets.UTF_8))
      .isEqualTo("trailing attachment past 256 KB stdout cap")

    // Passing unit test without androidx.report.* entries discards <system-out>/<system-err> to save daemon heap.
    val plainPassCase = testCases.findOrThrow({ it.name == "testPlainPassDiscardsStreams" }) { "plain pass test not found" }
    val plainPassResult = plainPassCase.targets.single().testSuiteResults.single().variantResults["debug"]!!
    assertThat(plainPassResult.durationMs).isEqualTo(494L)
    assertThat(plainPassResult.detailId).isNull()
    assertThat(plainPassResult.properties).isNull()

    // Failing test without androidx.report.* entries keeps the classic view: no streams, attachments or detail script.
    val plainFailCase = testCases.findOrThrow({ it.name == "testPlainFailureDiscardsStreams" }) { "plain failure test not found" }
    val plainFailTarget = plainFailCase.targets.single()
    val plainFailResult = plainFailTarget.testSuiteResults.single().variantResults["debug"]!!
    assertThat(plainFailResult.detailId).isNull()
    assertThat(plainFailResult.logMeta).isNull()
    assertThat(plainFailTarget.commonStackTraces.single().stackTrace).isEqualTo("java.lang.AssertionError: plain")

    // Passing suite test with androidx.report.* entries retains <system-out>.
    val detailPassCase = testCases.findOrThrow({ it.name == "testPassWithDetailRetainsStreams" }) { "detail pass test not found" }
    val detailPassResult = detailPassCase.targets.single().testSuiteResults.single().variantResults["debug"]!!
    assertThat(detailPassResult.suiteType).isEqualTo("com.example.journey")
    val detailPassLazy = readLazyTestDetailJs(outputDir, checkNotNull(detailPassResult.detailId))
    assertThat(detailPassLazy.systemOut).isEqualTo("retained passing suite stdout")
    assertThat(detailPassLazy.systemOutTruncated).isFalse()
  }

  @Test
  fun testProcessXmlStream_failureElementsKeepExistingStatusAndStackTraceRules() {
    createXmlReport(
      inputDir1,
      "TEST-FailureRulesTest.xml",
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.FailureRulesTest" tests="3" failures="3" errors="0" skipped="0" time="0.3">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testSkippedAfterFailure" classname="com.example.app.FailureRulesTest" time="0.1">
              <failure message="first">java.lang.AssertionError: before skip</failure>
              <skipped/>
          </testcase>
          <testcase name="testTwoFailures" classname="com.example.app.FailureRulesTest" time="0.1">
              <failure message="a">java.lang.AssertionError: first failure</failure>
              <failure message="b">java.lang.AssertionError: second failure</failure>
          </testcase>
          <testcase name="testMessageOnlyFailure" classname="com.example.app.FailureRulesTest" time="0.1">
              <failure message="no body"/>
          </testcase>
      </testsuite>
      """
        .trimIndent(),
    )

    val testCases =
      createAggregator(files = listOf(inputDir1), projectName = "FailureRulesProject")
        .generateReport()
        .modules
        .single()
        .packages
        .single()
        .classes
        .single()
        .testCases
    fun target(name: String) = testCases.first { it.name == name }.targets.single()

    val skipped = target("testSkippedAfterFailure")
    assertThat(skipped.testSuiteResults.single().variantResults["debug"]?.status).isEqualTo("skipped")
    assertThat(skipped.commonStackTraces.single().stackTrace).isEqualTo("java.lang.AssertionError: before skip")

    assertThat(target("testTwoFailures").commonStackTraces.single().stackTrace).isEqualTo("java.lang.AssertionError: second failure")

    val messageOnly = target("testMessageOnlyFailure")
    assertThat(messageOnly.testSuiteResults.single().variantResults["debug"]?.status).isEqualTo("fail")
    assertThat(messageOnly.commonStackTraces).isEmpty()
  }

  @Test
  fun testGenerateReport_keepsSuiteAndVariantResultsInInputOrder() {
    fun xml(suite: String, variant: String) =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.OrderTest" tests="1" failures="0" errors="0" skipped="0" time="0.1">
          <properties>
              <property name="testedVariantName" value="$variant"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="$suite"/>
          </properties>
          <testcase name="testOrder" classname="com.example.app.OrderTest" time="0.1"/>
      </testsuite>
      """
        .trimIndent()
    createXmlReport(inputDir1, "TEST-1.xml", xml(suite = "zSuite", variant = "release"))
    createXmlReport(inputDir1, "TEST-2.xml", xml(suite = "zSuite", variant = "debug"))
    createXmlReport(inputDir1, "TEST-3.xml", xml(suite = "aSuite", variant = "debug"))

    val target =
      createAggregator(files = listOf(inputDir1), projectName = "OrderProject")
        .generateReport()
        .modules
        .single()
        .packages
        .single()
        .classes
        .single()
        .testCases
        .single()
        .targets
        .single()

    assertThat(target.testSuiteResults.map { it.testSuiteName }).containsExactly("zSuite", "aSuite").inOrder()
    assertThat(target.testSuiteResults.first().variantResults.keys).containsExactly("release", "debug").inOrder()
  }

  @Test
  fun testParseDurationMs_acceptsOnlyPlainDecimalSeconds() {
    assertThat(XMLReportAggregator.parseDurationMs("0.271")).isEqualTo(271L)
    assertThat(XMLReportAggregator.parseDurationMs(" 12 ")).isEqualTo(12_000L)
    // A comma can be a decimal or a thousands separator, so the duration is left unknown.
    assertThat(XMLReportAggregator.parseDurationMs("0,123")).isNull()
    assertThat(XMLReportAggregator.parseDurationMs("1,234.5")).isNull()
    assertThat(XMLReportAggregator.parseDurationMs("-1")).isNull()
    assertThat(XMLReportAggregator.parseDurationMs("NaN")).isNull()
    assertThat(XMLReportAggregator.parseDurationMs("")).isNull()
    assertThat(XMLReportAggregator.parseDurationMs(null)).isNull()
  }

  @Test
  fun testProcessXmlStream_truncatedFileKeepsTestCasesParsedBeforeTheError() {
    createXmlReport(
      inputDir1,
      "TEST-TruncatedTest.xml",
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.TruncatedTest" tests="3" failures="1" errors="0" skipped="0" time="0.3">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testPassesBeforeTruncation" classname="com.example.app.TruncatedTest" time="0.1"/>
          <testcase name="testFailsBeforeTruncation" classname="com.example.app.TruncatedTest" time="0.1">
              <failure message="boom">java.lang.AssertionError: boom</failure>
          </testcase>
          <testcase name="testCutOff" classname="com.example.app.TruncatedTest" time="0.1">
              <system-out><![CDATA[partial output
      """
        .trimIndent(),
    )

    val report = createAggregator(files = listOf(inputDir1), projectName = "TruncatedProject").generateReport()

    val testCases = report.modules.single().packages.single().classes.single().testCases
    assertThat(testCases.map { it.name }).containsExactly("testFailsBeforeTruncation", "testPassesBeforeTruncation")
    val failing = testCases.first { it.name == "testFailsBeforeTruncation" }.targets.single()
    assertThat(failing.testSuiteResults.single().variantResults["debug"]?.status).isEqualTo("fail")
    assertThat(failing.commonStackTraces.single().stackTrace).isEqualTo("java.lang.AssertionError: boom")
  }

  @Test
  fun testProcessXmlForAggregation_ignoresNonJunitXmlFilesGracefully() {
    createXmlReport(
      inputDir1,
      "backup_rules.xml",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <full-backup-content>
          <exclude domain="sharedpref" path="secret.xml"/>
      </full-backup-content>
      """
        .trimIndent(),
    )
    createXmlReport(
      inputDir1,
      "data_extraction_rules.xml",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <data-extraction-rules>
          <device-transfer>
              <exclude domain="sharedpref" path="user_session.xml"/>
          </device-transfer>
      </data-extraction-rules>
      """
        .trimIndent(),
    )
    createXmlReport(
      inputDir1,
      "TEST-UnitTest.xml",
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.UnitTest" tests="1" failures="0" errors="0" skipped="0" time="0.05">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testOnlyRealJunitXmlAggregated" classname="com.example.app.UnitTest" time="0.05"/>
      </testsuite>
      """
        .trimIndent(),
    )

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "MixedXmlProject")
    val report = aggregator.generateReport()

    assertThat(aggregator.getTestCount()).isEqualTo(1)
    assertThat(report.modules).hasSize(1)
    val tc = report.modules.single().packages.single().classes.single().testCases.single()
    assertThat(tc.name).isEqualTo("testOnlyRealJunitXmlAggregated")
  }

  @Test
  fun testGenerateReport_streamTruncationPreservesSurrogatePairsAndPreventsChunkSplicing() {
    // Supplementary Plane-1 letter U+10400 (encoded as 2 UTF-16 surrogate code units).
    val supplementary = String(Character.toChars(0x10400))
    assertThat(supplementary).hasLength(2)

    val boundaryPayload = "A".repeat(MAX_STREAM_CHARS - 1) + supplementary
    val xmlContent =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.app.SurrogateStreamSuite" tests="1" failures="1" errors="0" skipped="0" time="0.2">
          <properties>
              <property name="testedVariantName" value="debug"/>
              <property name="modulePath" value=":app"/>
              <property name="testSuiteName" value="unitTest"/>
          </properties>
          <testcase name="testSurrogateBoundary" classname="com.example.app.SurrogateStreamTest" time="0.2">
              <properties>
                  <property name="androidx.report.schemaVersion" value="1"/>
              </properties>
              <failure message="boom">boom</failure>
              <system-out><![CDATA[__BOUNDARY_OUT__]]><![CDATA[TRAILING_OUT_ASCII]]></system-out>
              <system-err><![CDATA[__BOUNDARY_ERR__]]><![CDATA[TRAILING_ERR_ASCII]]></system-err>
          </testcase>
      </testsuite>
      """
        .trimIndent()
        .replace("__BOUNDARY_OUT__", boundaryPayload)
        .replace("__BOUNDARY_ERR__", boundaryPayload)
    createXmlReport(inputDir1, "TEST-SurrogateStreamTest.xml", xmlContent)

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "SurrogateStreamProject")
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    val result =
      checkNotNull(
        report.modules
          .single()
          .packages
          .single()
          .classes
          .single()
          .testCases
          .single()
          .targets
          .single()
          .testSuiteResults
          .single()
          .variantResults["debug"]
      )
    val detailId = checkNotNull(result.detailId)
    val lazyDetail = readLazyTestDetailJs(outputDir, detailId)

    val capturedOut = checkNotNull(lazyDetail.systemOut)
    val capturedErr = checkNotNull(lazyDetail.systemErr)
    assertThat(lazyDetail.systemOutTruncated).isTrue()
    assertThat(lazyDetail.systemErrTruncated).isTrue()
    assertThat(capturedOut).hasLength(MAX_STREAM_CHARS - 1)
    assertThat(capturedErr).hasLength(MAX_STREAM_CHARS - 1)
    assertThat(capturedOut).doesNotContain("TRAILING_OUT_ASCII")
    assertThat(capturedErr).doesNotContain("TRAILING_ERR_ASCII")
    assertNoUnpairedSurrogates(capturedOut)
    assertNoUnpairedSurrogates(capturedErr)

    // Verify direct multi-chunk appendCapped and SystemOutStreamCollector edge cases across surrogate boundaries.
    val singleChunkBuffer = StringBuilder("B".repeat(9))
    assertThat(appendCapped(singleChunkBuffer, supplementary, maxChars = 10)).isTrue()
    assertThat(singleChunkBuffer.toString()).isEqualTo("B".repeat(9))

    val splitStraddleBuffer = StringBuilder()
    assertThat(appendCapped(splitStraddleBuffer, "C".repeat(9), maxChars = 10)).isFalse()
    assertThat(appendCapped(splitStraddleBuffer, supplementary.substring(0, 1), maxChars = 10)).isTrue()
    assertThat(splitStraddleBuffer.toString()).isEqualTo("C".repeat(9))
    assertNoUnpairedSurrogates(splitStraddleBuffer.toString())

    val splitFittingBuffer = StringBuilder()
    assertThat(
        appendCapped(
          splitFittingBuffer,
          "D".repeat(8) + supplementary.substring(0, 1),
          maxChars = 10,
        )
      )
      .isFalse()
    assertThat(
        appendCapped(
          splitFittingBuffer,
          supplementary.substring(1, 2) + "TRAILING",
          maxChars = 10,
        )
      )
      .isTrue()
    assertThat(splitFittingBuffer.toString()).isEqualTo("D".repeat(8) + supplementary)
    assertNoUnpairedSurrogates(splitFittingBuffer.toString())

    val overflowCollector = SystemOutStreamCollector(maxChars = 8193)
    overflowCollector.append("E".repeat(8192) + supplementary.substring(0, 1))
    overflowCollector.append(supplementary.substring(1, 2) + "\n[[ATTACHMENT|attachments/1/host-log.txt]]\n")
    val overflowOut = checkNotNull(overflowCollector.finish())
    assertThat(overflowCollector.truncated).isTrue()
    assertThat(overflowOut).isEqualTo("E".repeat(8192))
    assertNoUnpairedSurrogates(overflowOut)
    assertThat(overflowCollector.attachments).containsExactly("attachments/1/host-log.txt")
  }

  private fun assertNoUnpairedSurrogates(value: String) {
    var i = 0
    while (i < value.length) {
      val ch = value[i]
      if (ch.isHighSurrogate()) {
        assertThat(i + 1 < value.length && value[i + 1].isLowSurrogate()).isTrue()
        i += 2
      } else {
        assertThat(ch.isLowSurrogate()).isFalse()
        i++
      }
    }
  }

  @Test
  fun testWriteReport_mergedDirectoryResolvesHashedAttachmentsWithoutCollision() {
    val hash1 = "1111aaaa"
    val hash2 = "2222bbbb"

    val hashedAttachDir1a = File(inputDir1, "attachments/$hash1/1").apply { mkdirs() }
    File(hashedAttachDir1a, "host-log.txt").writeText("host log from debug targetA", Charsets.UTF_8)
    val hashedAttachDir1b = File(inputDir1, "attachments/$hash1/2").apply { mkdirs() }
    File(hashedAttachDir1b, "snapshot.backup").writeBytes(byteArrayOf(0x11, 0x22))

    val hashedAttachDir2a = File(inputDir1, "attachments/$hash2/1").apply { mkdirs() }
    File(hashedAttachDir2a, "host-log.txt").writeText("host log from release targetB", Charsets.UTF_8)
    val hashedAttachDir2b = File(inputDir1, "attachments/$hash2/2").apply { mkdirs() }
    File(hashedAttachDir2b, "snapshot.backup").writeBytes(byteArrayOf(0x33, 0x44))

    createXmlReport(
      inputDir1,
      "TEST-BackupTest_$hash1.xml",
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<testsuite name="com.example.app.BackupTest" tests="1" failures="0" errors="0" skipped="0" time="1.1">
      |    <properties>
      |        <property name="modulePath" value=":app"/>
      |        <property name="testedVariantName" value="debug"/>
      |        <property name="testSuiteName" value="backupTest"/>
      |        <property name="testTarget" value="targetA"/>
      |        <property name="applicationId" value="com.example.app"/>
      |    </properties>
      |    <testcase name="testSessionRestored" classname="com.example.app.BackupTest" time="1.1">
      |        <properties>
      |            <property name="androidx.report.schemaVersion" value="1"/>
      |            <property name="androidx.report.suiteType" value="androidx.test.backup"/>
      |            <property name="androidx.report.attr.transport" value="LOCAL"/>
      |            <property name="androidx.report.attr.transport.filterable" value="true"/>
      |        </properties>
      |        <system-out><![CDATA[
      |[[ATTACHMENT|attachments/$hash1/1/host-log.txt]]
      |[[ATTACHMENT|attachments/$hash1/2/snapshot.backup]]
      |]]></system-out>
      |    </testcase>
      |</testsuite>
      """
        .trimMargin(),
    )
    createXmlReport(
      inputDir1,
      "TEST-BackupTest_$hash2.xml",
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<testsuite name="com.example.app.BackupTest" tests="1" failures="0" errors="0" skipped="0" time="1.2">
      |    <properties>
      |        <property name="modulePath" value=":app"/>
      |        <property name="testedVariantName" value="release"/>
      |        <property name="testSuiteName" value="backupTest"/>
      |        <property name="testTarget" value="targetB"/>
      |        <property name="applicationId" value="com.example.app"/>
      |    </properties>
      |    <testcase name="testSessionRestored" classname="com.example.app.BackupTest" time="1.2">
      |        <properties>
      |            <property name="androidx.report.schemaVersion" value="1"/>
      |            <property name="androidx.report.suiteType" value="androidx.test.backup"/>
      |            <property name="androidx.report.attr.transport" value="CLOUD_ENCRYPTED"/>
      |            <property name="androidx.report.attr.transport.filterable" value="true"/>
      |        </properties>
      |        <system-out><![CDATA[
      |[[ATTACHMENT|attachments/$hash2/1/host-log.txt]]
      |[[ATTACHMENT|attachments/$hash2/2/snapshot.backup]]
      |]]></system-out>
      |    </testcase>
      |</testsuite>
      """
        .trimMargin(),
    )

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "MergedAttachmentProject")
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    assertThat(report.suiteEnvironments).hasSize(2)
    val envByTarget = report.suiteEnvironments.associateBy { it.targetName }
    val envDebug = checkNotNull(envByTarget["targetA"])
    val envRelease = checkNotNull(envByTarget["targetB"])

    val classes = report.modules.single().packages.single().classes
    val backupClass = classes.findOrThrow({ it.name == "BackupTest" }) { "BackupTest not found" }
    val targets = backupClass.testCases.single().targets.associateBy { it.name }

    val debugResult = checkNotNull(targets["targetA"]?.testSuiteResults?.single()?.variantResults?.get("debug"))
    val releaseResult = checkNotNull(targets["targetB"]?.testSuiteResults?.single()?.variantResults?.get("release"))

    assertThat(debugResult.envId).isEqualTo(envDebug.envId)
    assertThat(releaseResult.envId).isEqualTo(envRelease.envId)
    assertThat(debugResult.summaryAttributes).containsEntry("transport", SummaryAttributeValue("LOCAL", "Local", "Transport"))
    assertThat(releaseResult.summaryAttributes)
      .containsEntry("transport", SummaryAttributeValue("CLOUD_ENCRYPTED", "Cloud (encrypted)", "Transport"))

    val debugDetailId = checkNotNull(debugResult.detailId)
    val releaseDetailId = checkNotNull(releaseResult.detailId)
    assertThat(debugDetailId).isEqualTo("t_0001")
    assertThat(releaseDetailId).isEqualTo("t_0002")

    val gson = Gson()
    val debugLazy = readLazyTestDetailJs(outputDir, debugDetailId, gson)
    val releaseLazy = readLazyTestDetailJs(outputDir, releaseDetailId, gson)
    assertThat(debugLazy.logs.single().content).isEqualTo("host log from debug targetA")
    assertThat(releaseLazy.logs.single().content).isEqualTo("host log from release targetB")
    assertThat(File(outputDir, "artifacts/$debugDetailId/host-log.txt").readText(Charsets.UTF_8)).isEqualTo("host log from debug targetA")
    assertThat(File(outputDir, "artifacts/$releaseDetailId/host-log.txt").readText(Charsets.UTF_8))
      .isEqualTo("host log from release targetB")
    assertThat(File(outputDir, "artifacts/$debugDetailId/snapshot.backup").readBytes()).isEqualTo(byteArrayOf(0x11, 0x22))
    assertThat(File(outputDir, "artifacts/$releaseDetailId/snapshot.backup").readBytes()).isEqualTo(byteArrayOf(0x33, 0x44))
  }

  @Test
  fun testBeforeAllClassFailureInheritsSuiteLevelReportEntriesOnlyOntoSyntheticClassTestcase() {
    val attachDir = File(inputDir1, "attachments/a1b2c3d4/1").apply { mkdirs() }
    File(attachDir, "host-log.txt").writeText("BeforeAll setup failed on device", Charsets.UTF_8)

    createXmlReport(
      inputDir1,
      "TEST-SharedSessionTest_a1b2c3d4.xml",
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<testsuite name="com.example.app.SharedSessionTest" tests="2" failures="1" errors="1" skipped="0" time="0.6">
      |    <properties>
      |        <property name="modulePath" value=":app"/>
      |        <property name="testedVariantName" value="debug"/>
      |        <property name="testSuiteName" value="backupTest"/>
      |        <property name="testTarget" value="default"/>
      |        <property name="androidx.report.schemaVersion" value="1"/>
      |        <property name="androidx.report.suiteType" value="androidx.test.backup"/>
      |        <property name="androidx.report.attr.transport" value="CLOUD_ENCRYPTED"/>
      |        <property name="androidx.report.attr.transport.displayValue" value="Cloud (encrypted)"/>
      |        <property name="androidx.report.attr.transport.filterable" value="true"/>
      |        <property name="androidx.report.callout.kind" value="BACKUP_ERROR"/>
      |        <property name="androidx.report.callout.failedStep" value="2"/>
      |        <property name="androidx.report.callout.title" value="Backup failed at step 2"/>
      |        <property name="androidx.report.callout.message" value="Transport rejected backup"/>
      |        <property name="androidx.report.step.1.name" value="Setup"/>
      |        <property name="androidx.report.step.1.on" value="device"/>
      |        <property name="androidx.report.step.1.shared" value="true"/>
      |        <property name="androidx.report.step.1.durationMs" value="200"/>
      |        <property name="androidx.report.step.1.status" value="PASSED"/>
      |        <property name="androidx.report.step.2.name" value="Backup"/>
      |        <property name="androidx.report.step.2.on" value="device"/>
      |        <property name="androidx.report.step.2.shared" value="true"/>
      |        <property name="androidx.report.step.2.durationMs" value="300"/>
      |        <property name="androidx.report.step.2.status" value="FAILED"/>
      |    </properties>
      |    <testcase name="testFailedBeforeAcquiringController" classname="com.example.app.SharedSessionTest" time="0.1">
      |        <failure message="precondition failed">java.lang.AssertionError: precondition failed</failure>
      |    </testcase>
      |    <testcase name="initializationError" classname="com.example.app.SharedSessionTest" time="0.5">
      |        <error message="Transport rejected backup">androidx.test.backup.host.BackupRestoreException: Transport rejected backup</error>
      |    </testcase>
      |    <system-out><![CDATA[
      |[[ATTACHMENT|attachments/a1b2c3d4/1/host-log.txt]]
      |]]></system-out>
      |</testsuite>
      """
        .trimMargin(),
    )

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "BeforeAllFailProject")
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    val clazz = report.modules.single().packages.single().classes.single()
    val initErrorCase = clazz.testCases.first { it.name == "initializationError" }
    val initVariantResult = checkNotNull(initErrorCase.targets.single().testSuiteResults.single().variantResults["debug"])
    assertThat(initVariantResult.adapterId).isEqualTo("backupRestore")
    assertThat(initVariantResult.callout?.failedStep).isEqualTo(2)
    assertThat(initVariantResult.steps).hasSize(2)
    val detailId = checkNotNull(initVariantResult.detailId)
    assertThat(File(outputDir, "artifacts/$detailId/host-log.txt").readText(Charsets.UTF_8)).isEqualTo("BeforeAll setup failed on device")

    // Unrelated normal failing method in the same <testsuite> must NOT inherit suite-level androidx.report.* entries.
    val unrelatedCase = clazz.testCases.first { it.name == "testFailedBeforeAcquiringController" }
    val unrelatedResult = checkNotNull(unrelatedCase.targets.single().testSuiteResults.single().variantResults["debug"])
    assertThat(unrelatedResult.callout).isNull()
    assertThat(unrelatedResult.steps).isNull()
    assertThat(unrelatedResult.detailId).isNull()
  }

  @Test
  fun testCappedLogHelpers_utf8BoundaryAndStrayContinuationBytes() {
    val logger = com.android.utils.NullLogger.getLogger()

    val newlinePlusCont = temporaryFolder.newFile("nl_cont.log")
    newlinePlusCont.writeBytes(byteArrayOf('\n'.code.toByte(), 0x80.toByte()))
    assertThat(XMLReportAggregator.readLogFileCapped(newlinePlusCont, 1, logger)).isEqualTo("\n" to true)
    assertThat(XMLReportAggregator.countLogLinesCapped(newlinePlusCont, 1, logger)).isEqualTo(1)
    assertThat(XMLReportAggregator.readLogFileCapped(newlinePlusCont, 0, logger)).isEqualTo("" to true)
    assertThat(XMLReportAggregator.readLogFileCapped(newlinePlusCont, -1, logger)).isEqualTo("" to true)
    assertThat(XMLReportAggregator.countLogLinesCapped(newlinePlusCont, -1, logger)).isEqualTo(0)

    // Complete 2-byte UTF-8 "é" (0xC3 0xA9) followed by a stray 0x80 continuation byte.
    val completeUtf8PlusCont = temporaryFolder.newFile("utf8_cont.log")
    completeUtf8PlusCont.writeBytes(byteArrayOf(0xC3.toByte(), 0xA9.toByte(), 0x80.toByte()))
    assertThat(XMLReportAggregator.readLogFileCapped(completeUtf8PlusCont, 2, logger)).isEqualTo("é" to true)
    assertThat(XMLReportAggregator.countLogLinesCapped(completeUtf8PlusCont, 2, logger)).isEqualTo(1)

    // 3-byte UTF-8 "€" (0xE2 0x82 0xAC) split mid-sequence at maxBytes = 3 after "a\n".
    val splitMultibyte = temporaryFolder.newFile("split_utf8.log")
    splitMultibyte.writeBytes("a\n€".toByteArray(Charsets.UTF_8))
    assertThat(XMLReportAggregator.readLogFileCapped(splitMultibyte, 3, logger)).isEqualTo("a\n" to true)
    assertThat(XMLReportAggregator.countLogLinesCapped(splitMultibyte, 3, logger)).isEqualTo(1)
  }

  @Test
  fun testProcessXmlStream_supportsTestSuitesRootPropertiesAndNestedTestSuites() {
    createXmlReport(
      inputDir1,
      "TEST-RootPropsSuite.xml",
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<testsuites>
      |    <properties>
      |        <property name="modulePath" value=":app"/>
      |        <property name="testedVariantName" value="debug"/>
      |        <property name="testSuiteName" value="unitTest"/>
      |        <property name="testTarget" value="host"/>
      |        <property name="applicationId" value="com.example.rootprops"/>
      |    </properties>
      |    <testsuite name="com.example.app.FirstSuiteTest" tests="1" failures="0" errors="0" skipped="0" time="0.2">
      |        <testcase name="firstPass" classname="com.example.app.FirstSuiteTest" time="0.2"/>
      |    </testsuite>
      |    <testsuite name="com.example.app.SecondSuiteTest" tests="1" failures="1" errors="0" skipped="0" time="0.3">
      |        <testcase name="secondFail" classname="com.example.app.SecondSuiteTest" time="0.3">
      |            <failure message="boom">AssertionError: boom</failure>
      |        </testcase>
      |    </testsuite>
      |</testsuites>
      """
        .trimMargin(),
    )

    createXmlReport(
      inputDir1,
      "TEST-NestedSuite.xml",
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<testsuite name="com.example.app.HierarchicalTest" tests="3" failures="0" errors="0" skipped="0" time="0.6">
      |    <properties>
      |        <property name="modulePath" value=":app"/>
      |        <property name="testedVariantName" value="release"/>
      |        <property name="testSuiteName" value="unitTest"/>
      |        <property name="testTarget" value="host"/>
      |    </properties>
      |    <testcase name="outerBeforeNested" classname="com.example.app.HierarchicalTest" time="0.1"/>
      |    <testsuite name="com.example.app.HierarchicalTest${'$'}NestedGroup" tests="1" failures="0" errors="0" skipped="0" time="0.2">
      |        <testcase name="innerNestedCase" classname="com.example.app.HierarchicalTest" time="0.2"/>
      |    </testsuite>
      |    <testcase name="outerAfterNested" classname="com.example.app.HierarchicalTest" time="0.3"/>
      |</testsuite>
      """
        .trimMargin(),
    )

    val aggregator = createAggregator(files = listOf(inputDir1), projectName = "HierarchicalXmlProject")
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    assertThat(report.variants).containsExactly("debug", "release")
    assertThat(aggregator.getTestCount()).isEqualTo(5)
    assertThat(aggregator.getFailedTestCount()).isEqualTo(1)

    val classesByName = report.modules.single().packages.single().classes.associateBy { it.name }
    assertThat(classesByName.keys).containsExactly("FirstSuiteTest", "SecondSuiteTest", "HierarchicalTest")
    val hierarchicalCases = checkNotNull(classesByName["HierarchicalTest"]).testCases.map { it.name }
    assertThat(hierarchicalCases).containsExactly("outerBeforeNested", "innerNestedCase", "outerAfterNested")
  }
}
