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

  @Before
  fun setUp() {
    outputDir = temporaryFolder.newFolder("output")
    inputDir1 = temporaryFolder.newFolder("input1")
    inputDir2 = temporaryFolder.newFolder("input2")
  }

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

    val aggregator = XMLReportAggregator(files = listOf(inputDir1), projectName = "MyProject")
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

    val aggregator = XMLReportAggregator(files = listOf(inputDir1, inputDir2), projectName = "MyMultiVariantProject")
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

    val aggregator = XMLReportAggregator(files = listOf(inputDir1), projectName = "MyProject")
    aggregator.writeReport(outputDir)

    assertThat(aggregator.getTestCount()).isEqualTo(1)
    assertThat(File(outputDir, "data.js").exists()).isTrue()
    assertThat(File(outputDir, "index.html").exists()).isTrue()
    assertThat(File(outputDir, "script.js").exists()).isTrue()
    assertThat(File(outputDir, "styles.css").exists()).isTrue()

    val dataJsContent = File(outputDir, "data.js").readText()
    assertThat(dataJsContent).contains("const TEST_DATA_SOURCE = {")
    assertThat(dataJsContent).contains("\"projectName\":\"MyProject\"")
    assertThat(dataJsContent).contains("\"timestamp\"")
    assertThat(dataJsContent).contains("debug")
    assertThat(dataJsContent).contains("testExample")
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

    val aggregator = XMLReportAggregator(files = listOf(inputDir1), projectName = "FailureProject")
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

    val aggregator = XMLReportAggregator(files = listOf(nonExistentDir), projectName = "ProjectWithMissingFile")
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

    val aggregator = XMLReportAggregator(files = listOf(inputDir1, inputDir2), projectName = "VariantSpecificProject")
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

    val aggregator = XMLReportAggregator(files = listOf(inputDir1), projectName = "SummaryProject")
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

    val aggregator = XMLReportAggregator(files = listOf(inputDir1), projectName = "DeterministicProject")
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

    val aggregator = XMLReportAggregator(files = listOf(inputDir1), projectName = "ScreenshotProject")
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

    val aggregator = XMLReportAggregator(files = listOf(inputDir1, inputDir2), projectName = "MultiTargetProject")
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
}
