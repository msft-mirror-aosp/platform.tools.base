/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.options.BooleanOption
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.tooling.BuildException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class JacocoWithUnitTestReportTest(private val isJacocoPluginAppliedFromBuildFile: Boolean) {

  @get:Rule val testProject = GradleTestProjectBuilder().fromTestProject("unitTesting").create()

  companion object {

    @JvmStatic @Parameterized.Parameters(name = "isJacocoPluginAppliedFromBuildFile_{0}") fun params() = arrayOf(true, false)
  }

  @Before
  fun setup() {
    if (isJacocoPluginAppliedFromBuildFile) {
      TestFileUtils.appendToFile(testProject.buildFile, "apply plugin: 'jacoco'\n")
    }

    // Only UnitTest coverage is needed for these tests, but validate that classes are not
    // instrumented twice when AndroidTest coverage is enabled: b/281266702
    TestFileUtils.appendToFile(
      testProject.buildFile,
      "android.buildTypes.debug.enableUnitTestCoverage = true\n" + "android.buildTypes.debug.enableAndroidTestCoverage = true",
    )
  }

  @Test
  fun `test expected report contents`() {
    val run = testProject.executor().run("createDebugUnitTestCoverageReport")
    checkHighlightedSourceCodeReportFiles(testProject.buildDir)
    val reportDir = FileUtils.join(testProject.buildDir, "reports", "coverage", "test", "debug")
    val generatedCoverageReport = File(reportDir, "index.html")
    run.stdout.use { assertThat(ScannerSubject.assertThat(it).contains("View coverage report at ${generatedCoverageReport.toURI()}")) }
    assertThat(generatedCoverageReport.exists()).isTrue()
    val generatedCoverageReportHTML = generatedCoverageReport.readLines().joinToString("\n")
    val reportTitle = Regex("<span class=\"el_report\">(.*?)</span").find(generatedCoverageReportHTML)
    val totalCoverageMetricsContents = Regex("<tfoot>(.*?)</tfoot>").find(generatedCoverageReportHTML)
    val totalCoverageInfo = Regex("<td class=\"ctr2\">(.*?)</td>").find(totalCoverageMetricsContents?.groups?.first()!!.value)
    val totalUnitTestCoveragePercentage = totalCoverageInfo!!.groups[1]!!.value
    // Checks if the report title is expected.
    assertThat(reportTitle!!.groups[1]!!.value).isEqualTo("debug")
    // Checks if the total line coverage on unit tests exceeds 0% i.e
    assertThat(totalUnitTestCoveragePercentage.trimEnd('%').toInt() > 0).isTrue()

    // Verify XML report is generated
    assertThat(File(reportDir, "report.xml").exists()).isTrue()

    // Verify that only the debug reports have been created.
    val testReports = FileUtils.join(testProject.buildDir, "reports", "tests")
    assertThat(testReports.listFiles().map(File::getName)).containsExactly("testDebugUnitTest")
  }

  @Test
  fun `test expected report contents with aggregation enabled`() {
    val run = testProject.executor().with(BooleanOption.REPORT_AGGREGATION_SUPPORT, true).run("createDebugUnitTestCoverageReport")

    val reportDir = FileUtils.join(testProject.buildDir, "reports", "coverage", "test", "debug")
    val generatedCoverageReport = File(reportDir, "index.html")

    run.stdout.use { assertThat(ScannerSubject.assertThat(it).contains("View coverage report at ${generatedCoverageReport.toURI()}")) }

    assertThat(generatedCoverageReport.exists()).isTrue()

    // Verify XML report is generated
    assertThat(File(reportDir, "report.xml").exists()).isTrue()

    // Verify new format files
    assertThat(File(reportDir, "data/report-data.js").exists()).isTrue()
    assertThat(File(reportDir, "css/style.css").exists()).isTrue()
    assertThat(File(reportDir, "javascript/codecoveragescript.js").exists()).isTrue()
    assertThat(File(reportDir, "javascript/sourceviewscript.js").exists()).isTrue()

    // Verify source files are generated
    val sourceFilesDir = File(reportDir, "sourcefiles")
    assertThat(sourceFilesDir.exists()).isTrue()
    // It should contain something like com.android.tests/MainActivity.java.json.js
    // In unitTesting project, we have MainActivity.java and someKotlinCode.kt
    val expectedSourceJson = sourceFilesDir.walk().filter { it.extension == "js" }.toList()
    assertThat(expectedSourceJson.map { it.name }).containsAtLeast("MainActivity.java.json.js", "someKotlinCode.kt.json.js")

    val reportDataContent = File(reportDir, "data/report-data.js").readText()
    assertThat(reportDataContent).contains("const fullReport = ")
    // Should contain the project name, variant name and test suite coverages metadata
    assertThat(reportDataContent).contains("\"debug\"")
    assertThat(reportDataContent).contains("\"testSuiteCoverages\"")
    assertThat(reportDataContent).contains("\"name\"")
  }

  // Regression test for b/188953818.
  private fun checkHighlightedSourceCodeReportFiles(buildDir: File) {
    val reportPackageInfoDir = FileUtils.join(buildDir, "reports", "coverage", "test", "debug", "com.android.tests")
    assertThat(FileUtils.join(reportPackageInfoDir, "MainActivity.java.html").exists()).isTrue()
    assertThat(FileUtils.join(reportPackageInfoDir, "someKotlinCode.kt.html").exists()).isTrue()
  }

  @Test(expected = BuildException::class)
  fun `report not generated for build types with unit test coverage disabled`() {
    // Build fails as the code coverage report task has not been registered as there is no
    // code coverage data for the release build type.
    testProject.execute("createReleaseUnitTestCoverageReport")
  }
}
