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

package com.android.build.gradle.internal.testsuites.backup

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.file.FileSystemLocation
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupManifestRulesExtractorTest {

  @get:Rule val folder = TemporaryFolder()

  @Test
  fun extract_parsesAllowBackupAndMapsEngineAndReportProperties() {
    val manifestFile = folder.newFile("AndroidManifest.xml")
    manifestFile.writeText(
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.journalingapp">
          <uses-sdk android:targetSdkVersion="33" />
          <application
              android:allowBackup="true"
              android:fullBackupContent="@xml/backup_rules"
              android:dataExtractionRules="@xml/data_extraction_rules" />
      </manifest>
      """
        .trimIndent()
    )

    val debugRes = folder.newFolder("debugRes")
    val mainRes = folder.newFolder("mainRes")
    mainRes.resolve("xml").apply {
      mkdirs()
      resolve("backup_rules.xml").writeText("<full-backup-content><include domain=\"file\" path=\".\"/></full-backup-content>")
      resolve("data_extraction_rules.xml").writeText("<data-extraction-rules><cloud-backup/></data-extraction-rules>")
    }
    debugRes.resolve("xml").apply {
      mkdirs()
      resolve("data_extraction_rules.xml")
        .writeText(
          "<data-extraction-rules><device-transfer><exclude domain=\"sharedpref\" path=\"user_session.xml\"/></device-transfer></data-extraction-rules>"
        )
    }

    val extracted = BackupManifestRulesExtractor.extract(manifestFile, listOf(debugRes, mainRes))
    assertThat(extracted).isNotNull()

    assertThat(BackupManifestRulesExtractor.toEngineInputProperties(extracted))
      .containsExactly(
        "com.android.agp.test.TARGET_SDK_VERSION",
        "33",
        "com.android.agp.test.ALLOW_BACKUP",
        "true",
        "com.android.agp.test.FULL_BACKUP_CONTENT",
        "@xml/backup_rules",
        "com.android.agp.test.FULL_BACKUP_CONTENT_FILE",
        "backup_rules.xml",
        "com.android.agp.test.FULL_BACKUP_CONTENT_XML",
        "<full-backup-content><include domain=\"file\" path=\".\"/></full-backup-content>",
        "com.android.agp.test.DATA_EXTRACTION_RULES",
        "@xml/data_extraction_rules",
        "com.android.agp.test.DATA_EXTRACTION_RULES_FILE",
        "data_extraction_rules.xml",
        "com.android.agp.test.DATA_EXTRACTION_RULES_XML",
        "<data-extraction-rules><device-transfer><exclude domain=\"sharedpref\" path=\"user_session.xml\"/></device-transfer></data-extraction-rules>",
      )

    assertThat(BackupManifestRulesExtractor.toSuiteReportProperties(extracted))
      .containsExactly(
        "allowBackup",
        "true",
        "fullBackupContent",
        "@xml/backup_rules",
        "fullBackupContentFile",
        "backup_rules.xml",
        "fullBackupContentXml",
        "<full-backup-content><include domain=\"file\" path=\".\"/></full-backup-content>",
        "dataExtractionRules",
        "@xml/data_extraction_rules",
        "dataExtractionRulesFile",
        "data_extraction_rules.xml",
        "dataExtractionRulesXml",
        "<data-extraction-rules><device-transfer><exclude domain=\"sharedpref\" path=\"user_session.xml\"/></device-transfer></data-extraction-rules>",
      )
  }

  @Test
  fun extract_defaultsAllowBackupToTrueWhenOmittedOnApplicationElement() {
    val manifestFile = folder.newFile("AndroidManifestDefaultBackup.xml")
    manifestFile.writeText(
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
          <uses-sdk android:targetSdkVersion="34" />
          <application />
      </manifest>
      """
        .trimIndent()
    )

    val extracted = BackupManifestRulesExtractor.extract(manifestFile, emptyList())
    assertThat(extracted?.allowBackup).isTrue()
    assertThat(extracted?.targetSdkVersion).isEqualTo(34)
  }

  @Test
  fun resolveXmlRuleResource_selectsHighestEligibleApiQualifierAndIgnoresNonVersionQualifiers() {
    val resDir = folder.newFolder("res")
    fun writeRule(folderName: String, tag: String) {
      resDir.resolve(folderName).apply {
        mkdirs()
        resolve("data_extraction_rules.xml").writeText("<$tag/>")
      }
    }
    writeRule("xml", "default-xml")
    writeRule("xml-v9", "v9-xml")
    writeRule("xml-v26", "v26-xml")
    writeRule("xml-v31", "v31-xml")
    writeRule("xml-v35", "v35-xml")
    writeRule("xml-land", "land-xml")
    writeRule("xml-night-v33", "night-v33-xml")
    writeRule("xml-not-a-valid-qualifier", "bogus-xml")

    val (file33, xml33) =
      BackupManifestRulesExtractor.resolveXmlRuleResource("@xml/data_extraction_rules", listOf(resDir), targetSdkVersion = 33)
    assertThat(file33).isEqualTo("data_extraction_rules.xml")
    assertThat(xml33).isEqualTo("<v31-xml/>")

    val (_, xml8) = BackupManifestRulesExtractor.resolveXmlRuleResource("@xml/data_extraction_rules", listOf(resDir), targetSdkVersion = 8)
    assertThat(xml8).isEqualTo("<default-xml/>")

    val (_, xmlUnbounded) =
      BackupManifestRulesExtractor.resolveXmlRuleResource("@xml/data_extraction_rules", listOf(resDir), targetSdkVersion = null)
    assertThat(xmlUnbounded).isEqualTo("<v35-xml/>")
  }

  @Test
  fun resolveXmlRuleResource_picksBestQualifierAcrossOverlaysAndLetsHigherPriorityOverlayWinTheSameBucket() {
    fun resDir(name: String, vararg rules: Pair<String, String>): File {
      val dir = folder.newFolder(name)
      for ((folderName, tag) in rules) {
        dir.resolve(folderName).apply {
          mkdirs()
          resolve("backup_rules.xml").writeText("<$tag/>")
        }
      }
      return dir
    }
    val debugRes = resDir("debug", "xml" to "debug-default", "xml-v26" to "debug-v26")
    val mainRes = resDir("main", "xml" to "main-default", "xml-v26" to "main-v26", "xml-v31" to "main-v31")

    val (_, xml33) =
      BackupManifestRulesExtractor.resolveXmlRuleResource("@xml/backup_rules", listOf(debugRes, mainRes), targetSdkVersion = 33)
    assertThat(xml33).isEqualTo("<main-v31/>")

    val (_, xml28) =
      BackupManifestRulesExtractor.resolveXmlRuleResource("@xml/backup_rules", listOf(debugRes, mainRes), targetSdkVersion = 28)
    assertThat(xml28).isEqualTo("<debug-v26/>")

    val (_, xml21) =
      BackupManifestRulesExtractor.resolveXmlRuleResource("@xml/backup_rules", listOf(debugRes, mainRes), targetSdkVersion = 21)
    assertThat(xml21).isEqualTo("<debug-default/>")
  }

  @Test
  fun resolveXmlRuleResource_rejectsPathTraversalAndNonXmlReferences() {
    val resDir = folder.newFolder("res_safe")
    assertThat(BackupManifestRulesExtractor.resolveXmlRuleResource("@xml/../secret", listOf(resDir))).isEqualTo(null to null)
    assertThat(BackupManifestRulesExtractor.resolveXmlRuleResource("@xml/nested/rules", listOf(resDir))).isEqualTo(null to null)
    assertThat(BackupManifestRulesExtractor.resolveXmlRuleResource("@string/app_name", listOf(resDir))).isEqualTo(null to null)
    assertThat(BackupManifestRulesExtractor.resolveXmlRuleResource("@xml/missing_rules", listOf(resDir)))
      .isEqualTo("missing_rules.xml" to null)
  }

  @Test
  fun resolveXmlRuleResource_leavesOutRulesFileThatCannotBeRead() {
    val resDir = folder.newFolder("res_unreadable")
    val rulesFile =
      resDir.resolve("xml/backup_rules.xml").apply {
        parentFile.mkdirs()
        writeText("<full-backup-content/>")
        setReadable(false)
      }
    assumeFalse("File permissions don't apply to this user", rulesFile.canRead())
    assertThat(BackupManifestRulesExtractor.resolveXmlRuleResource("@xml/backup_rules", listOf(resDir)))
      .isEqualTo("backup_rules.xml" to null)
  }

  @Test
  fun backupRulesMemoizer_cachesRepeatedQueriesAndInvalidatesWhenManifestOrRuleXmlChanges() {
    val manifestFile = folder.newFile("MemoizedManifest.xml")
    val resDir = folder.newFolder("memoizedRes")
    val rulesFile =
      resDir.resolve("xml/backup_rules.xml").apply {
        parentFile.mkdirs()
        writeText("<full-backup-content><include domain=\"file\" path=\"initial\"/></full-backup-content>")
      }
    manifestFile.writeText(
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
        <application android:allowBackup="false" android:fullBackupContent="@xml/backup_rules"/>
      </manifest>
      """
        .trimIndent()
    )
    val resLocations = setOf(FileSystemLocation { resDir })

    var extractCount = 0
    val memoizer = BackupRulesMemoizer { file, dirs ->
      extractCount++
      BackupManifestRulesExtractor.extract(file, dirs)
    }

    val first = memoizer.get(manifestFile.absolutePath, resLocations)
    val second = memoizer.get(manifestFile.absolutePath, resLocations)
    val third = memoizer.get(manifestFile.absolutePath, resLocations)
    assertThat(extractCount).isEqualTo(1)
    assertThat(first.allowBackup).isFalse()
    assertThat(first.fullBackupContentXml).contains("path=\"initial\"")
    assertThat(second).isSameInstanceAs(first)
    assertThat(third).isSameInstanceAs(first)

    rulesFile.writeText("<full-backup-content><exclude domain=\"sharedpref\" path=\"updated_secret.xml\"/></full-backup-content>")
    val afterRuleEdit = memoizer.get(manifestFile.absolutePath, resLocations)
    assertThat(extractCount).isEqualTo(2)
    assertThat(afterRuleEdit.fullBackupContentXml).contains("path=\"updated_secret.xml\"")

    manifestFile.writeText(
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
        <uses-sdk android:targetSdkVersion="34" />
        <application android:allowBackup="true" android:fullBackupContent="@xml/backup_rules"/>
      </manifest>
      """
        .trimIndent()
    )
    val updated = memoizer.get(manifestFile.absolutePath, resLocations)
    assertThat(extractCount).isEqualTo(3)
    assertThat(updated.allowBackup).isTrue()
    assertThat(updated.targetSdkVersion).isEqualTo(34)
    assertThat(updated.fullBackupContentXml).contains("path=\"updated_secret.xml\"")
  }
}
