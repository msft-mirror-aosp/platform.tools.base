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

package com.android.build.gradle.internal.test.tasks

import com.android.build.gradle.internal.test.tasks.TestResultsCollectionTask.CodeCoverageCollectionWorkerAction
import com.android.build.gradle.internal.test.tasks.TestResultsCollectionTask.CodeCoverageCollectionWorkerAction.Companion.getTestSuiteCoverageFiles
import com.android.build.gradle.tasks.TestSuiteTestTask.Companion.TEST_SUITE_METADATA_FILE
import com.android.build.gradle.tasks.TestSuiteTestTask.Companion.TEST_SUITE_METADATA_MODULE_KEY
import com.android.build.gradle.tasks.TestSuiteTestTask.Companion.TEST_SUITE_METADATA_SUITE_KEY
import com.android.build.gradle.tasks.TestSuiteTestTask.Companion.TEST_SUITE_METADATA_VARIANT_KEY
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element

class TestResultsCollectionTaskTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var project: Project
  private lateinit var task: TestableTestResultsCollectionTask
  private lateinit var outputDir: File

  abstract class TestableTestResultsCollectionTask : TestResultsCollectionTask() {
    fun runTaskAction() {
      doTaskAction()
    }
  }

  @Before
  fun setUp() {
    project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
    task = project.tasks.register("testTask", TestableTestResultsCollectionTask::class.java).get()
    outputDir = temporaryFolder.newFolder("outputDir")
    task.outputDir.set(outputDir)
  }

  @Test
  fun testDoTaskAction_copiesXmlFiles() {
    val unitTestResultsDir = temporaryFolder.newFolder("unitTestResults")
    val file1 = File(unitTestResultsDir, "TEST-file1.xml").apply { writeText("<testsuite/>") }
    val file2 = File(unitTestResultsDir, "TEST-file2.xml").apply { writeText("<testsuite/>") }
    File(unitTestResultsDir, "not-xml.txt").apply { writeText("hello") }

    task.unitTestResults.set(unitTestResultsDir)

    task.runTaskAction()

    assertThat(outputDir.resolve("TEST-file1.xml")).exists()
    assertThat(outputDir.resolve("TEST-file2.xml")).exists()
    assertThat(outputDir.resolve("not-xml.txt")).doesNotExist()
  }

  @Test
  fun testGetTestSuiteCoverageFiles() {
    val root = temporaryFolder.newFolder("test_suite_data")
    val subDir = File(root, "coverage_data").apply { mkdirs() }
    val ecFile = File(subDir, "test.ec").apply { createNewFile() }
    val execFile = File(subDir, "test.exec").apply { createNewFile() }
    File(subDir, "metadata.txt").apply { createNewFile() }

    File(root, TEST_SUITE_METADATA_FILE).apply { writeText("$TEST_SUITE_METADATA_SUITE_KEY=my_suite") }

    val result = getTestSuiteCoverageFiles(root)

    assertThat(result).isNotNull()
    val (testSuiteName, coverageFiles) = result!!
    assertThat(testSuiteName).isEqualTo("my_suite")
    assertThat(coverageFiles).containsExactly(ecFile, execFile)
  }

  @Test
  fun testGetTestSuiteCoverageFilesNoMetadata() {
    val root = temporaryFolder.newFolder("test_suite_data_no_metadata")
    File(root, "test.ec").apply { createNewFile() }

    val result = getTestSuiteCoverageFiles(root)

    assertThat(result).isNull()
  }

  @Test
  fun testGetTestSuiteCoverageFilesDoesNotExist() {
    val root = File(temporaryFolder.root, "does_not_exist")

    val result = getTestSuiteCoverageFiles(root)

    assertThat(result).isNull()
  }

  @Test
  fun testFormatProjectName() {
    assertThat(CodeCoverageCollectionWorkerAction.formatProjectName(":app")).isEqualTo("App")
    assertThat(CodeCoverageCollectionWorkerAction.formatProjectName(":core:datastore")).isEqualTo("CoreDatastore")
    assertThat(CodeCoverageCollectionWorkerAction.formatProjectName("app")).isEqualTo("App")
    assertThat(CodeCoverageCollectionWorkerAction.formatProjectName("")).isEqualTo("")
    assertThat(CodeCoverageCollectionWorkerAction.formatProjectName(":")).isEqualTo("")
  }

  @Test
  fun testInjectMetadataInXmlReport() {
    val tempDir = temporaryFolder.newFolder()
    val xmlFile = copyResourceToFolder("jacocoReport/com/android/tools/build/tests/myapplication/report.xml", tempDir)

    val properties =
      mapOf(
        TEST_SUITE_METADATA_MODULE_KEY to "app",
        TEST_SUITE_METADATA_SUITE_KEY to "UnitTest",
        TEST_SUITE_METADATA_VARIANT_KEY to "debug",
      )
    val sourceFolders = listOf("src/main/java", "src/main/kotlin")

    CodeCoverageCollectionWorkerAction.injectMetadataInXmlReport(xmlFile, properties, sourceFolders)

    assertThat(xmlFile).exists()

    val docFactory = DocumentBuilderFactory.newInstance()
    docFactory.isValidating = false
    docFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    docFactory.isIgnoringElementContentWhitespace = true
    val docBuilder = docFactory.newDocumentBuilder()
    val document: Document = docBuilder.parse(xmlFile)

    val propertiesNode = document.getElementsByTagName("properties").item(0) as Element
    assertThat(propertiesNode).isNotNull()

    val propertyElements = propertiesNode.childNodes
    assertThat(propertyElements.length).isEqualTo(3)

    for (i in 0 until propertyElements.length) {
      val propertyElement = propertyElements.item(i)
      val name = propertyElement.attributes.getNamedItem("name").nodeValue
      val value = propertyElement.attributes.getNamedItem("value").nodeValue
      assertThat(properties).containsEntry(name, value)
    }

    val sourcesNode = document.getElementsByTagName("sources").item(0) as Element
    assertThat(sourcesNode).isNotNull()

    val fileElements = sourcesNode.childNodes
    assertThat(fileElements.length).isEqualTo(2)

    for (i in 0 until fileElements.length) {
      val fileElement = fileElements.item(i)
      val path = fileElement.attributes.getNamedItem("path").nodeValue
      assertThat(sourceFolders).contains(path)
    }
  }

  @Test
  fun testCodeCoverageCollectionWorkerAction_handlesCoverageFilesNameCollisions() {
    val reportOutputDir = temporaryFolder.newFolder("coverage_output_dir_consolidated")

    // 1. Local coverage setup: Creates local coverage file for Unit Tests.
    // This will generate locally:
    //   - "debugAppUnitTestXmlReport.xml"
    //   - "debugAppAggregatedXmlReport.xml"
    val unitTestCoverageFile = File(temporaryFolder.newFolder("local_coverage"), "test.exec").apply { writeText("") }

    // 2. Dependent Module Real-World Collision (Path Overlap):
    // Module ":foo:bar" maps to formatted name "FooBar"
    // Module ":fooBar" also maps to formatted name "FooBar"
    // Both generate "debugFooBarUnitTestXmlReport.xml"
    val depDirFooBar1 = temporaryFolder.newFolder("foo_bar_module")
    val depFileFooBar1 = File(depDirFooBar1, "debugFooBarUnitTestXmlReport.xml").apply { writeText("foo_bar_content") }

    val depDirFooBar2 = temporaryFolder.newFolder("fooBar_module")
    val depFileFooBar2 = File(depDirFooBar2, "debugFooBarUnitTestXmlReport.xml").apply { writeText("fooBar_content") }

    // 3. Dependent Module Clashing with Local Report:
    // A dependent module also happens to have a report named "debugAppUnitTestXmlReport.xml"
    val depDirClashLocal = temporaryFolder.newFolder("clash_local_module")
    val depFileClashLocal = File(depDirClashLocal, "debugAppUnitTestXmlReport.xml").apply { writeText("clashing_dependent_content") }

    val worker =
      object : CodeCoverageCollectionWorkerAction() {
        override fun getParameters(): TestResultsCollectionTask.CodeCoverageWorkParameters {
          return object : TestResultsCollectionTask.CodeCoverageWorkParameters {
            override val reportOutputDir = project.objects.directoryProperty().apply { set(reportOutputDir) }
            override val unitTestCoverageFile = project.files(unitTestCoverageFile)
            override val connectedTestCoverageDirectory = project.files()
            override val testSuiteCoverageData = project.files()
            override val classFolders = project.files()
            override val sourceFolders = project.files()
            override val dependantModulesReports = project.files(depFileFooBar1, depFileFooBar2, depFileClashLocal)
            override val variantName = project.objects.property(String::class.java).apply { set("debug") }
            override val projectName = project.objects.property(String::class.java).apply { set("app") }
            override val projectRoot = project.objects.directoryProperty().apply { set(temporaryFolder.root) }
          }
        }
      }

    worker.execute()

    val files = reportOutputDir.listFiles() ?: emptyArray()
    val names = files.map { it.name }

    // We expect the following files:
    // - Locally generated: "debugAppUnitTestXmlReport.xml", "debugAppAggregatedXmlReport.xml"
    // - Copied with no clash: "debugFooBarUnitTestXmlReport.xml" (from :foo:bar)
    // - Copied and clashing with another copy: "debugFooBarUnitTestXmlReport_1.xml" (from :fooBar)
    // - Copied and clashing with local: "debugAppUnitTestXmlReport_1.xml" (from the clash_local_module)
    assertThat(names)
      .containsExactly(
        "debugAppUnitTestXmlReport.xml",
        "debugAppAggregatedXmlReport.xml",
        "debugFooBarUnitTestXmlReport.xml",
        "debugFooBarUnitTestXmlReport_1.xml",
        "debugAppUnitTestXmlReport_1.xml",
      )

    // Verify content preservation
    val fooBar1File = File(reportOutputDir, "debugFooBarUnitTestXmlReport.xml")
    val fooBar2File = File(reportOutputDir, "debugFooBarUnitTestXmlReport_1.xml")
    val clashLocalFile = File(reportOutputDir, "debugAppUnitTestXmlReport_1.xml")

    assertThat(fooBar1File.readText()).isEqualTo("foo_bar_content")
    assertThat(fooBar2File.readText()).isEqualTo("fooBar_content")
    assertThat(clashLocalFile.readText()).isEqualTo("clashing_dependent_content")
  }

  @Throws(IOException::class)
  private fun copyResourceToFolder(fileName: String, folder: File?): File {
    val inputStream = javaClass.classLoader.getResourceAsStream(fileName) ?: throw IOException("Resource not found: $fileName")

    val file = File(folder, fileName)
    FileUtils.mkdirs(file.parentFile)

    file.outputStream().use { fileOut -> inputStream.use { it.copyTo(fileOut) } }
    return file
  }
}
