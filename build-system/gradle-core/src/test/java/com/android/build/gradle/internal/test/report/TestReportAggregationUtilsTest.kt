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

package com.android.build.gradle.internal.test.report

import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.XmlUtils
import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

class TestReportAggregationUtilsTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun processTestReportAggregation_keepsResultsOfOtherTargets() {
    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    val xmlResultsDirectory = directoryProperty(xmlResultsDir)

    // Device group test tasks process the results of each of their devices into the same directory.
    for (device in listOf("Pixel3", "Pixel4")) {
      val sourceDir = temporaryFolder.newFolder(device)
      sourceDir
        .resolve("TEST-com.example.SimpleTest.xml")
        .writeText(
          """<testsuite name="com.example.SimpleTest" tests="1"><testcase name="test" classname="com.example.SimpleTest"/></testsuite>"""
        )
      processTestReportAggregation(
        sourceDir,
        xmlResultsDirectory,
        ":app",
        "debug",
        "AndroidTest",
        device,
        Logging.getLogger(TestReportAggregationUtilsTest::class.java),
      )
    }

    val results = xmlResultsDir.listFiles()!!.map { it.readText() }
    assertThat(results).hasSize(2)
    assertThat(results.filter { it.contains("\"Pixel3\"") }).hasSize(1)
    assertThat(results.filter { it.contains("\"Pixel4\"") }).hasSize(1)
  }

  @Test
  fun processTestReportAggregation_renamesSuiteEnvironmentJsonAndPreservesOtherFiles() {
    val sourceDir = temporaryFolder.newFolder("rawResults")
    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    val xmlResultsDirectory = directoryProperty(xmlResultsDir)

    sourceDir
      .resolve("TEST-com.example.BackupTest.xml")
      .writeText(
        """<testsuite name="com.example.BackupTest" tests="1"><testcase name="testFlow" classname="com.example.BackupTest"/></testsuite>"""
      )
    val envJson = """{"schemaVersion":1,"modulePath":":app","variantName":"debug","suiteName":"backupTest","targetName":"default"}"""
    sourceDir.resolve("suite-environment.json").writeText(envJson)
    sourceDir.resolve("nested").apply {
      mkdirs()
      resolve("suite-environment.json").writeText(envJson)
    }

    val nonJunitXml = """<full-backup-content><exclude domain="sharedpref" path="secret.xml"/></full-backup-content>"""
    sourceDir.resolve("backup_rules.xml").writeText(nonJunitXml)

    val detailJson = """{"schemaVersion":1,"suiteType":"androidx.test.backup"}"""
    val detailDir = sourceDir.resolve("suite-details").also { it.mkdirs() }
    detailDir.resolve("BackupTest.testFlow_01.json").writeText(detailJson)
    val detailArtifactDir = detailDir.resolve("BackupTest.testFlow_01").also { it.mkdirs() }
    detailArtifactDir.resolve("host-log.txt").writeText("Backup started\nBackup finished\n")
    detailArtifactDir.resolve("data_extraction_rules.xml").writeText("<data-extraction-rules/>")
    detailArtifactDir.resolve("TEST-captured_artifact.xml").writeText("<captured/>")
    detailArtifactDir.resolve("suite-environment.json").writeText("""{"captured":true}""")

    processTestReportAggregation(
      sourceDir,
      xmlResultsDirectory,
      ":app",
      "debug",
      "backupTest",
      "default",
      Logging.getLogger(TestReportAggregationUtilsTest::class.java),
    )

    val topLevelFiles = xmlResultsDir.listFiles()!!.filter { it.isFile }.associateBy { it.name }
    val enrichedTestXml = topLevelFiles.entries.single { it.key.startsWith("TEST-com.example.BackupTest_") && it.key.endsWith(".xml") }
    val hash = enrichedTestXml.key.removePrefix("TEST-com.example.BackupTest_").removeSuffix(".xml")
    assertThat(hash).hasLength(32)
    assertThat(enrichedTestXml.value.readText()).contains("""name="testSuiteName" value="backupTest"""")

    // suite-environment.json is renamed with the matching metadata hash (both root and nested outside suite-details).
    assertThat(topLevelFiles).doesNotContainKey("suite-environment.json")
    assertThat(topLevelFiles).containsKey("suite-environment_${hash}.json")
    assertThat(topLevelFiles.getValue("suite-environment_${hash}.json").readText()).isEqualTo(envJson)
    assertThat(xmlResultsDir.resolve("nested/suite-environment_${hash}.json").readText()).isEqualTo(envJson)
    assertThat(xmlResultsDir.resolve("nested/suite-environment.json").exists()).isFalse()

    // Non-TEST-*.xml files are copied verbatim, and suite-details/** is namespaced under suite-details/<hash>/... without mutation.
    assertThat(topLevelFiles).containsKey("backup_rules.xml")
    assertThat(topLevelFiles.getValue("backup_rules.xml").readText()).isEqualTo(nonJunitXml)
    assertThat(xmlResultsDir.resolve("suite-details/BackupTest.testFlow_01.json").exists()).isFalse()
    assertThat(xmlResultsDir.resolve("suite-details/$hash/BackupTest.testFlow_01.json").readText()).isEqualTo(detailJson)
    assertThat(xmlResultsDir.resolve("suite-details/$hash/BackupTest.testFlow_01/host-log.txt").readText())
      .isEqualTo("Backup started\nBackup finished\n")
    assertThat(xmlResultsDir.resolve("suite-details/$hash/BackupTest.testFlow_01/data_extraction_rules.xml").readText())
      .isEqualTo("<data-extraction-rules/>")
    assertThat(xmlResultsDir.resolve("suite-details/$hash/BackupTest.testFlow_01/TEST-captured_artifact.xml").readText())
      .isEqualTo("<captured/>")
    assertThat(xmlResultsDir.resolve("suite-details/$hash/BackupTest.testFlow_01/suite-environment.json").readText())
      .isEqualTo("""{"captured":true}""")
  }

  @Test
  fun processTestReportAggregation_namespacesSuiteDetailsAcrossTargetsWithoutCollision() {
    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    val xmlResultsDirectory = directoryProperty(xmlResultsDir)

    for (device in listOf("Pixel3", "Pixel4")) {
      val sourceDir = temporaryFolder.newFolder("raw_$device")
      sourceDir
        .resolve("TEST-com.example.BackupTest.xml")
        .writeText(
          """<testsuite name="com.example.BackupTest" tests="1"><testcase name="testFlow" classname="com.example.BackupTest"/></testsuite>"""
        )
      sourceDir
        .resolve("suite-environment.json")
        .writeText("""{"schemaVersion":1,"modulePath":":app","variantName":"debug","suiteName":"backupTest","targetName":"$device"}""")
      val detailDir = sourceDir.resolve("suite-details").also { it.mkdirs() }
      detailDir.resolve("d1.json").writeText("""{"schemaVersion":1,"suiteType":"androidx.test.backup","device":"$device"}""")
      detailDir.resolve("d1").apply {
        mkdirs()
        resolve("host-log.txt").writeText("log-from-$device")
      }

      processTestReportAggregation(
        sourceDir,
        xmlResultsDirectory,
        ":app",
        "debug",
        "backupTest",
        device,
        Logging.getLogger(TestReportAggregationUtilsTest::class.java),
      )
    }

    val xmlFiles = xmlResultsDir.listFiles()!!.filter { it.isFile && it.name.startsWith("TEST-com.example.BackupTest_") }
    assertThat(xmlFiles).hasSize(2)
    val hashByDevice = xmlFiles.associate { file ->
      val text = file.readText()
      val device = if (text.contains("\"Pixel3\"")) "Pixel3" else "Pixel4"
      val hash = file.name.removePrefix("TEST-com.example.BackupTest_").removeSuffix(".xml")
      device to hash
    }
    assertThat(hashByDevice.keys).containsExactly("Pixel3", "Pixel4")
    val pixel3Hash = hashByDevice.getValue("Pixel3")
    val pixel4Hash = hashByDevice.getValue("Pixel4")
    assertThat(pixel3Hash).isNotEqualTo(pixel4Hash)

    assertThat(xmlResultsDir.resolve("suite-environment_$pixel3Hash.json").readText()).contains("\"targetName\":\"Pixel3\"")
    assertThat(xmlResultsDir.resolve("suite-environment_$pixel4Hash.json").readText()).contains("\"targetName\":\"Pixel4\"")
    assertThat(xmlResultsDir.resolve("suite-details/$pixel3Hash/d1.json").readText()).contains("\"device\":\"Pixel3\"")
    assertThat(xmlResultsDir.resolve("suite-details/$pixel4Hash/d1.json").readText()).contains("\"device\":\"Pixel4\"")
    assertThat(xmlResultsDir.resolve("suite-details/$pixel3Hash/d1/host-log.txt").readText()).isEqualTo("log-from-Pixel3")
    assertThat(xmlResultsDir.resolve("suite-details/$pixel4Hash/d1/host-log.txt").readText()).isEqualTo("log-from-Pixel4")
  }

  @Test
  fun processTestReportAggregation_injectsSuitePropertiesWithoutMutatingTestCaseProperties() {
    val sourceDir = temporaryFolder.newFolder("rawResults")
    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    val xmlResultsDirectory = directoryProperty(xmlResultsDir)

    sourceDir
      .resolve("TEST-com.example.BackupTest.xml")
      .writeText(
        """
        <testsuite name="com.example.BackupTest" tests="1">
          <testcase name="testFlow" classname="com.example.BackupTest" time="0.25">
            <properties>
              <property name="androidx.report.detailPath" value="suite-details/t1.json"/>
            </properties>
          </testcase>
        </testsuite>
        """
          .trimIndent()
      )

    processTestReportAggregation(
      sourceDir,
      xmlResultsDirectory,
      ":app",
      "debug",
      "backupTest",
      "default",
      Logging.getLogger(TestReportAggregationUtilsTest::class.java),
    )

    val enrichedXml = xmlResultsDir.listFiles()!!.single { it.name.startsWith("TEST-com.example.BackupTest_") }
    val root = parseXmlElement(enrichedXml)
    val directChildren = XmlUtils.getSubTagsAsList(root)
    assertThat(directChildren.map { it.tagName }).containsExactly("properties", "testcase").inOrder()

    val suiteProps = directChildren[0].readPropertiesMap()
    assertThat(suiteProps)
      .containsExactly(
        "modulePath",
        ":app",
        "testedVariantName",
        "debug",
        "testSuiteName",
        "backupTest",
        "testTarget",
        "default",
      )

    val testCaseProps = XmlUtils.getSubTagsAsList(directChildren[1]).single { it.tagName == "properties" }.readPropertiesMap()
    assertThat(testCaseProps).containsExactly("androidx.report.detailPath", "suite-details/t1.json")

    assertThat(XMLReportAggregator(listOf(xmlResultsDir), ":app", temporaryFolder.root).getTestCount()).isEqualTo(1)
  }

  @Test
  fun processTestReportAggregation_injectsPropertiesIntoEverySuiteUnderTestsuites() {
    val sourceDir = temporaryFolder.newFolder("rawResults")
    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    val xmlResultsDirectory = directoryProperty(xmlResultsDir)

    sourceDir
      .resolve("TEST-withExistingProps.xml")
      .writeText(
        """
        <testsuites>
          <testsuite name="com.example.FirstTest" tests="1">
            <properties>
              <property name="existing" value="1"/>
            </properties>
            <testcase name="firstCase" classname="com.example.FirstTest" time="0.1"/>
          </testsuite>
          <testsuite name="com.example.SecondTest" tests="1">
            <testcase name="secondCase" classname="com.example.SecondTest" time="0.1"/>
          </testsuite>
        </testsuites>
        """
          .trimIndent()
      )

    sourceDir
      .resolve("TEST-withoutSuiteProps.xml")
      .writeText(
        """
        <testsuites>
          <testsuite name="com.example.ThirdTest" tests="1">
            <testcase name="thirdCase" classname="com.example.ThirdTest" time="0.1">
              <properties>
                <property name="androidx.report.detailPath" value="suite-details/t3.json"/>
              </properties>
            </testcase>
          </testsuite>
          <testsuite name="com.example.FourthTest" tests="1">
            <testcase name="fourthCase" classname="com.example.FourthTest" time="0.1"/>
          </testsuite>
        </testsuites>
        """
          .trimIndent()
      )

    processTestReportAggregation(
      sourceDir,
      xmlResultsDirectory,
      ":app",
      "debug",
      "backupTest",
      "default",
      Logging.getLogger(TestReportAggregationUtilsTest::class.java),
    )

    val expectedMetadata =
      mapOf(
        "modulePath" to ":app",
        "testedVariantName" to "debug",
        "testSuiteName" to "backupTest",
        "testTarget" to "default",
      )

    val withExistingRoot = parseXmlElement(xmlResultsDir.listFiles()!!.single { it.name.startsWith("TEST-withExistingProps_") })
    assertThat(XmlUtils.getSubTagsAsList(withExistingRoot).filter { it.tagName == "properties" }).isEmpty()
    val withExistingSuites = XmlUtils.getSubTagsAsList(withExistingRoot).filter { it.tagName == "testsuite" }
    assertThat(withExistingSuites).hasSize(2)
    assertThat(XmlUtils.getSubTagsAsList(withExistingSuites[0]).map { it.tagName }).containsExactly("properties", "testcase").inOrder()
    assertThat(XmlUtils.getSubTagsAsList(withExistingSuites[0]).single { it.tagName == "properties" }.readPropertiesMap())
      .containsExactlyEntriesIn(expectedMetadata + ("existing" to "1"))
    assertThat(XmlUtils.getSubTagsAsList(withExistingSuites[1]).map { it.tagName }).containsExactly("properties", "testcase").inOrder()
    assertThat(XmlUtils.getSubTagsAsList(withExistingSuites[1]).single { it.tagName == "properties" }.readPropertiesMap())
      .containsExactlyEntriesIn(expectedMetadata)

    val withoutPropsRoot = parseXmlElement(xmlResultsDir.listFiles()!!.single { it.name.startsWith("TEST-withoutSuiteProps_") })
    assertThat(XmlUtils.getSubTagsAsList(withoutPropsRoot).filter { it.tagName == "properties" }).isEmpty()
    val withoutPropsSuites = XmlUtils.getSubTagsAsList(withoutPropsRoot).filter { it.tagName == "testsuite" }
    assertThat(withoutPropsSuites).hasSize(2)
    for (suite in withoutPropsSuites) {
      assertThat(XmlUtils.getSubTagsAsList(suite).map { it.tagName }).containsExactly("properties", "testcase").inOrder()
      assertThat(XmlUtils.getSubTagsAsList(suite).single { it.tagName == "properties" }.readPropertiesMap())
        .containsExactlyEntriesIn(expectedMetadata)
    }
    val thirdTestCase = XmlUtils.getSubTagsAsList(withoutPropsSuites[0]).single { it.tagName == "testcase" }
    val thirdTestCaseProps = XmlUtils.getSubTagsAsList(thirdTestCase).single { it.tagName == "properties" }.readPropertiesMap()
    assertThat(thirdTestCaseProps).containsExactly("androidx.report.detailPath", "suite-details/t3.json")

    assertThat(XMLReportAggregator(listOf(xmlResultsDir), ":app", temporaryFolder.root).getTestCount()).isEqualTo(4)
  }

  @Test
  fun processTestReportAggregation_blocksDtdExternalEntityExpansion() {
    val secretFile = temporaryFolder.newFile("secret.txt").apply { writeText("TOP_SECRET_ENTITY_CONTENT") }
    val sourceDir = temporaryFolder.newFolder("rawResults")
    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    val xmlResultsDirectory = directoryProperty(xmlResultsDir)

    sourceDir
      .resolve("TEST-com.example.XxeTest.xml")
      .writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE testsuite [
          <!ENTITY ext SYSTEM "${secretFile.toURI()}">
        ]>
        <testsuite name="com.example.XxeTest" tests="1">
          <testcase name="xxeCase" classname="com.example.XxeTest">
            <system-out>&ext;</system-out>
          </testcase>
        </testsuite>
        """
          .trimIndent()
      )

    processTestReportAggregation(
      sourceDir,
      xmlResultsDirectory,
      ":app",
      "debug",
      "backupTest",
      "default",
      Logging.getLogger(TestReportAggregationUtilsTest::class.java),
    )

    val outputXml = xmlResultsDir.listFiles()!!.single { it.name.startsWith("TEST-com.example.XxeTest_") }
    assertThat(outputXml.readText()).doesNotContain("TOP_SECRET_ENTITY_CONTENT")
  }

  @Test
  fun clearTestReportAggregationResults_removesResultsOfPreviousRuns() {
    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    // Result of a test class that was deleted after the previous run.
    xmlResultsDir.resolve("TEST-com.example.DeletedTest_0123.xml").writeText("<testsuite/>")

    clearTestReportAggregationResults(directoryProperty(xmlResultsDir))

    assertThat(xmlResultsDir).isDirectory()
    assertThat(xmlResultsDir.listFiles()!!.toList()).isEmpty()
  }

  private fun parseXmlElement(file: File): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

  private fun Element.readPropertiesMap(): Map<String, String> =
    XmlUtils.getSubTagsAsList(this).filter { it.tagName == "property" }.associate { it.getAttribute("name") to it.getAttribute("value") }

  private fun directoryProperty(dir: File): DirectoryProperty =
    ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build().objects.directoryProperty().fileValue(dir)
}
