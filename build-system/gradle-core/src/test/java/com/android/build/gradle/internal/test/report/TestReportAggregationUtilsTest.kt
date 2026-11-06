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
import java.nio.file.Files
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
  fun processTestReportAggregation_rewritesValidAttachmentsAndPreservesRoutingHashWithAdditionalProperties() {
    val buildDir = temporaryFolder.newFolder("build")
    val sourceDir = buildDir.resolve("test-results/testDebugBackupTest").apply { mkdirs() }
    val attachmentsRoot = buildDir.resolve("intermediates/debug/testDebugBackupTest/attachments").apply { mkdirs() }
    val outsideSecret = temporaryFolder.newFile("secret.txt").apply { writeText("TOP_SECRET") }

    val testAttachmentDir = attachmentsRoot.resolve("testFlow").apply { mkdirs() }
    val hostLog = testAttachmentDir.resolve("host-log.txt").apply { writeText("Backup started\nBackup finished\n") }
    val suiteLog =
      attachmentsRoot.resolve("suite/logcat-emulator-5554.txt").apply {
        parentFile.mkdirs()
        writeText("I/BackupManagerService: init\n")
      }
    val symlinkAttachment = testAttachmentDir.resolve("symlink-secret.txt")
    runCatching { Files.createSymbolicLink(symlinkAttachment.toPath(), outsideSecret.toPath()) }

    val relativeHostLog = sourceDir.toPath().relativize(hostLog.toPath()).toString().replace('\\', '/')
    val relativeSymlink = sourceDir.toPath().relativize(symlinkAttachment.toPath()).toString().replace('\\', '/')
    val escapingRelative = sourceDir.toPath().relativize(outsideSecret.toPath()).toString().replace('\\', '/')

    sourceDir
      .resolve("TEST-com.example.BackupTest.xml")
      .writeText(
        """
        <testsuite name="com.example.BackupTest" tests="1">
          <testcase name="testFlow" classname="com.example.BackupTest">
            <system-out><![CDATA[Test stdout line
[[ATTACHMENT|$relativeHostLog]]
[[ATTACHMENT|$escapingRelative]]
[[ATTACHMENT|$relativeSymlink]]
After markers
]]></system-out>
          </testcase>
          <system-out>[[ATTACHMENT|${suiteLog.absolutePath}]]</system-out>
        </testsuite>
        """
          .trimIndent()
      )
    val nonJunitXml = """<full-backup-content><exclude domain="sharedpref" path="secret.xml"/></full-backup-content>"""
    sourceDir.resolve("backup_rules.xml").writeText(nonJunitXml)

    val baselineResultsDir = temporaryFolder.newFolder("baselineResults")
    val baselineSourceDir =
      temporaryFolder.newFolder("baselineSource").apply {
        resolve("TEST-com.example.BackupTest.xml").writeText("<testsuite name=\"com.example.BackupTest\" tests=\"0\"/>")
      }
    processTestReportAggregation(
      baselineSourceDir,
      directoryProperty(baselineResultsDir),
      ":app",
      "debug",
      "backupTest",
      "default",
      Logging.getLogger(TestReportAggregationUtilsTest::class.java),
    )
    val baselineHash = baselineResultsDir.listFiles()!!.single().name.removePrefix("TEST-com.example.BackupTest_").removeSuffix(".xml")

    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    processTestReportAggregation(
      sourceDir = sourceDir,
      xmlResultsDirectory = directoryProperty(xmlResultsDir),
      modulePath = ":app",
      variantName = "debug",
      suiteName = "backupTest",
      target = "default",
      logger = Logging.getLogger(TestReportAggregationUtilsTest::class.java),
      additionalProperties = mapOf("applicationId" to "com.example.app", "suiteType" to "androidx.test.backup"),
      allowedAttachmentRoot = attachmentsRoot,
    )

    val topLevelFiles = xmlResultsDir.listFiles()!!.filter { it.isFile }.associateBy { it.name }
    val enrichedTestXml = topLevelFiles.entries.single { it.key.startsWith("TEST-com.example.BackupTest_") && it.key.endsWith(".xml") }
    val hash = enrichedTestXml.key.removePrefix("TEST-com.example.BackupTest_").removeSuffix(".xml")
    assertThat(hash).isEqualTo(baselineHash)

    val root = parseXmlElement(enrichedTestXml.value)
    val suiteProps = XmlUtils.getSubTagsAsList(root).single { it.tagName == "properties" }.readPropertiesMap()
    assertThat(suiteProps)
      .containsAtLeast(
        "modulePath",
        ":app",
        "testedVariantName",
        "debug",
        "testSuiteName",
        "backupTest",
        "testTarget",
        "default",
        "applicationId",
        "com.example.app",
        "suiteType",
        "androidx.test.backup",
      )

    // Non-TEST-*.xml files are copied verbatim.
    assertThat(topLevelFiles.getValue("backup_rules.xml").readText()).isEqualTo(nonJunitXml)

    // Valid relative and absolute attachments inside allowedAttachmentRoot are copied and rewritten; escaping/symlinked ones are stripped.
    assertThat(xmlResultsDir.resolve("attachments/$hash/1/host-log.txt").readText()).isEqualTo("Backup started\nBackup finished\n")
    assertThat(xmlResultsDir.resolve("attachments/$hash/2/logcat-emulator-5554.txt").readText()).isEqualTo("I/BackupManagerService: init\n")
    val testcaseOut =
      XmlUtils.getSubTagsAsList(XmlUtils.getSubTagsAsList(root).single { it.tagName == "testcase" })
        .single { it.tagName == "system-out" }
        .textContent
    assertThat(testcaseOut).isEqualTo("Test stdout line\n[[ATTACHMENT|attachments/$hash/1/host-log.txt]]\nAfter markers\n")
    val suiteOut = XmlUtils.getSubTagsAsList(root).single { it.tagName == "system-out" }.textContent
    assertThat(suiteOut).isEqualTo("[[ATTACHMENT|attachments/$hash/2/logcat-emulator-5554.txt]]")
  }

  @Test
  fun processTestReportAggregation_namespacesAttachmentsAcrossTargetsWithoutCollision() {
    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    val xmlResultsDirectory = directoryProperty(xmlResultsDir)

    for (device in listOf("Pixel3", "Pixel4")) {
      val sourceDir = temporaryFolder.newFolder("raw_$device")
      val attachmentsDir = temporaryFolder.newFolder("attachments_$device")
      val logFile = attachmentsDir.resolve("host-log.txt").apply { writeText("log-from-$device") }
      val relPath = sourceDir.toPath().relativize(logFile.toPath()).toString().replace('\\', '/')
      sourceDir
        .resolve("TEST-com.example.BackupTest.xml")
        .writeText(
          """
          <testsuite name="com.example.BackupTest" tests="1">
            <testcase name="testFlow" classname="com.example.BackupTest">
              <system-out>[[ATTACHMENT|$relPath]]</system-out>
            </testcase>
          </testsuite>
          """
            .trimIndent()
        )

      processTestReportAggregation(
        sourceDir = sourceDir,
        xmlResultsDirectory = xmlResultsDirectory,
        modulePath = ":app",
        variantName = "debug",
        suiteName = "backupTest",
        target = device,
        logger = Logging.getLogger(TestReportAggregationUtilsTest::class.java),
        additionalProperties = mapOf("suiteType" to "androidx.test.backup"),
        allowedAttachmentRoot = attachmentsDir,
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

    assertThat(xmlResultsDir.resolve("attachments/$pixel3Hash/1/host-log.txt").readText()).isEqualTo("log-from-Pixel3")
    assertThat(xmlResultsDir.resolve("attachments/$pixel4Hash/1/host-log.txt").readText()).isEqualTo("log-from-Pixel4")
  }

  @Test
  fun processTestReportAggregation_keepsTestResultsWhenAnAttachmentCannotBeCopied() {
    val sourceDir = temporaryFolder.newFolder("results")
    val attachmentsRoot = temporaryFolder.newFolder("attachments")
    val hostLog = attachmentsRoot.resolve("host-log.txt").apply { writeText("host log") }
    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    val resultFile = sourceDir.resolve("TEST-com.example.BackupTest.xml")
    fun aggregate() =
      processTestReportAggregation(
        sourceDir = sourceDir,
        xmlResultsDirectory = directoryProperty(xmlResultsDir),
        modulePath = ":app",
        variantName = "debug",
        suiteName = "backupTest",
        target = "default",
        logger = Logging.getLogger(TestReportAggregationUtilsTest::class.java),
        allowedAttachmentRoot = attachmentsRoot,
      )

    resultFile.writeText("""<testsuite name="com.example.BackupTest" tests="0"/>""")
    aggregate()
    val hash = xmlResultsDir.listFiles()!!.single().name.removePrefix("TEST-com.example.BackupTest_").removeSuffix(".xml")
    // A file in place of the attachments folder makes the copy fail.
    xmlResultsDir.resolve("attachments/$hash").apply {
      parentFile.mkdirs()
      writeText("")
    }

    resultFile.writeText(
      "<testsuite name=\"com.example.BackupTest\" tests=\"1\">" +
        "<testcase name=\"testFlow\" classname=\"com.example.BackupTest\">" +
        "<system-out><![CDATA[before\n[[ATTACHMENT|${hostLog.absolutePath}]]\nafter\n]]></system-out>" +
        "</testcase></testsuite>"
    )
    aggregate()

    val root = parseXmlElement(xmlResultsDir.resolve("TEST-com.example.BackupTest_$hash.xml"))
    val suiteProps = XmlUtils.getSubTagsAsList(root).single { it.tagName == "properties" }.readPropertiesMap()
    assertThat(suiteProps).containsAtLeast("modulePath", ":app", "testedVariantName", "debug", "testSuiteName", "backupTest")
    val testcaseOut =
      XmlUtils.getSubTagsAsList(XmlUtils.getSubTagsAsList(root).single { it.tagName == "testcase" })
        .single { it.tagName == "system-out" }
        .textContent
    assertThat(testcaseOut).isEqualTo("before\nafter\n")
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
              <property name="androidx.report.schemaVersion" value="1"/>
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
    assertThat(testCaseProps).containsExactly("androidx.report.schemaVersion", "1")

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
                <property name="androidx.report.schemaVersion" value="1"/>
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
    assertThat(thirdTestCaseProps).containsExactly("androidx.report.schemaVersion", "1")

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

  @Test
  fun processTestReportAggregation_rewritesAttachmentsInsideNestedTestsuites() {
    val buildDir = temporaryFolder.newFolder("nestedBuild")
    val sourceDir = buildDir.resolve("test-results/testDebugBackupTest").apply { mkdirs() }
    val attachmentsRoot = buildDir.resolve("intermediates/debug/testDebugBackupTest/attachments").apply { mkdirs() }

    val nestedTestLog =
      attachmentsRoot.resolve("nestedCase/host-log.txt").apply {
        parentFile.mkdirs()
        writeText("Nested testcase host log\n")
      }
    val nestedSuiteLog =
      attachmentsRoot.resolve("nestedSuite/logcat-emulator-5554.txt").apply {
        parentFile.mkdirs()
        writeText("Nested suite logcat\n")
      }

    sourceDir
      .resolve("TEST-com.example.OuterBackupTest.xml")
      .writeText(
        """
        <testsuite name="com.example.OuterBackupTest" tests="1">
          <testsuite name="com.example.OuterBackupTest${'$'}InnerGroup" tests="1">
            <testcase name="nestedRestoreFlow" classname="com.example.OuterBackupTest${'$'}InnerGroup" time="0.4">
              <properties>
                <property name="androidx.report.schemaVersion" value="1"/>
                <property name="androidx.report.suiteType" value="androidx.test.backup"/>
              </properties>
              <system-out><![CDATA[[[ATTACHMENT|${nestedTestLog.absolutePath}]]]]></system-out>
            </testcase>
            <system-out><![CDATA[[[ATTACHMENT|${nestedSuiteLog.absolutePath}]]]]></system-out>
          </testsuite>
        </testsuite>
        """
          .trimIndent()
      )

    val xmlResultsDir = temporaryFolder.newFolder("nestedXmlResults")
    processTestReportAggregation(
      sourceDir = sourceDir,
      xmlResultsDirectory = directoryProperty(xmlResultsDir),
      modulePath = ":app",
      variantName = "debug",
      suiteName = "backupTest",
      target = "default",
      logger = Logging.getLogger(TestReportAggregationUtilsTest::class.java),
      allowedAttachmentRoot = attachmentsRoot,
    )

    val enrichedXml = xmlResultsDir.listFiles()!!.single { it.isFile && it.name.startsWith("TEST-com.example.OuterBackupTest_") }
    val hash = enrichedXml.name.removePrefix("TEST-com.example.OuterBackupTest_").removeSuffix(".xml")
    val enrichedText = enrichedXml.readText()
    assertThat(enrichedText).doesNotContain(nestedTestLog.absolutePath)
    assertThat(enrichedText).doesNotContain(nestedSuiteLog.absolutePath)
    assertThat(enrichedText).contains("[[ATTACHMENT|attachments/$hash/1/host-log.txt]]")
    assertThat(enrichedText).contains("[[ATTACHMENT|attachments/$hash/2/logcat-emulator-5554.txt]]")
    assertThat(xmlResultsDir.resolve("attachments/$hash/1/host-log.txt").readText()).isEqualTo("Nested testcase host log\n")
    assertThat(xmlResultsDir.resolve("attachments/$hash/2/logcat-emulator-5554.txt").readText()).isEqualTo("Nested suite logcat\n")
  }

  private fun parseXmlElement(file: File): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

  private fun Element.readPropertiesMap(): Map<String, String> =
    XmlUtils.getSubTagsAsList(this).filter { it.tagName == "property" }.associate { it.getAttribute("name") to it.getAttribute("value") }

  private fun directoryProperty(dir: File): DirectoryProperty =
    ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build().objects.directoryProperty().fileValue(dir)
}
