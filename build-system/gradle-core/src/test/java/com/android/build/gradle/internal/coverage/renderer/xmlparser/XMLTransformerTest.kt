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

package com.android.build.gradle.internal.coverage.renderer.xmlparser

import com.android.build.gradle.internal.coverage.renderer.builders.CoverageReportBuilder
import com.android.build.gradle.internal.coverage.renderer.builders.SourceFileReportsBuilder
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XMLTransformerTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var sourceFileReportsBuilder: SourceFileReportsBuilder
  private lateinit var projectBaseDir: File
  private lateinit var sourceRoot: File
  private lateinit var relativeSourceFilePath: String

  @Before
  fun setUp() {
    sourceFileReportsBuilder = SourceFileReportsBuilder()
    projectBaseDir = tempFolder.root

    sourceRoot = File(projectBaseDir, "src/main/java").apply { mkdirs() }
    val packageDir = File(sourceRoot, "com/example/app").apply { mkdirs() }
    val sourceFile = File(packageDir, "MyActivity.kt")
    sourceFile.createNewFile()
    relativeSourceFilePath = sourceFile.relativeTo(projectBaseDir).path.replace(File.separatorChar, '/')
  }

  @Test
  fun `transform with non-existent file does nothing`() {
    val nonExistentFile = File(tempFolder.root, "nonexistent.xml")
    val coverageBuilder = CoverageReportBuilder("Test Project", "now")

    XMLTransformer.transform(nonExistentFile, projectBaseDir, coverageBuilder, sourceFileReportsBuilder)

    assertThat(coverageBuilder.moduleReportBuilders).isEmpty()
    assertThat(coverageBuilder.aggregatedVariantCoverages).isEmpty()
    assertThat(sourceFileReportsBuilder.sourceFileBuilders).isEmpty()
    assertThat(coverageBuilder.allTestSuiteNames).isEmpty()
  }

  @Test
  fun `transform parses and aggregates multiple reports correctly`() {
    val unitTestXml = createXmlFile("unit_test_report.xml", getXmlContent("my-module", "debug", "UnitTest", sourceRoot.absolutePath))
    val aggregatedXml = createXmlFile("aggregated_report.xml", getAggregatedXmlContent("my-module", "debug"))

    val coverageBuilder = CoverageReportBuilder("Test Project", "now")

    XMLTransformer.transform(unitTestXml, projectBaseDir, coverageBuilder, sourceFileReportsBuilder)
    XMLTransformer.transform(aggregatedXml, projectBaseDir, coverageBuilder, sourceFileReportsBuilder)

    assertThat(coverageBuilder.allTestSuiteNames).containsExactly("UnitTestmy-module")

    assertThat(coverageBuilder.aggregatedVariantCoverages).hasSize(1)
    val projectVariantCoverage = coverageBuilder.aggregatedVariantCoverages["debug"]!!
    assertThat(projectVariantCoverage.name).isEqualTo("debug")
    // Sum of aggregated (15) and unit test (90)
    assertThat(projectVariantCoverage.instruction.covered).isEqualTo(105)
    // Sum of aggregated (20) and unit test (100)
    assertThat(projectVariantCoverage.instruction.total).isEqualTo(120)
    assertThat(projectVariantCoverage.instruction.percent).isEqualTo(87)

    assertThat(coverageBuilder.moduleReportBuilders).hasSize(1)
    val moduleBuilder = coverageBuilder.moduleReportBuilders["my-module"]!!
    assertThat(moduleBuilder.name).isEqualTo("my-module")

    assertThat(moduleBuilder.variantCoverages).hasSize(1)
    val moduleAggregatedCoverage = moduleBuilder.variantCoverages.first()
    assertThat(moduleAggregatedCoverage.name).isEqualTo("debug")
    assertThat(moduleAggregatedCoverage.instruction.covered).isEqualTo(15)
    assertThat(moduleAggregatedCoverage.instruction.total).isEqualTo(20)

    assertThat(moduleBuilder.testSuites).hasSize(1)
    val testSuiteBuilder = moduleBuilder.testSuites["UnitTest"]!!
    assertThat(testSuiteBuilder.name).isEqualTo("UnitTest")

    assertThat(testSuiteBuilder.variantCoverages).hasSize(1)
    val testSuiteCoverage = testSuiteBuilder.variantCoverages.first()
    assertThat(testSuiteCoverage.name).isEqualTo("debug")
    assertThat(testSuiteCoverage.instruction.covered).isEqualTo(90)
    assertThat(testSuiteCoverage.instruction.total).isEqualTo(100)

    assertThat(testSuiteBuilder.packages).hasSize(1)
    val packageBuilder = testSuiteBuilder.packages["com.example.app"]!!
    assertThat(packageBuilder.fullyQualifiedName).isEqualTo("com.example.app")
    assertThat(packageBuilder.testSuiteName).isEqualTo("UnitTest")
    assertThat(packageBuilder.variantCoverages.first().instruction.covered).isEqualTo(90)

    assertThat(packageBuilder.classes).hasSize(1)
    val classBuilder = packageBuilder.classes["MyActivity"]!!
    assertThat(classBuilder.name).isEqualTo("MyActivity")
    assertThat(classBuilder.packageName).isEqualTo("com.example.app")
    assertThat(classBuilder.sourceFileName).isEqualTo("MyActivity.kt")
    assertThat(classBuilder.testSuiteName).isEqualTo("UnitTest")

    val generatedPath = classBuilder.variantSourceFilePaths.first().path
    assertThat(generatedPath).isEqualTo("src/main/java/com.example.app/MyActivity.kt")

    assertThat(classBuilder.methods).hasSize(1)
    val methodBuilder = classBuilder.methods["onCreate(Landroid/os/Bundle;)V"]!!
    assertThat(methodBuilder.name).isEqualTo("onCreate(Landroid/os/Bundle;)V")
    assertThat(methodBuilder.variantLineNumbers.first().lineNumber).isEqualTo(15)

    assertThat(sourceFileReportsBuilder.sourceFileBuilders.keys).contains(relativeSourceFilePath)
    val sourceFileBuilder = sourceFileReportsBuilder.sourceFileBuilders[relativeSourceFilePath]!!
    assertThat(sourceFileBuilder.packageFlattenedPath).isEqualTo("src/main/java/com.example.app/MyActivity.kt")

    val variantBuilder = sourceFileBuilder.variantFileCoverageBuilders["debug"]!!
    assertThat(variantBuilder.testSuiteFileCoverageBuilders).hasSize(1)
    val testSuiteFileBuilder = variantBuilder.testSuiteFileCoverageBuilders["UnitTest"]!!
    assertThat(testSuiteFileBuilder.fileCoverage.covered).isEqualTo(10)
    assertThat(testSuiteFileBuilder.fileCoverage.total).isEqualTo(12)
    assertThat(testSuiteFileBuilder.lineCoverageBuilders).hasSize(2)

    val line15 = testSuiteFileBuilder.lineCoverageBuilders[15]!!
    assertThat(line15.instructionCoverageInfo.covered).isEqualTo(10)
    assertThat(line15.instructionCoverageInfo.total).isEqualTo(10)
    assertThat(line15.instructionCoverageInfo.percent).isEqualTo(100)

    val line20 = testSuiteFileBuilder.lineCoverageBuilders[20]!!
    assertThat(line20.instructionCoverageInfo.covered).isEqualTo(15)
    assertThat(line20.instructionCoverageInfo.total).isEqualTo(20)
    assertThat(line20.instructionCoverageInfo.percent).isEqualTo(75)
    assertThat(line20.branchCoverageInfo.covered).isEqualTo(1)
    assertThat(line20.branchCoverageInfo.total).isEqualTo(2)
    assertThat(line20.branchCoverageInfo.percent).isEqualTo(50)
  }

  @Test
  fun `transform with default package and aggregated packages`() {
    val defaultActivityFile = File(sourceRoot, "DefaultActivity.kt").apply { createNewFile() }
    val defaultPackageXml =
      createXmlFile(
        "default_package_report.xml",
        getDefaultPackageXmlContent("my-module", "debug", "DefaultPackageTest", sourceRoot.absolutePath),
      )
    val aggregatedWithPackagesXml = createXmlFile("aggregated_with_packages.xml", getAggregatedWithPackagesXmlContent("my-module", "debug"))
    val coverageBuilder = CoverageReportBuilder("Test Project", "now")

    XMLTransformer.transform(defaultPackageXml, projectBaseDir, coverageBuilder, sourceFileReportsBuilder)
    XMLTransformer.transform(aggregatedWithPackagesXml, projectBaseDir, coverageBuilder, sourceFileReportsBuilder)
    assertThat(coverageBuilder.allTestSuiteNames).containsExactly("DefaultPackageTestmy-module")
    val moduleBuilder = coverageBuilder.moduleReportBuilders["my-module"]!!
    assertThat(moduleBuilder.packages).hasSize(1)
    val modulePackageBuilder = moduleBuilder.packages["com.other.package"]!!
    assertThat(modulePackageBuilder.fullyQualifiedName).isEqualTo("com.other.package")
    assertThat(modulePackageBuilder.testSuiteName).isEqualTo("Aggregated")
    assertThat(modulePackageBuilder.variantCoverages.first().instruction.covered).isEqualTo(5)

    val testSuiteBuilder = moduleBuilder.testSuites["DefaultPackageTest"]!!
    assertThat(testSuiteBuilder.packages).hasSize(1)
    val defaultPackageBuilder = testSuiteBuilder.packages["default"]!!
    assertThat(defaultPackageBuilder.fullyQualifiedName).isEqualTo("default")
    assertThat(defaultPackageBuilder.classes).hasSize(1)

    val classBuilder = defaultPackageBuilder.classes["DefaultActivity"]!!
    assertThat(classBuilder.name).isEqualTo("DefaultActivity")

    val generatedPath = classBuilder.variantSourceFilePaths.first().path
    val expectedLogicalPath = "src/main/java/default/DefaultActivity.kt"
    assertThat(generatedPath).isEqualTo(expectedLogicalPath)

    val physicalRelativePath = defaultActivityFile.relativeTo(projectBaseDir).path.replace(File.separator, "/")
    assertThat(physicalRelativePath).isEqualTo("src/main/java/DefaultActivity.kt")
    assertThat(sourceFileReportsBuilder.sourceFileBuilders.keys).contains(physicalRelativePath)

    val sourceFileBuilder = sourceFileReportsBuilder.sourceFileBuilders[physicalRelativePath]!!
    assertThat(sourceFileBuilder.packageFlattenedPath).isEqualTo(expectedLogicalPath)
    val testSuiteFileBuilder =
      sourceFileBuilder.variantFileCoverageBuilders["debug"]!!.testSuiteFileCoverageBuilders["DefaultPackageTest"]!!
    assertThat(testSuiteFileBuilder.lineCoverageBuilders).hasSize(1)
    assertThat(testSuiteFileBuilder.lineCoverageBuilders[10]).isNotNull()
  }

  private fun createXmlFile(name: String, content: String): File {
    return tempFolder.newFile(name).apply { writeText(content) }
  }

  private fun getXmlContent(module: String, variant: String, testSuite: String, srcPath: String) =
    """
        <report name="My Project">
            <properties>
                <property name="moduleName" value="$module"/>
                <property name="testedVariantName" value="$variant"/>
                <property name="testSuiteName" value="$testSuite"/>
            </properties>
            <counter type="INSTRUCTION" missed="10" covered="90"/>
            <counter type="BRANCH" missed="2" covered="8"/>
            <counter type="LINE" missed="5" covered="45"/>
            <sources>
                <file path="src/main/java"/>
            </sources>
            <package name="com/example/app">
                <counter type="INSTRUCTION" missed="10" covered="90"/>
                <counter type="BRANCH" missed="2" covered="8"/>
                <counter type="LINE" missed="5" covered="45"/>
                <class name="com/example/app/MyActivity" sourcefilename="MyActivity.kt">
                    <counter type="INSTRUCTION" missed="5" covered="25"/>
                    <counter type="BRANCH" missed="1" covered="1"/>
                    <counter type="LINE" missed="2" covered="10"/>
                    <method name="onCreate" desc="(Landroid/os/Bundle;)V" line="15">
                        <counter type="INSTRUCTION" missed="0" covered="10"/>
                    </method>
                </class>
                <sourcefile name="MyActivity.kt">
                    <counter type="INSTRUCTION" missed="7" covered="25"/>
                    <counter type="BRANCH" missed="1" covered="1"/>
                    <counter type="LINE" missed="2" covered="10"/>
                    <line nr="15" mi="0" ci="10" mb="0" cb="0"/>
                    <line nr="20" mi="5" ci="15" mb="1" cb="1"/>
                </sourcefile>
            </package>
        </report>
    """
      .trimIndent()

  private fun getAggregatedXmlContent(module: String, variant: String) =
    """
        <report name="My Project Aggregated">
            <properties>
                <property name="moduleName" value="$module"/>
                <property name="testedVariantName" value="$variant"/>
                <property name="testSuiteName" value="Aggregated"/>
            </properties>
            <counter type="INSTRUCTION" missed="5" covered="15"/>
            <counter type="BRANCH" missed="1" covered="4"/>
            <counter type="LINE" missed="1" covered="9"/>
        </report>
    """
      .trimIndent()

  private fun getDefaultPackageXmlContent(module: String, variant: String, testSuite: String, srcPath: String) =
    """
        <report name="Default Pkg Project">
            <properties>
                <property name="moduleName" value="$module"/>
                <property name="testedVariantName" value="$variant"/>
                <property name="testSuiteName" value="$testSuite"/>
            </properties>
            <sources>
                <file path="src/main/java"/>
            </sources>
            <package name="">
                <class name="DefaultActivity" sourcefilename="DefaultActivity.kt">
                    <counter type="INSTRUCTION" missed="0" covered="10"/>
                </class>
                <sourcefile name="DefaultActivity.kt">
                    <counter type="LINE" missed="0" covered="1"/>
                    <line nr="10" mi="0" ci="5" mb="0" cb="0"/>
                </sourcefile>
            </package>
        </report>
    """
      .trimIndent()

  private fun getAggregatedWithPackagesXmlContent(module: String, variant: String) =
    """
        <report name="Aggregated with Pkgs">
            <properties>
                <property name="moduleName" value="$module"/>
                <property name="testedVariantName" value="$variant"/>
                <property name="testSuiteName" value="Aggregated"/>
            </properties>
            <package name="com/other/package">
                <counter type="INSTRUCTION" missed="5" covered="5"/>
            </package>
        </report>
    """
      .trimIndent()
}
