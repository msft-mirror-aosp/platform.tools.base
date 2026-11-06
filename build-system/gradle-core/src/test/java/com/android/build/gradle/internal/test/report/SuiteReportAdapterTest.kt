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

import com.android.build.gradle.internal.testsuites.backup.BackupRestoreReportAdapter
import com.android.build.gradle.internal.testsuites.screenshot.ScreenshotReportAdapter
import com.android.testutils.MockLog
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SuiteReportAdapterTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var inputDir: File
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    inputDir = temporaryFolder.newFolder("input")
    rootDir = temporaryFolder.newFolder("project_root")
  }

  @Test
  fun defaultAdapter_customSuiteNamedBackupTestAndSkippedTestsStayOnDefaultAdapter() {
    val namedBackupDir = temporaryFolder.newFolder("named_backup_input")

    File(inputDir, "TEST-JourneyTest.xml")
      .writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.example.shop.JourneyTest" tests="3" failures="0" errors="0" skipped="1" time="0.35">
          <properties>
            <property name="modulePath" value=":app"/>
            <property name="testedVariantName" value="debug"/>
            <property name="testSuiteName" value="journeySuite"/>
            <property name="testTarget" value="default"/>
            <property name="suiteType" value="com.example.journey"/>
            <property name="testLibraryVersion" value="2.4.0"/>
          </properties>
          <testcase name="testSkippedBefore" classname="com.example.shop.JourneyTest" time="0.0"><skipped/></testcase>
          <testcase name="testLocale" classname="com.example.shop.JourneyTest" time="0.11">
            <properties>
              <property name="androidx.report.schemaVersion" value="1"/>
              <property name="androidx.report.suiteType" value=" "/>
              <property name="androidx.report.attr.locale" value="en-US"/>
              <property name="androidx.report.attr.locale.label" value="Locale"/>
              <property name="androidx.report.attr.locale.displayValue" value="English (US)"/>
              <property name="androidx.report.attr.locale.filterable" value="true"/>
              <property name="androidx.report.attr.blankVal" value="   "/>
              <property name="androidx.report.actor.dev1.name" value="Pixel 8"/>
              <property name="androidx.report.actor.dev1.role" value="  "/>
              <property name="androidx.report.step.1.name" value="OpenHome"/>
              <property name="androidx.report.step.1.status" value="PASSED"/>
              <property name="androidx.report.step.1.durationMs" value="110"/>
            </properties>
          </testcase>
          <testcase name="testNetwork" classname="com.example.shop.JourneyTest" time="0.24">
            <properties>
              <property name="androidx.report.schemaVersion" value="1"/>
              <property name="androidx.report.suiteType" value="com.example.journey"/>
              <property name="androidx.report.attr.network" value="3G"/>
              <property name="androidx.report.attr.network.label" value="Network"/>
              <property name="androidx.report.attr.network.filterable" value="true"/>
              <property name="androidx.report.step.1.name" value="SyncCart"/>
              <property name="androidx.report.step.1.status" value="PASSED"/>
              <property name="androidx.report.step.1.durationMs" value="240"/>
            </properties>
          </testcase>
        </testsuite>
        """
          .trimIndent(),
        Charsets.UTF_8,
      )

    // A generic host test suite named "backupTest" without BackupAgpTestSuiteImpl metadata stays on DefaultSuiteReportAdapter.
    File(namedBackupDir, "TEST-GenericBackupNamedSuite.xml")
      .writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.example.shop.GenericBackupNamedSuite" tests="1" failures="0" errors="0" skipped="0" time="0.05">
          <properties>
            <property name="modulePath" value=":app"/>
            <property name="testedVariantName" value="debug"/>
            <property name="testSuiteName" value="backupTest"/>
            <property name="testTarget" value="default"/>
          </properties>
          <testcase name="testPlainHostLogic" classname="com.example.shop.GenericBackupNamedSuite" time="0.05"/>
        </testsuite>
        """
          .trimIndent(),
        Charsets.UTF_8,
      )

    val report = XMLReportAggregator(listOf(inputDir, namedBackupDir), "ShopProject", rootDir).generateReport()

    val journeyDescriptor = checkNotNull(report.suiteDescriptors["journeySuite"])
    assertThat(journeyDescriptor.adapterId).isEqualTo("default")
    assertThat(journeyDescriptor.suiteType).isEqualTo("com.example.journey")
    assertThat(journeyDescriptor.summaryColumns)
      .containsExactly(
        SummaryColumnDef(key = "locale", label = "Locale", filterable = true, sortable = true),
        SummaryColumnDef(key = "network", label = "Network", filterable = true, sortable = true),
      )
      .inOrder()

    val genericBackupDescriptor = checkNotNull(report.suiteDescriptors["backupTest"])
    assertThat(genericBackupDescriptor.adapterId).isEqualTo("default")
    assertThat(genericBackupDescriptor.suiteType).isNull()
    assertThat(genericBackupDescriptor.summaryColumns).isEmpty()

    val journeyModule = report.modules.single { it.testSuiteSummaries.any { s -> s.name == "journeySuite" } }
    val localeCase =
      journeyModule.packages
        .single { it.classes.any { c -> c.name == "JourneyTest" } }
        .classes
        .single { it.name == "JourneyTest" }
        .testCases
        .single { it.name == "testLocale" }
    val localeResult = localeCase.targets.single().testSuiteResults.single().variantResults["debug"]!!
    assertThat(localeResult.adapterId).isEqualTo("default")
    assertThat(localeResult.suiteType).isEqualTo("com.example.journey")
    assertThat(localeResult.actors?.single()).isEqualTo(ActorInfo(id = "dev1", name = "Pixel 8"))
    assertThat(localeResult.steps?.single()).isEqualTo(StepInfo(n = 1, name = "OpenHome", status = "PASSED", durationMs = 110L))
  }

  @Test
  fun defaultAdapter_duplicateSummaryAttributeKeysKeepLastEntryConsistently() {
    val detailRecord =
      TestDetailRecord(
        schemaVersion = 1,
        suiteType = "com.example.journey",
        summaryAttributes =
          listOf(
            SummaryAttributeRecord(key = "locale", label = "Old Locale", value = "en", filterable = true),
            SummaryAttributeRecord(key = "locale", label = "Locale", value = "en-US", filterable = true),
            SummaryAttributeRecord(key = "seed", label = "Seed", value = "1", filterable = true),
            SummaryAttributeRecord(key = "seed", label = "Seed", value = "2", filterable = false),
          ),
      )
    val adapted = DefaultSuiteReportAdapter.adapt(SuiteAdapterContext(rawProperties = emptyMap(), detailRecord = detailRecord))

    assertThat(adapted.result.summaryAttributes)
      .containsExactly(
        "locale",
        SummaryAttributeValue(value = "en-US", displayValue = "en-US", label = "Locale"),
        "seed",
        SummaryAttributeValue(value = "2", displayValue = "2", label = "Seed"),
      )
    assertThat(adapted.suiteDescriptor.summaryColumns)
      .containsExactly(SummaryColumnDef(key = "locale", label = "Locale", filterable = true, sortable = true))
  }

  @Test
  fun adapters_keepEntriesTheTestPublishedEvenWhenNamedLikeSuiteMetadata() {
    // AGP adds suite metadata only to <testsuite>, so test-level entries with the same names come from the test.
    val rawProperties =
      linkedMapOf("runner" to "custom-runner", "allowBackup" to "false", "androidx.report.schemaVersion" to "1", "owner" to "team")
    val context = SuiteAdapterContext(rawProperties = rawProperties)

    for (adapter in listOf(DefaultSuiteReportAdapter, BackupRestoreReportAdapter, ScreenshotReportAdapter)) {
      assertThat(adapter.adapt(context).result.properties)
        .containsExactly("runner", "custom-runner", "allowBackup", "false", "owner", "team")
        .inOrder()
    }
  }

  @Test
  fun detailRecordReader_ordersNumberedStepsByNumberAndOtherwiseKeepsPublishedOrder() {
    fun stepNames(vararg keys: String): List<String>? {
      val props = linkedMapOf("androidx.report.schemaVersion" to "1")
      for (key in keys) {
        props["androidx.report.step.$key.name"] = "Step $key"
        props["androidx.report.step.$key.status"] = "PASSED"
      }
      val record = checkNotNull(TestDetailRecordReader.parseDetailRecordFromEntries(rawProperties = props, logger = MockLog()))
      return record.steps?.map { it.name }
    }

    assertThat(stepNames("2", "10", "1")).containsExactly("Step 1", "Step 2", "Step 10").inOrder()
    assertThat(stepNames("prepare_1", "1", "2", "cleanup_1"))
      .containsExactly("Step prepare_1", "Step 1", "Step 2", "Step cleanup_1")
      .inOrder()
  }

  @Test
  fun detailRecordReader_preservesMetadataForSanitizedAttachmentFileNames() {
    val props =
      linkedMapOf(
        "androidx.report.schemaVersion" to "1",
        "androidx.report.file.logcat-127.0.0.1:6520.txt.name" to "Logcat (source)",
        "androidx.report.file.logcat-127.0.0.1:6520.txt.truncated" to "true",
        "androidx.report.file.snapshot (1).backup.name" to "Cloud backup archive",
        "androidx.report.file.snapshot (1).backup.values" to "size=4.2 KB,entries=1,234, mode=cloud",
      )
    val attachments =
      listOf(
        "attachments/hash123/1/logcat-127.0.0.1_6520.txt",
        "attachments/hash123/2/snapshot__1_.backup",
      )

    val record =
      checkNotNull(
        TestDetailRecordReader.parseDetailRecordFromEntries(
          rawProperties = props,
          attachments = attachments,
          logger = MockLog(),
        )
      )
    assertThat(record.logs)
      .containsExactly(
        LogRecord(
          title = "Logcat (source)",
          path = "attachments/hash123/1/logcat-127.0.0.1_6520.txt",
          truncated = true,
        )
      )
    assertThat(record.artifacts)
      .containsExactly(
        ArtifactRecord(
          name = "Cloud backup archive",
          values = mapOf("size" to "4.2 KB", "entries" to "1,234", "mode" to "cloud"),
          path = "attachments/hash123/2/snapshot__1_.backup",
        )
      )
  }

  @Test
  fun backupRestoreAdapter_deduplicatesCollidingSanitizedEnvironmentRuleFileNames() {
    val adapted =
      BackupRestoreReportAdapter.adaptEnvironment(
        envId = "env_01",
        record =
          SuiteEnvironmentRecord(
            extraProperties =
              mapOf(
                "allowBackup" to "true",
                "fullBackupContent" to "@xml/rules_v1",
                "fullBackupContentFile" to "rules:1.xml",
                "fullBackupContentXml" to "<full-backup-content/>",
                "dataExtractionRules" to "@xml/rules_v2",
                "dataExtractionRulesFile" to "rules?1.xml",
                "dataExtractionRulesXml" to "<data-extraction-rules/>",
              )
          ),
      )

    assertThat(adapted.generatedFiles)
      .containsExactly(
        GeneratedTextFileSpec(relativePath = "artifacts/env_01/rules_1.xml", content = "<full-backup-content/>"),
        GeneratedTextFileSpec(relativePath = "artifacts/env_01/rules_1_2.xml", content = "<data-extraction-rules/>"),
      )
      .inOrder()
    assertThat(adapted.configFiles?.map { it.artifactPath })
      .containsExactly("artifacts/env_01/rules_1.xml", "artifacts/env_01/rules_1_2.xml")
      .inOrder()
    assertThat(adapted.lazyFiles.map { it.artifactPath })
      .containsExactly("artifacts/env_01/rules_1.xml", "artifacts/env_01/rules_1_2.xml")
      .inOrder()
  }
}
