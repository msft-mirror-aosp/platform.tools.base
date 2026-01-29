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

import com.android.build.gradle.internal.coverage.renderer.data.CoverageReport
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class CodeCoverageReportOrchestratorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var inputDir: File
  private lateinit var outputDir: File
  private lateinit var mockReportDirProperty: DirectoryProperty
  private lateinit var projectBaseDir: File
  private lateinit var sourceRootDir: File

  @Before
  fun setUp() {
    inputDir = tempFolder.newFolder("inputs")
    outputDir = tempFolder.newFolder("outputs")
    projectBaseDir = tempFolder.newFolder("project")

    val mockDirectory = mock(Directory::class.java)
    `when`(mockDirectory.asFile).thenReturn(outputDir)

    mockReportDirProperty = mock(DirectoryProperty::class.java)
    `when`(mockReportDirProperty.get()).thenReturn(mockDirectory)

    sourceRootDir = File(projectBaseDir, "src/main/java").apply { mkdirs() }
    val packageDir = File(sourceRootDir, "com/example/myapp").apply { mkdirs() }
    File(packageDir, "MyClass.kt").writeText("package com.example.myapp\n\nclass MyClass {}")
  }

  @Test
  fun `orchestrate with valid XML creates all expected files and correct json`() {
    val relativeSourceRootPath = sourceRootDir.relativeTo(projectBaseDir).path
    val jacocoXmlFile = File(inputDir, "jacoco.xml")
    jacocoXmlFile.writeText(
      """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <report name="TestRootProject">
                <properties>
                    <property name="moduleName" value="app"/>
                    <property name="testedVariantName" value="debug"/>
                    <property name="testSuiteName" value="UnitTest"/>
                </properties>
                <sources>
                    <file path="$relativeSourceRootPath"/>
                </sources>
                <package name="com.example.myapp">
                    <class name="com/example/myapp/MyClass" sourcefilename="MyClass.kt">
                        <method name="&lt;init&gt;" desc="()V" line="5">
                            <counter type="INSTRUCTION" missed="0" covered="3"/>
                        </method>
                        <counter type="INSTRUCTION" missed="3" covered="12"/>
                    </class>
                    <sourcefile name="MyClass.kt">
                        <line nr="5" mi="0" ci="3" mb="0" cb="0"/>
                        <line nr="7" mi="3" ci="0" mb="0" cb="0"/>
                        <counter type="LINE" missed="1" covered="1"/>
                        <counter type="INSTRUCTION" missed="3" covered="3"/>
                    </sourcefile>
                </package>
                <counter type="INSTRUCTION" missed="10" covered="90"/>
            </report>
            """
        .trimIndent()
    )

    CodeCoverageReportOrchestrator.orchestrate(
      inputDirectories = listOf(inputDir),
      htmlReportDir = mockReportDirProperty,
      rootProjectName = "TestRootProject",
      rootProjectDir = projectBaseDir,
    )

    val reportDataFile = File(outputDir, "data/report-data.js")
    assertThat(reportDataFile.exists()).isTrue()

    val fileContent = reportDataFile.readText()
    assertThat(fileContent).startsWith("const fullReport = ")
    val json = fileContent.removePrefix("const fullReport = ").removeSuffix(";")
    val report = Gson().fromJson(json, CoverageReport::class.java)

    assertThat(report.name).isEqualTo("TestRootProject")
    assertThat(report.modules).hasSize(1)
    val moduleReport = report.modules.first()
    assertThat(moduleReport.name).isEqualTo("app")
    assertThat(moduleReport.testSuites).hasSize(1)
    assertThat(moduleReport.testSuites.first().name).isEqualTo("UnitTest")
    assertThat(report.numberOfTestsSuites).isEqualTo(1)
    assertThat(report.timeStamp).isNotEmpty()

    val expectedLogicalPath = "src/main/java/com.example.myapp/MyClass.kt"
    val sourceFileReport = File(outputDir, "sourcefiles/$expectedLogicalPath.json.js")
    assertThat(sourceFileReport.exists()).isTrue()

    val sourceFileContent = sourceFileReport.readText()
    // Note: The key in the JS file is based on the *logical* path from the builder.
    assertThat(sourceFileContent).contains("window.coverageData[\"$expectedLogicalPath\"]")

    assertThat(File(outputDir, "index.html").exists()).isTrue()
    assertThat(File(outputDir, "css/style.css").exists()).isTrue()
    assertThat(File(outputDir, "javascript/codecoveragescript.js").exists()).isTrue()
    assertThat(File(outputDir, "javascript/sourceviewscript.js").exists()).isTrue()
  }

  @Test
  fun `orchestrate with empty input creates directories but no data files`() {
    CodeCoverageReportOrchestrator.orchestrate(
      inputDirectories = listOf(inputDir),
      htmlReportDir = mockReportDirProperty,
      rootProjectName = "TestRootProject",
      rootProjectDir = projectBaseDir,
    )

    val reportDataFile = File(outputDir, "data/report-data.js")
    assertThat(reportDataFile.exists()).isTrue()

    val report =
      Gson().fromJson(reportDataFile.readText().removePrefix("const fullReport = ").removeSuffix(";"), CoverageReport::class.java)

    assertThat(report.name).isEqualTo("TestRootProject")
    assertThat(report.modules).isEmpty()
    assertThat(report.numberOfTestsSuites).isEqualTo(0)

    assertThat(File(outputDir, "index.html").exists()).isTrue()
  }

  @Test
  fun `orchestrate with malformed XML throws GradleException`() {
    val malformedXmlFile = File(inputDir, "malformed.xml")
    malformedXmlFile.writeText("<report><unclosed-tag></report>")

    try {
      CodeCoverageReportOrchestrator.orchestrate(
        inputDirectories = listOf(inputDir),
        htmlReportDir = mockReportDirProperty,
        rootProjectName = "TestRootProject",
        rootProjectDir = projectBaseDir,
      )
      fail("Expected a GradleException to be thrown for malformed XML.")
    } catch (e: GradleException) {
      assertThat(e).hasMessageThat().contains("Failed to parse code coverage XML report")
      assertThat(e).hasMessageThat().contains(malformedXmlFile.absolutePath)
    }
  }
}
