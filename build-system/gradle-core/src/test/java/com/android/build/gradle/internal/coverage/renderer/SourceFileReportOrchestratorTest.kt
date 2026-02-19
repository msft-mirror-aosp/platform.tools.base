/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal.coverage.renderer

import com.android.build.gradle.internal.coverage.renderer.builders.LineCoverageBuilder
import com.android.build.gradle.internal.coverage.renderer.builders.SourceFileReportBuilder
import com.android.build.gradle.internal.coverage.renderer.builders.SourceFileReportsBuilder
import com.android.build.gradle.internal.coverage.renderer.builders.TestSuiteFileCoverageBuilder
import com.android.build.gradle.internal.coverage.renderer.builders.VariantFileCoverageBuilder
import com.android.build.gradle.internal.coverage.renderer.data.CoverageInfo
import com.android.build.gradle.internal.coverage.renderer.data.SourceFileCoverageReport
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import java.io.File
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceFileReportOrchestratorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var projectBaseDir: File
  private lateinit var outputDir: File
  private val gson = Gson()

  @Before
  fun setUp() {
    projectBaseDir = tempFolder.newFolder("project")
    outputDir = tempFolder.newFolder("output")
  }

  @Test
  fun `orchestrate creates correct file with aggregated content from multiple variants`() {
    val sourceFilePhysicalPath = "src/main/java/com/example/MyClass.kt"
    val sourceFileLogicalPath = "src/main/java/com/example/MyClass.kt"
    val sourceFile = File(projectBaseDir, sourceFilePhysicalPath)
    sourceFile.parentFile.mkdirs()
    val sourceFileContent =
      """
      package com.example

      class MyClass {
          fun coveredMethod() {}
          fun partiallyCoveredMethod() {}
      }
      """
        .trimIndent()
    sourceFile.writeText(sourceFileContent)

    val builder =
      SourceFileReportBuilder(packageFlattenedPath = sourceFileLogicalPath).apply {
        variantFileCoverageBuilders["debug"] =
          VariantFileCoverageBuilder().apply {
            testSuiteFileCoverageBuilders["test"] =
              TestSuiteFileCoverageBuilder(
                fileCoverage = CoverageInfo(percent = 80, covered = 8, total = 10),
                lineCoverageBuilders =
                  mutableMapOf(4 to LineCoverageBuilder(CoverageInfo(percent = 100, covered = 4, total = 4), CoverageInfo(0, 0, 0))),
              )
            testSuiteFileCoverageBuilders["androidTest"] =
              TestSuiteFileCoverageBuilder(
                fileCoverage = CoverageInfo(percent = 60, covered = 6, total = 10),
                lineCoverageBuilders =
                  mutableMapOf(
                    5 to
                      LineCoverageBuilder(
                        CoverageInfo(percent = 75, covered = 3, total = 4),
                        CoverageInfo(percent = 50, covered = 1, total = 2),
                      )
                  ),
              )
          }
        variantFileCoverageBuilders["release"] =
          VariantFileCoverageBuilder().apply {
            testSuiteFileCoverageBuilders["test"] =
              TestSuiteFileCoverageBuilder(
                fileCoverage = CoverageInfo(percent = 20, covered = 2, total = 10),
                lineCoverageBuilders =
                  mutableMapOf(4 to LineCoverageBuilder(CoverageInfo(percent = 100, covered = 2, total = 2), CoverageInfo(0, 0, 0))),
              )
          }
      }

    val sourceFileReportsBuilder = SourceFileReportsBuilder().apply { sourceFileBuilders[sourceFilePhysicalPath] = builder }

    SourceFileReportOrchestrator.orchestrate(sourceFileReportsBuilder, projectBaseDir, outputDir)

    val expectedOutputFile = File(outputDir, "$sourceFileLogicalPath.json.js")
    assertThat(expectedOutputFile.exists()).isTrue()

    val fileContent = expectedOutputFile.readText(Charsets.UTF_8)
    val expectedPrefix = "window.coverageData = window.coverageData || {};\nwindow.coverageData[\"$sourceFileLogicalPath\"] = "
    assertThat(fileContent).startsWith(expectedPrefix)

    val jsonContent = fileContent.removePrefix(expectedPrefix).removeSuffix(";")
    val aggregatedReport = gson.fromJson(jsonContent, SourceFileCoverageReport::class.java)

    assertThat(aggregatedReport.variantCoverageSummary).hasSize(2)
    val debugSummaryGroup = aggregatedReport.variantCoverageSummary.find { it.variantName == "debug" }!!
    assertThat(debugSummaryGroup.testSuiteCoverages).hasSize(2)
    assertThat(debugSummaryGroup.testSuiteCoverages.map { it.testSuiteName }).containsExactly("test", "androidTest")
    assertThat(debugSummaryGroup.testSuiteCoverages.find { it.testSuiteName == "test" }!!.variantCoverage.instruction.total).isEqualTo(10)

    val releaseSummaryGroup = aggregatedReport.variantCoverageSummary.find { it.variantName == "release" }!!
    assertThat(releaseSummaryGroup.testSuiteCoverages).hasSize(1)
    assertThat(releaseSummaryGroup.testSuiteCoverages.first().testSuiteName).isEqualTo("test")

    val allLines = sourceFileContent.lines()
    assertThat(aggregatedReport.linesCoverages).hasSize(allLines.size)
    assertThat(aggregatedReport.linesCoverages[0].lineText).isEqualTo(allLines[0])

    val line4Details = aggregatedReport.linesCoverages[3]
    assertThat(line4Details.lineNumber).isEqualTo(4)
    assertThat(line4Details.lineText).isEqualTo(allLines[3])
    assertThat(line4Details.variantCoverageDetails).hasSize(2)

    val line4DebugGroup = line4Details.variantCoverageDetails.find { it.variantName == "debug" }!!
    assertThat(line4DebugGroup.testSuiteCoverages).hasSize(1)
    assertThat(line4DebugGroup.testSuiteCoverages.first().testSuiteName).isEqualTo("test")
    assertThat(line4DebugGroup.testSuiteCoverages.first().variantCoverage.instruction.covered).isEqualTo(4)

    val line4ReleaseGroup = line4Details.variantCoverageDetails.find { it.variantName == "release" }!!
    assertThat(line4ReleaseGroup.testSuiteCoverages).hasSize(1)
    assertThat(line4ReleaseGroup.testSuiteCoverages.first().variantCoverage.instruction.covered).isEqualTo(2)

    val line5Details = aggregatedReport.linesCoverages[4]
    val line5DebugGroup = line5Details.variantCoverageDetails.find { it.variantName == "debug" }!!
    assertThat(line5DebugGroup.testSuiteCoverages).hasSize(1)
    val line5AndroidTestCoverage = line5DebugGroup.testSuiteCoverages.first()
    assertThat(line5AndroidTestCoverage.testSuiteName).isEqualTo("androidTest")
    assertThat(line5AndroidTestCoverage.variantCoverage.instruction.covered).isEqualTo(3)
    assertThat(line5AndroidTestCoverage.variantCoverage.branch.total).isEqualTo(2)

    val line5ReleaseGroup = line5Details.variantCoverageDetails.find { it.variantName == "release" }!!
    assertThat(line5ReleaseGroup.testSuiteCoverages).isEmpty()
  }

  @Test
  fun `orchestrate with empty input creates directory but no files`() {
    val sourceFileReportsBuilder = SourceFileReportsBuilder()
    SourceFileReportOrchestrator.orchestrate(sourceFileReportsBuilder, projectBaseDir, outputDir)

    assertThat(outputDir.exists()).isTrue()
    assertThat(outputDir.listFiles()?.isEmpty() ?: true).isTrue()
  }

  @Test
  fun `orchestrate with missing source file throws exception`() {
    val missingSourcePhysicalPath = "src/main/java/com/example/Missing.kt"
    val missingSourceLogicalPath = "src/main/java/com/example/Missing.kt"

    val builder =
      SourceFileReportBuilder(missingSourceLogicalPath).apply {
        variantFileCoverageBuilders["debug"] =
          VariantFileCoverageBuilder().apply {
            testSuiteFileCoverageBuilders["test"] = TestSuiteFileCoverageBuilder(fileCoverage = CoverageInfo(10, 1, 10))
          }
      }
    val sourceFileReportsBuilder = SourceFileReportsBuilder().apply { sourceFileBuilders[missingSourcePhysicalPath] = builder }

    try {
      SourceFileReportOrchestrator.orchestrate(sourceFileReportsBuilder, projectBaseDir, outputDir)
      fail("Expected an exception to be thrown for a missing source file.")
    } catch (e: Exception) {
      // The exception from parallelStream may be wrapped. We need to find the root cause.
      var cause: Throwable? = e
      while (cause?.cause != null) {
        cause = cause.cause
      }
      assertThat(cause).isInstanceOf(IllegalArgumentException::class.java)

      val expectedMissingFile = File(projectBaseDir, missingSourcePhysicalPath)
      assertThat(cause).hasMessageThat().contains("Failed to generate coverage report. Source file not found:")
      assertThat(cause).hasMessageThat().contains(expectedMissingFile.absolutePath)
    }
  }
}
