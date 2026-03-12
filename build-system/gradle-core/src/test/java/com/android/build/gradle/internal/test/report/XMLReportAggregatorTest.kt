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
    assertThat(report.modules).hasSize(1)

    val module = report.modules.first()
    assertThat(module.name).isEqualTo(":app")
    assertThat(module.testSuites).hasSize(1)

    val testSuite = module.testSuites.first()
    assertThat(testSuite.name).isEqualTo("unitTest")
    assertThat(testSuite.packages).hasSize(1)

    val pkg = testSuite.packages.first()
    assertThat(pkg.name).isEqualTo("com.example.app")
    assertThat(pkg.classes).hasSize(1)

    val clazz = pkg.classes.first()
    assertThat(clazz.name).isEqualTo("MyClassTest")
    assertThat(clazz.testCases).hasSize(1)

    val testCase = clazz.testCases.first()
    assertThat(testCase.name).isEqualTo("testExample")
    assertThat(testCase.results).hasSize(1)
    assertThat(testCase.results["debug"]?.status).isEqualTo("pass")
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
    assertThat(report.variants).containsExactly("debug", "release").inOrder()
    assertThat(report.modules).hasSize(1)

    val appModule = report.modules.first()
    assertThat(appModule.name).isEqualTo(":app")
    assertThat(appModule.testSuites).hasSize(2)

    val unitTestSuite = appModule.testSuites.findOrThrow({ it.name == "unitTest" }) { "unitTest not found" }
    assertThat(unitTestSuite.packages).hasSize(1)
    val unitTestPkg = unitTestSuite.packages.first()
    assertThat(unitTestPkg.name).isEqualTo("com.example.app")
    assertThat(unitTestPkg.classes).hasSize(1)
    val unitTestClass = unitTestPkg.classes.first()
    assertThat(unitTestClass.name).isEqualTo("MyClassTest")
    assertThat(unitTestClass.testCases).hasSize(2)
    assertThat(unitTestClass.testCases.find { it.name == "testPass" }?.results["debug"]?.status).isEqualTo("pass")
    assertThat(unitTestClass.testCases.find { it.name == "testFail" }?.results["debug"]?.status).isEqualTo("fail")
    assertThat(unitTestClass.testCases.find { it.name == "testFail" }?.results["debug"]?.stackTrace).contains("stacktrace here")

    val otherTestSuite = appModule.testSuites.findOrThrow({ it.name == "otherTestSuite" }) { "otherTestSuite not found" }
    assertThat(otherTestSuite.packages).hasSize(1)
    val otherTestPkg = otherTestSuite.packages.first()
    assertThat(otherTestPkg.name).isEqualTo("com.example.app")
    assertThat(otherTestPkg.classes).hasSize(1)
    val otherTestClass = otherTestPkg.classes.first()
    assertThat(otherTestClass.name).isEqualTo("MyOtherClassTest")
    assertThat(otherTestClass.testCases).hasSize(2)
    assertThat(otherTestClass.testCases.find { it.name == "testAnotherPass" }?.results["release"]?.status).isEqualTo("pass")
    assertThat(otherTestClass.testCases.find { it.name == "testSkipped" }?.results["release"]?.status).isEqualTo("skipped")
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

    assertThat(File(outputDir, "data.js").exists()).isTrue()
    assertThat(File(outputDir, "index.html").exists()).isTrue()
    assertThat(File(outputDir, "script.js").exists()).isTrue()
    assertThat(File(outputDir, "styles.css").exists()).isTrue()

    val dataJsContent = File(outputDir, "data.js").readText()
    assertThat(dataJsContent).contains("const TEST_DATA_SOURCE = {")
    assertThat(dataJsContent).contains("\"projectName\": \"MyProject\"")
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
    val testSuite = module.testSuites.findOrThrow({ it.name == "failedUnitTest" }) { "failedUnitTest not found" }
    val pkg = testSuite.packages.findOrThrow({ it.name == "com.example.app" }) { "com.example.app package not found" }
    val clazz = pkg.classes.findOrThrow({ it.name == "MyFailedClassTest" }) { "MyFailedClassTest not found" }
    val testCase = clazz.testCases.findOrThrow({ it.name == "testFailure" }) { "testFailure testcase not found" }

    assertThat(testCase.name).isEqualTo("testFailure")
    assertThat(testCase.results["debug"]?.status).isEqualTo("fail")
    assertThat(testCase.results["debug"]?.stackTrace).contains("java.lang.RuntimeException: This is a test exception")
    assertThat(testCase.results["debug"]?.stackTrace).contains("at com.example.app.MyFailedClassTest.testFailure(MyFailedClassTest.kt:10)")
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
    val androidTestSuite = myLibraryModule.testSuites.findOrThrow({ it.name == "AndroidTest" }) { "AndroidTest suite not found" }
    val pkg = androidTestSuite.packages.findOrThrow({ it.name == "com.example.mylibrary" }) { "com.example.mylibrary package not found" }

    // Verify ExampleInstrumentedTest (runs on both variants)
    val exampleInstrumentedTest = pkg.classes.findOrThrow({ it.name == "ExampleInstrumentedTest" }) { "ExampleInstrumentedTest not found" }
    val exampleTestCase =
      exampleInstrumentedTest.testCases.findOrThrow({ it.name == "useAppContext" }) { "useAppContext in ExampleInstrumentedTest not found" }
    assertThat(exampleTestCase.results).hasSize(2)
    assertThat(exampleTestCase.results["stagingDebug"]?.status).isEqualTo("pass")
    assertThat(exampleTestCase.results["trialDebug"]?.status).isEqualTo("pass")

    // Verify StagingInstrumentedTest (runs only on stagingDebug)
    val stagingInstrumentedTest = pkg.classes.findOrThrow({ it.name == "StagingInstrumentedTest" }) { "StagingInstrumentedTest not found" }
    val stagingTestCase =
      stagingInstrumentedTest.testCases.findOrThrow({ it.name == "useAppContext" }) { "useAppContext in StagingInstrumentedTest not found" }
    assertThat(stagingTestCase.results).hasSize(1)
    assertThat(stagingTestCase.results).containsKey("stagingDebug")
    assertThat(stagingTestCase.results["stagingDebug"]?.status).isEqualTo("pass")
    assertThat(stagingTestCase.results).doesNotContainKey("trialDebug")
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

    // 1. Check Root Summary
    val rootSummary = report.summary
    assertThat(rootSummary.total).isEqualTo(5)
    assertThat(rootSummary.passed).isEqualTo(2)
    assertThat(rootSummary.failed).isEqualTo(1)
    assertThat(rootSummary.skipped).isEqualTo(2)

    // passRate = passed / (passed + failed) = 2 / 3 = 66.666...
    assertThat(rootSummary.passRate).isWithin(0.01).of(66.66)

    // 2. Check Variant Summaries at Root
    val debugSummary = rootSummary.variantSummaries["debug"]!!
    assertThat(debugSummary.total).isEqualTo(2)
    assertThat(debugSummary.passed).isEqualTo(1)
    assertThat(debugSummary.failed).isEqualTo(1)
    assertThat(debugSummary.skipped).isEqualTo(0)
    assertThat(debugSummary.rate).isEqualTo(50.0)

    val releaseSummary = rootSummary.variantSummaries["release"]!!
    assertThat(releaseSummary.total).isEqualTo(2)
    assertThat(releaseSummary.passed).isEqualTo(1)
    assertThat(releaseSummary.failed).isEqualTo(0)
    assertThat(releaseSummary.skipped).isEqualTo(1)
    assertThat(releaseSummary.rate).isEqualTo(100.0)

    val skippedOnlySummary = rootSummary.variantSummaries["skippedOnly"]!!
    assertThat(skippedOnlySummary.total).isEqualTo(1)
    assertThat(skippedOnlySummary.passed).isEqualTo(0)
    assertThat(skippedOnlySummary.failed).isEqualTo(0)
    assertThat(skippedOnlySummary.skipped).isEqualTo(1)
    assertThat(skippedOnlySummary.rate).isEqualTo(0.0)

    // 3. Check Aggregation up the chain (Module, TestSuite, Package, Class)
    val appModule = report.modules.findOrThrow({ it.name == ":app" }) { ":app module not found" }
    assertThat(appModule.summary.total).isEqualTo(5)

    val unitTestSuite = appModule.testSuites.first()
    assertThat(unitTestSuite.summary.total).isEqualTo(5)

    val pkg = unitTestSuite.packages.first()
    assertThat(pkg.summary.total).isEqualTo(5)

    val clazz = pkg.classes.first()
    assertThat(clazz.summary.total).isEqualTo(5)
  }
}
