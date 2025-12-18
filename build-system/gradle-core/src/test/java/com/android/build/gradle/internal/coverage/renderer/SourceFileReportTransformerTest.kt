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
import com.android.build.gradle.internal.coverage.renderer.builders.TestSuiteFileCoverageBuilder
import com.android.build.gradle.internal.coverage.renderer.builders.VariantFileCoverageBuilder
import com.android.build.gradle.internal.coverage.renderer.data.CoverageInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SourceFileReportTransformerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sourceFile: File
    private lateinit var projectBaseDir: File

    @Before
    fun setUp() {
        projectBaseDir = tempFolder.root
        sourceFile = tempFolder.newFile("MyClass.kt")
    }

    @Test
    fun `createSourceFileReport with empty builder returns report with no coverage info`() {
        sourceFile.writeText("line 1\nline 2")
        val relativePath = sourceFile.relativeTo(projectBaseDir).path
        val builder = SourceFileReportBuilder("com/example/MyClass.kt") // Empty builder

        val result = createSourceFileReport(relativePath, projectBaseDir, builder)

        assertThat(result.variantCoverageSummary).isEmpty()
        assertThat(result.linesCoverages).hasSize(2)
        assertThat(result.linesCoverages[0].lineText).isEqualTo("line 1")
        assertThat(result.linesCoverages[0].variantCoverageDetails).isEmpty()
    }

    @Test
    fun `createSourceFileReport with non-existent file throws exception`() {
        val nonExistentPath = "path/to/non/existent/File.kt"
        val builder = SourceFileReportBuilder("non/existent/File.kt").apply {
            variantFileCoverageBuilders["debug"] = VariantFileCoverageBuilder().apply {
                testSuiteFileCoverageBuilders["unitTest"] = TestSuiteFileCoverageBuilder(
                    fileCoverage = CoverageInfo(50, 5, 10)
                )
            }
        }

        try {
            createSourceFileReport(nonExistentPath, projectBaseDir, builder)
            fail("Expected an IllegalArgumentException to be thrown for a missing source file.")
        } catch (e: IllegalArgumentException) {
            val expectedMissingFile = File(projectBaseDir, nonExistentPath)
            assertThat(e).hasMessageThat().contains("Failed to generate coverage report. Source file not found:")
            assertThat(e).hasMessageThat().contains(expectedMissingFile.absolutePath)

        }
    }

    @Test
    fun `createSourceFileReport aggregates full report correctly`() {
        sourceFile.writeText(
            """
            // A comment line

            class MyClass {
                fun coveredByAll() {}
                fun coveredByDebugOnly() {}
            }
            """.trimIndent()
        )
        val packageFlattenedPath = "com/example/MyClass.kt"
        val builder = SourceFileReportBuilder(packageFlattenedPath).apply {
            variantFileCoverageBuilders["debug"] = VariantFileCoverageBuilder().apply {
                testSuiteFileCoverageBuilders["unitTest"] = TestSuiteFileCoverageBuilder(
                    fileCoverage = CoverageInfo(66, 10, 15),
                    lineCoverageBuilders = mutableMapOf(
                        4 to LineCoverageBuilder(
                            instructionCoverageInfo = CoverageInfo(100, 10, 10),
                            branchCoverageInfo = CoverageInfo(0, 0, 0)
                        )
                    )
                )
                testSuiteFileCoverageBuilders["connectedTest"] = TestSuiteFileCoverageBuilder(
                    fileCoverage = CoverageInfo(65, 13, 20),
                    lineCoverageBuilders = mutableMapOf(
                        4 to LineCoverageBuilder(
                            instructionCoverageInfo = CoverageInfo(100, 8, 8),
                            branchCoverageInfo = CoverageInfo(0, 0, 0)
                        ),
                        5 to LineCoverageBuilder(
                            instructionCoverageInfo = CoverageInfo(100, 5, 5),
                            branchCoverageInfo = CoverageInfo(0, 0, 0)
                        )
                    )
                )
            }
            variantFileCoverageBuilders["release"] = VariantFileCoverageBuilder().apply {
                testSuiteFileCoverageBuilders["unitTest"] = TestSuiteFileCoverageBuilder(
                    fileCoverage = CoverageInfo(66, 12, 18),
                    lineCoverageBuilders = mutableMapOf(
                        4 to LineCoverageBuilder(
                            instructionCoverageInfo = CoverageInfo(100, 12, 12),
                            branchCoverageInfo = CoverageInfo(0, 0, 0)
                        )
                    )
                )
            }
        }

        val relativePath = sourceFile.relativeTo(projectBaseDir).path
        val report = createSourceFileReport(relativePath, projectBaseDir, builder)

        assertThat(report.variantCoverageSummary).hasSize(2)
        val debugSummary = report.variantCoverageSummary.find { it.variantName == "debug" }!!
        val releaseSummary = report.variantCoverageSummary.find { it.variantName == "release" }!!

        assertThat(debugSummary.testSuiteCoverages).hasSize(2)
        assertThat(debugSummary.testSuiteCoverages.find { it.testSuiteName == "unitTest" }!!.variantCoverage.instruction.covered).isEqualTo(10)
        assertThat(debugSummary.testSuiteCoverages.find { it.testSuiteName == "connectedTest" }!!.variantCoverage.instruction.covered).isEqualTo(13)

        assertThat(releaseSummary.testSuiteCoverages).hasSize(1)
        assertThat(releaseSummary.testSuiteCoverages.first().testSuiteName).isEqualTo("unitTest")
        assertThat(releaseSummary.testSuiteCoverages.first().variantCoverage.instruction.covered).isEqualTo(12)

        assertThat(report.linesCoverages).hasSize(6)

        val line1Details = report.linesCoverages[0]
        assertThat(line1Details.lineNumber).isEqualTo(1)
        assertThat(line1Details.lineText).isEqualTo("// A comment line")
        assertThat(line1Details.variantCoverageDetails.all { it.testSuiteCoverages.isEmpty() }).isTrue()

        val line4Details = report.linesCoverages[3]
        assertThat(line4Details.lineNumber).isEqualTo(4)
        val line4DebugGroup = line4Details.variantCoverageDetails.find { it.variantName == "debug" }!!
        val line4ReleaseGroup = line4Details.variantCoverageDetails.find { it.variantName == "release" }!!
        assertThat(line4DebugGroup.testSuiteCoverages).hasSize(2)
        assertThat(line4DebugGroup.testSuiteCoverages.find { it.testSuiteName == "unitTest" }!!.variantCoverage.instruction.covered).isEqualTo(10)
        assertThat(line4DebugGroup.testSuiteCoverages.find { it.testSuiteName == "connectedTest" }!!.variantCoverage.instruction.covered).isEqualTo(8)
        assertThat(line4ReleaseGroup.testSuiteCoverages).hasSize(1)
        assertThat(line4ReleaseGroup.testSuiteCoverages.first().variantCoverage.instruction.covered).isEqualTo(12)

        val line5Details = report.linesCoverages[4]
        assertThat(line5Details.lineNumber).isEqualTo(5)
        val line5DebugGroup = line5Details.variantCoverageDetails.find { it.variantName == "debug" }!!
        val line5ReleaseGroup = line5Details.variantCoverageDetails.find { it.variantName == "release" }!!
        assertThat(line5DebugGroup.testSuiteCoverages).hasSize(1)
        val line5Coverage = line5DebugGroup.testSuiteCoverages.first()
        assertThat(line5Coverage.testSuiteName).isEqualTo("connectedTest")
        assertThat(line5Coverage.variantCoverage.instruction.covered).isEqualTo(5)
        assertThat(line5ReleaseGroup.testSuiteCoverages).isEmpty()
    }
}
