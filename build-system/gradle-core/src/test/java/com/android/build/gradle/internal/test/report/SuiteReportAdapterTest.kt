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
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SuiteReportAdapterTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private val gson = Gson()
  private lateinit var inputDir: File
  private lateinit var outputDir: File
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    inputDir = temporaryFolder.newFolder("input")
    outputDir = temporaryFolder.newFolder("output")
    rootDir = temporaryFolder.newFolder("project_root")
  }

  @Test
  fun screenshotAdapter_preservesAllFieldsWithoutCopyingImageFiles() {
    val xml =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.ui.PreviewTest" tests="1" failures="1" errors="0" skipped="0" time="0.42">
        <properties>
          <property name="modulePath" value=":app"/>
          <property name="testedVariantName" value="debug"/>
          <property name="testSuiteName" value="screenshotTest"/>
          <property name="testTarget" value="default"/>
        </properties>
        <testcase name="cardPreview" classname="com.example.ui.PreviewTest" time="0.42">
          <properties>
            <property name="PreviewScreenshot.diffPercent" value="1.25"/>
            <property name="PreviewScreenshot.previewName" value="CardDark"/>
            <property name="PreviewScreenshot.methodName" value="CardPreviewComposable"/>
            <property name="PreviewScreenshot.refImagePath" value="src/screenshotTestDebug/reference/card.png"/>
            <property name="PreviewScreenshot.newImagePath" value="build/outputs/screenshotTest/rendered/card.png"/>
            <property name="PreviewScreenshot.diffImagePath" value="build/outputs/screenshotTest/diff/card.png"/>
          </properties>
          <failure message="Image mismatch">Image mismatch by 1.25%</failure>
        </testcase>
      </testsuite>
      """
        .trimIndent()
    File(inputDir, "TEST-PreviewTest_a1b2c3.xml").writeText(xml, Charsets.UTF_8)

    val aggregator = XMLReportAggregator(listOf(inputDir), "ScreenshotApp", rootDir)
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    assertThat(report.suiteDescriptors["screenshotTest"]).isEqualTo(SuiteDescriptor(adapterId = "screenshot"))

    val result =
      report.modules
        .single()
        .packages
        .single()
        .classes
        .single()
        .testCases
        .single()
        .targets
        .single()
        .testSuiteResults
        .single()
        .variantResults["debug"]!!

    assertThat(result.adapterId).isEqualTo("screenshot")
    assertThat(result.durationMs).isEqualTo(420L)
    assertThat(result.diffPercent).isEqualTo("1.25")
    assertThat(result.previewName).isEqualTo("CardDark")
    assertThat(result.methodName).isEqualTo("CardPreviewComposable")
    assertThat(result.refImagePath).isEqualTo("src/screenshotTestDebug/reference/card.png")
    assertThat(result.newImagePath).isEqualTo("build/outputs/screenshotTest/rendered/card.png")
    assertThat(result.diffImagePath).isEqualTo("build/outputs/screenshotTest/diff/card.png")
    assertThat(result.properties).isNull()
    assertThat(result.detailId).isNull()
    assertThat(File(outputDir, "artifacts").exists()).isFalse()
    assertThat(File(outputDir, "details").exists()).isFalse()
  }

  @Test
  fun backupRestoreAdapter_aggregatesEntriesEnvironmentRulesLogsAndSelfContainedAttachments() {
    val hash = "0123456789abcdef0123456789abcdef"
    val fullBackupXml = "<full-backup-content>\n  <include domain=\"sharedpref\" path=\".\"/>\n</full-backup-content>"
    val extractionRulesXml =
      "<data-extraction-rules>\n  <device-transfer>\n    <exclude domain=\"sharedpref\" path=\"user_session.xml\"/>\n  </device-transfer>\n</data-extraction-rules>"

    val hostLogDir = File(inputDir, "attachments/$hash/1").apply { mkdirs() }
    File(hostLogDir, "host-log.txt").writeText("INFO: Starting D2D backup\nINFO: Restore finished\nSEVERE: Token missing\n", Charsets.UTF_8)
    val logcatDir = File(inputDir, "attachments/$hash/2").apply { mkdirs() }
    File(logcatDir, "logcat-emulator-5556.txt")
      .writeText("10-30 08:00:01.000 I/BackupManager: Restore complete\n10-30 08:00:02.000 E/AppVerify: auth_token=null", Charsets.UTF_8)
    val archiveDir = File(inputDir, "attachments/$hash/3").apply { mkdirs() }
    File(archiveDir, "d2d_snapshot.backup").writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))

    fun xmlAttr(raw: String): String =
      raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "&#10;")

    File(inputDir, "TEST-CrossDeviceMigrationTests_$hash.xml")
      .writeText(
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<testsuite name="com.example.journalingapp.CrossDeviceMigrationTests" tests="1" failures="1" errors="0" skipped="0" time="41.42">
        |  <properties>
        |    <property name="modulePath" value=":app"/>
        |    <property name="testedVariantName" value="debug"/>
        |    <property name="testSuiteName" value="backupTest"/>
        |    <property name="testTarget" value="default"/>
        |    <property name="suiteType" value="androidx.test.backup"/>
        |    <property name="applicationId" value="com.example.journalingapp"/>
        |    <property name="applicationName" value="JournalingApp"/>
        |    <property name="versionName" value="1.2.0"/>
        |    <property name="versionCode" value="12"/>
        |    <property name="targetSdkVersion" value="34"/>
        |    <property name="agpVersion" value="9.6.0-dev"/>
        |    <property name="gradleVersion" value="9.9.0"/>
        |    <property name="testLibraryVersion" value="1.0.0-beta01"/>
        |    <property name="runner" value="junit-jupiter"/>
        |    <property name="allowBackup" value="true"/>
        |    <property name="fullBackupContent" value="@xml/backup_rules"/>
        |    <property name="fullBackupContentFile" value="backup_rules.xml"/>
        |    <property name="fullBackupContentXml" value="${xmlAttr(fullBackupXml)}"/>
        |    <property name="dataExtractionRules" value="@xml/data_extraction_rules"/>
        |    <property name="dataExtractionRulesFile" value="data_extraction_rules.xml"/>
        |    <property name="dataExtractionRulesXml" value="${xmlAttr(extractionRulesXml)}"/>
        |  </properties>
        |  <testcase name="testSessionTokenRestored()" classname="com.example.journalingapp.CrossDeviceMigrationTests" time="41.42">
        |    <properties>
        |      <property name="androidx.report.schemaVersion" value="1"/>
        |      <property name="androidx.report.suiteType" value="androidx.test.backup"/>
        |      <property name="androidx.report.source" value="CrossDeviceMigrationTests.kt:47"/>
        |      <property name="androidx.report.attr.transport" value="DEVICE_TO_DEVICE"/>
        |      <property name="androidx.report.attr.transport.label" value="Transport"/>
        |      <property name="androidx.report.attr.transport.displayValue" value="DEVICE_TO_DEVICE"/>
        |      <property name="androidx.report.attr.transport.filterable" value="true"/>
        |      <property name="androidx.report.attr.devices" value="Pixel 6 (API 31) -&gt; Pixel 8 (API 34)"/>
        |      <property name="androidx.report.attr.devices.label" value="Devices"/>
        |      <property name="androidx.report.attr.devices.filterable" value="true"/>
        |      <property name="androidx.report.callout.kind" value="ASSERTION_FAILURE"/>
        |      <property name="androidx.report.callout.failedStep" value="5"/>
        |      <property name="androidx.report.callout.title" value="Assertion failed at step 5 · Verify"/>
        |      <property name="androidx.report.callout.message" value="expected auth_token to be restored on target but was null"/>
        |      <property name="androidx.report.callout.likelyCause" value="data_extraction_rules.xml excludes domain=&quot;sharedpref&quot; path=&quot;user_session.xml&quot; for &lt;device-transfer&gt;."/>
        |      <property name="androidx.report.actor.source.role" value="SOURCE"/>
        |      <property name="androidx.report.actor.source.name" value="Pixel 6"/>
        |      <property name="androidx.report.actor.source.api" value="31"/>
        |      <property name="androidx.report.actor.source.serial" value="emulator-5554"/>
        |      <property name="androidx.report.actor.source.image" value="Google APIs"/>
        |      <property name="androidx.report.actor.source.playServices" value="26.39.33"/>
        |      <property name="androidx.report.actor.target.role" value="TARGET"/>
        |      <property name="androidx.report.actor.target.name" value="Pixel 8"/>
        |      <property name="androidx.report.actor.target.api" value="34"/>
        |      <property name="androidx.report.actor.target.serial" value="emulator-5556"/>
        |      <property name="androidx.report.actor.target.image" value="Google APIs"/>
        |      <property name="androidx.report.actor.target.playServices" value="26.39.33"/>
        |      <property name="androidx.report.step.prepare_1.name" value="Prepare"/>
        |      <property name="androidx.report.step.prepare_1.on" value="source"/>
        |      <property name="androidx.report.step.prepare_1.shared" value="false"/>
        |      <property name="androidx.report.step.prepare_1.call" value="Install APKs, unlock device, clear app data"/>
        |      <property name="androidx.report.step.prepare_1.durationMs" value="820"/>
        |      <property name="androidx.report.step.prepare_1.status" value="PASSED"/>
        |      <property name="androidx.report.step.1.name" value="Setup"/>
        |      <property name="androidx.report.step.1.on" value="source"/>
        |      <property name="androidx.report.step.1.shared" value="true"/>
        |      <property name="androidx.report.step.1.call" value="seedSharedFixtures()"/>
        |      <property name="androidx.report.step.1.durationMs" value="510"/>
        |      <property name="androidx.report.step.1.status" value="PASSED"/>
        |      <property name="androidx.report.step.5.name" value="Verify"/>
        |      <property name="androidx.report.step.5.on" value="target"/>
        |      <property name="androidx.report.step.5.shared" value="false"/>
        |      <property name="androidx.report.step.5.call" value="runOnDevice(&quot;VerifySession&quot;)"/>
        |      <property name="androidx.report.step.5.durationMs" value="980"/>
        |      <property name="androidx.report.step.5.status" value="FAILED"/>
        |      <property name="androidx.report.step.6.name" value="Cleanup"/>
        |      <property name="androidx.report.step.6.on" value="target"/>
        |      <property name="androidx.report.step.6.shared" value="false"/>
        |      <property name="androidx.report.step.6.call" value="postVerify()"/>
        |      <property name="androidx.report.step.6.durationMs" value="0"/>
        |      <property name="androidx.report.step.6.status" value="SKIPPED"/>
        |      <property name="androidx.report.artifact.1.name" value="Backup file summary"/>
        |      <property name="androidx.report.artifact.1.values" value="app_backup=1.8 MB, pm_backup=3.1 KB"/>
        |      <property name="androidx.report.file.d2d_snapshot.backup.name" value="Raw backup archive"/>
        |      <property name="BackupRestore.transportMode" value="DEVICE_TO_DEVICE"/>
        |      <property name="BackupRestore.status" value="FAILURE"/>
        |      <property name="customUserNote" value="d2d-regression"/>
        |    </properties>
        |    <failure message="auth_token was null">org.opentest4j.AssertionFailedError: expected auth_token</failure>
        |    <system-out><![CDATA[test stdout line
        |[[ATTACHMENT|attachments/$hash/1/host-log.txt]]
        |[[ATTACHMENT|attachments/$hash/2/logcat-emulator-5556.txt]]
        |[[ATTACHMENT|attachments/$hash/3/d2d_snapshot.backup]]
        |]]></system-out>
        |  </testcase>
        |</testsuite>
        """
          .trimMargin(),
        Charsets.UTF_8,
      )

    val aggregator = XMLReportAggregator(listOf(inputDir), "JournalingProject", rootDir)
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    val descriptor = checkNotNull(report.suiteDescriptors["backupTest"])
    assertThat(descriptor.adapterId).isEqualTo("backupRestore")
    assertThat(descriptor.suiteType).isEqualTo("androidx.test.backup")
    assertThat(descriptor.title).isEqualTo("Backup & Restore")
    assertThat(descriptor.likelyCauseHeading).isEqualTo("Backup rule that left the data out · Likely cause")
    assertThat(descriptor.docsUrl).isEqualTo("https://developer.android.com/identity/data/testing")
    assertThat(descriptor.summaryColumns)
      .containsExactly(
        SummaryColumnDef(
          key = "transport",
          label = "Transport",
          filterable = true,
          sortable = true,
          tooltip = "Android backup transport mode used by the test",
        ),
        SummaryColumnDef(
          key = "devices",
          label = "Devices",
          filterable = true,
          sortable = true,
          tooltip = "Source and target Android devices used by the test",
        ),
      )
      .inOrder()

    assertThat(report.suiteEnvironments).hasSize(1)
    val env = report.suiteEnvironments.single()
    assertThat(env.envId).isEqualTo("env_01")
    assertThat(env.suiteType).isEqualTo("androidx.test.backup")
    assertThat(env.applicationId).isEqualTo("com.example.journalingapp")
    assertThat(env.applicationName).isEqualTo("JournalingApp")
    assertThat(env.versionName).isEqualTo("1.2.0")
    assertThat(env.versionCode).isEqualTo(12)
    assertThat(env.targetSdkVersion).isEqualTo(34)
    assertThat(env.testLibraryVersion).isEqualTo("1.0.0-beta01")
    assertThat(env.attributes).containsExactly("allowBackup", "true")
    assertThat(env.configFiles)
      .containsExactly(
        EnvironmentConfigFile(
          key = "fullBackupContent",
          label = "backup_rules.xml",
          reference = "@xml/backup_rules",
          artifactPath = "artifacts/env_01/backup_rules.xml",
        ),
        EnvironmentConfigFile(
          key = "dataExtractionRules",
          label = "data_extraction_rules.xml",
          reference = "@xml/data_extraction_rules",
          artifactPath = "artifacts/env_01/data_extraction_rules.xml",
        ),
      )
      .inOrder()
    assertThat(File(outputDir, "artifacts/env_01/backup_rules.xml").readText(Charsets.UTF_8)).isEqualTo(fullBackupXml)
    assertThat(File(outputDir, "artifacts/env_01/data_extraction_rules.xml").readText(Charsets.UTF_8)).isEqualTo(extractionRulesXml)

    val result =
      report.modules
        .single()
        .packages
        .single()
        .classes
        .single()
        .testCases
        .single()
        .targets
        .single()
        .testSuiteResults
        .single()
        .variantResults["debug"]!!

    assertThat(result.status).isEqualTo("fail")
    assertThat(result.durationMs).isEqualTo(41420L)
    assertThat(result.adapterId).isEqualTo("backupRestore")
    assertThat(result.suiteType).isEqualTo("androidx.test.backup")
    assertThat(result.envId).isEqualTo("env_01")
    assertThat(result.detailId).isEqualTo("t_0001")
    val detailId = result.detailId!!

    assertThat(result.properties).containsExactly("customUserNote", "d2d-regression")
    assertThat(result.summaryAttributes)
      .containsExactly(
        "transport",
        SummaryAttributeValue("DEVICE_TO_DEVICE", "Device to device", "Transport"),
        "devices",
        SummaryAttributeValue("Pixel 6 (API 31) -> Pixel 8 (API 34)", "Pixel 6 (API 31) -> Pixel 8 (API 34)", "Devices"),
      )
    assertThat(result.sourceLocation)
      .isEqualTo(
        SourceLocationInfo(
          file = "CrossDeviceMigrationTests.kt",
          line = 47,
          className = "com.example.journalingapp.CrossDeviceMigrationTests",
          methodName = "testSessionTokenRestored",
        )
      )
    assertThat(result.callout?.failedStep).isEqualTo(5)
    assertThat(result.callout?.likelyCause).contains("data_extraction_rules.xml")
    assertThat(result.actors).hasSize(2)
    assertThat(result.actors!![0].playServices).isEqualTo("26.39.33")
    assertThat(result.steps).hasSize(4)
    assertThat(result.steps!![0].n).isNull()
    assertThat(result.steps[1].shared).isTrue()
    assertThat(result.steps[3].status).isEqualTo("SKIPPED")

    assertThat(result.artifacts).hasSize(2)
    assertThat(result.artifacts!![0])
      .isEqualTo(ArtifactInfo(name = "Backup file summary", values = mapOf("app_backup" to "1.8 MB", "pm_backup" to "3.1 KB"), path = null))
    assertThat(result.artifacts[1])
      .isEqualTo(
        ArtifactInfo(
          name = "Raw backup archive",
          values = mapOf("size" to "4 B"),
          path = "artifacts/$detailId/d2d_snapshot.backup",
        )
      )
    assertThat(File(outputDir, "artifacts/$detailId/d2d_snapshot.backup").readBytes()).isEqualTo(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
    assertThat(File(outputDir, "artifacts/$detailId/host-log.txt").exists()).isTrue()
    assertThat(File(outputDir, "artifacts/$detailId/logcat-emulator-5556.txt").exists()).isTrue()

    assertThat(result.logMeta)
      .containsExactly(
        LogMetaInfo(title = "Host log", lineCount = 3, artifactPath = "artifacts/$detailId/host-log.txt"),
        LogMetaInfo(title = "Logcat (target)", lineCount = 2, artifactPath = "artifacts/$detailId/logcat-emulator-5556.txt"),
      )
      .inOrder()

    // Parse data.js JSON model to verify log text and inline backup rule XML strings are absent from data.js.
    val dataJsJson = readReportDataJsJson(outputDir)
    val envJson = dataJsJson.getAsJsonArray("suiteEnvironments")[0].asJsonObject
    assertThat(envJson.has("backupRules")).isFalse()
    assertThat(envJson.getAsJsonObject("attributes").get("allowBackup").asString).isEqualTo("true")
    assertThat(envJson.getAsJsonArray("configFiles")).hasSize(2)
    assertThat(envJson.toString()).doesNotContain("fullBackupContentXml")
    assertThat(envJson.toString()).doesNotContain("dataExtractionRulesXml")
    assertThat(dataJsJson.toString()).doesNotContain("SEVERE: Token missing")
    assertThat(dataJsJson.toString()).doesNotContain("full-backup-content")

    // Verify lazy per-test details/<detailId>.js carries logs and stripped stdout without [[ATTACHMENT|...]] markers.
    val detailJsJson = readLazyDetailJsonObject(outputDir, detailId)
    assertThat(detailJsJson.has("backupRules")).isFalse()
    val lazyDetail = readLazyTestDetailJs(outputDir, detailId, gson)
    assertThat(lazyDetail.systemOut).isEqualTo("test stdout line\n")
    assertThat(lazyDetail.logs).hasSize(2)
    assertThat(lazyDetail.logs[0].title).isEqualTo("Host log")
    assertThat(lazyDetail.logs[0].lineCount).isEqualTo(3)
    assertThat(lazyDetail.logs[0].capturedTruncated).isFalse()
    assertThat(lazyDetail.logs[0].previewTruncated).isFalse()
    assertThat(lazyDetail.logs[0].content).contains("SEVERE: Token missing")

    // Verify backup rules XML is emitted once per environment in details/env_01.js.
    val lazyEnv = readLazyEnvironmentDetailJs(outputDir, "env_01", gson)
    assertThat(lazyEnv.files)
      .containsExactly(
        LazyEnvironmentFile(
          key = "fullBackupContent",
          label = "backup_rules.xml",
          reference = "@xml/backup_rules",
          artifactPath = "artifacts/env_01/backup_rules.xml",
          content = fullBackupXml,
        ),
        LazyEnvironmentFile(
          key = "dataExtractionRules",
          label = "data_extraction_rules.xml",
          reference = "@xml/data_extraction_rules",
          artifactPath = "artifacts/env_01/data_extraction_rules.xml",
          content = extractionRulesXml,
        ),
      )
      .inOrder()
  }

  @Test
  fun defaultAdapter_customSuiteDerivesDynamicSummaryColumnsAndSkipsEmptyEnvDetail() {
    File(inputDir, "TEST-CheckoutJourneyTest.xml")
      .writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.example.shop.CheckoutJourneyTest" tests="1" failures="0" errors="0" skipped="0" time="0.32">
          <properties>
            <property name="modulePath" value=":app"/>
            <property name="testedVariantName" value="debug"/>
            <property name="testSuiteName" value="journeyTest"/>
            <property name="testTarget" value="default"/>
            <property name="applicationId" value="com.example.shop"/>
            <property name="allowBackup" value="true"/>
          </properties>
          <testcase name="completeCheckout" classname="com.example.shop.CheckoutJourneyTest" time="0.32">
            <properties>
              <property name="androidx.report.schemaVersion" value="1"/>
              <property name="androidx.report.suiteType" value="com.example.journey"/>
              <property name="androidx.report.attr.locale" value="en-US"/>
              <property name="androidx.report.attr.locale.label" value="Locale"/>
              <property name="androidx.report.attr.locale.displayValue" value="English (US)"/>
              <property name="androidx.report.attr.locale.filterable" value="true"/>
              <property name="androidx.report.attr.network" value="3G"/>
              <property name="androidx.report.attr.network.label" value="Network"/>
              <property name="androidx.report.attr.network.filterable" value="true"/>
              <property name="androidx.report.attr.seed" value="42"/>
              <property name="androidx.report.attr.seed.label" value="Seed"/>
              <property name="androidx.report.attr.seed.filterable" value="false"/>
              <property name="androidx.report.step.1.name" value="OpenCart"/>
              <property name="androidx.report.step.1.status" value="PASSED"/>
              <property name="androidx.report.step.1.durationMs" value="320"/>
            </properties>
          </testcase>
        </testsuite>
        """
          .trimIndent(),
        Charsets.UTF_8,
      )

    val aggregator = XMLReportAggregator(listOf(inputDir), "ShopProject", rootDir)
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    val descriptor = report.suiteDescriptors["journeyTest"]!!
    assertThat(descriptor.adapterId).isEqualTo("default")
    assertThat(descriptor.suiteType).isEqualTo("com.example.journey")
    assertThat(descriptor.title).isNull()
    assertThat(descriptor.likelyCauseHeading).isNull()
    assertThat(descriptor.summaryColumns)
      .containsExactly(
        SummaryColumnDef(key = "locale", label = "Locale", filterable = true, sortable = true),
        SummaryColumnDef(key = "network", label = "Network", filterable = true, sortable = true),
      )
      .inOrder()

    val result =
      report.modules
        .single()
        .packages
        .single()
        .classes
        .single()
        .testCases
        .single()
        .targets
        .single()
        .testSuiteResults
        .single()
        .variantResults["debug"]!!

    assertThat(result.adapterId).isEqualTo("default")
    assertThat(result.suiteType).isEqualTo("com.example.journey")
    assertThat(result.envId).isEqualTo("env_01")
    assertThat(result.summaryAttributes)
      .containsExactly(
        "locale",
        SummaryAttributeValue("en-US", "English (US)", "Locale"),
        "network",
        SummaryAttributeValue("3G", "3G", "Network"),
        "seed",
        SummaryAttributeValue("42", "42", "Seed"),
      )
    assertThat(result.steps).containsExactly(StepInfo(n = 1, name = "OpenCart", status = "PASSED", durationMs = 320L))
    // No XML backup rules content -> details/env_01.js must not be written.
    assertThat(File(outputDir, "details/env_01.js").exists()).isFalse()
  }

  @Test
  fun forwardCompatibility_unsupportedSchemaVersionDeduplicatesWarningAndBuildsDynamicColumns() {
    val attachDir = File(inputDir, "attachments/1").apply { mkdirs() }
    File(attachDir, "host-log.txt").writeText("future schema host log", Charsets.UTF_8)

    File(inputDir, "TEST-FutureBackupTest.xml")
      .writeText(
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<testsuite name="com.example.FutureBackupTest" tests="2" failures="1" errors="0" skipped="0" time="3.0">
        |  <properties>
        |    <property name="modulePath" value=":app"/>
        |    <property name="testedVariantName" value="debug"/>
        |    <property name="testSuiteName" value="backupTest"/>
        |  </properties>
        |  <testcase name="testFutureSchema" classname="com.example.FutureBackupTest" time="2.5">
        |    <properties>
        |      <property name="androidx.report.schemaVersion" value="99"/>
        |      <property name="androidx.report.suiteType" value="androidx.test.backup"/>
        |      <property name="androidx.report.step.1.name" value="FutureStep"/>
        |      <property name="BackupRestore.transportMode" value="LOCAL"/>
        |    </properties>
        |    <failure message="failed">AssertionError: failed</failure>
        |    <system-out><![CDATA[fallback stdout captured
        |[[ATTACHMENT|attachments/1/host-log.txt]]
        |]]></system-out>
        |  </testcase>
        |  <testcase name="testSecondFutureSchema" classname="com.example.FutureBackupTest" time="0.5">
        |    <properties>
        |      <property name="androidx.report.schemaVersion" value="99"/>
        |      <property name="androidx.report.suiteType" value="androidx.test.backup"/>
        |      <property name="BackupRestore.transportMode" value="LOCAL"/>
        |    </properties>
        |  </testcase>
        |</testsuite>
        """
          .trimMargin(),
        Charsets.UTF_8,
      )

    val logger = MockLog()
    val aggregator =
      XMLReportAggregator(
        files = listOf(inputDir),
        projectName = "FutureCompatProject",
        rootDir = rootDir,
        adapterRegistry = SuiteReportAdapterRegistry(),
        logger = logger,
      )
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    val warnings = logger.messages.filter { it.startsWith("W ") }
    val errors = logger.messages.filter { it.startsWith("E ") || it.startsWith("T") }
    assertThat(errors).isEmpty()
    val schemaWarnings = warnings.filter { it.contains("schema version 99") }
    assertThat(schemaWarnings).hasSize(1)
    assertThat(schemaWarnings.single()).contains("Upgrade Android Gradle Plugin")
    assertThat(schemaWarnings.single()).doesNotContain("TestDetailRecord")

    // Legacy properties without a "devices" attribute emit only the "Transport" column.
    val descriptor = checkNotNull(report.suiteDescriptors["backupTest"])
    assertThat(descriptor.summaryColumns)
      .containsExactly(
        SummaryColumnDef(
          key = "transport",
          label = "Transport",
          filterable = true,
          sortable = true,
          tooltip = "Android backup transport mode used by the test",
        )
      )

    val testCases = report.modules.single().packages.single().classes.single().testCases
    val result = testCases.first { it.name == "testFutureSchema" }.targets.single().testSuiteResults.single().variantResults["debug"]!!
    assertThat(result.status).isEqualTo("fail")
    assertThat(result.durationMs).isEqualTo(2500L)
    assertThat(result.steps).isNull()
    assertThat(result.summaryAttributes).containsEntry("transport", SummaryAttributeValue("LOCAL", "Local", "Transport"))
    assertThat(result.properties).isNull()
    val lazyDetail = readLazyTestDetailJs(outputDir, checkNotNull(result.detailId), gson)
    assertThat(lazyDetail.systemOut).isEqualTo("fallback stdout captured\n")
    assertThat(lazyDetail.logs).isEmpty()
  }

  @Test
  fun security_rejectsPathTraversalNonAttachmentsAndSymlinks() {
    val outsideSecret = File(temporaryFolder.root, "outside.txt").apply { writeText("TOP SECRET", Charsets.UTF_8) }
    val siblingInsideInput =
      File(inputDir, "other/secret.txt").apply {
        parentFile.mkdirs()
        writeText("TOP SECRET SIBLING", Charsets.UTF_8)
      }

    val attachSubdir = File(inputDir, "attachments/1").apply { mkdirs() }
    val symlinkLog = File(attachSubdir, "symlink-log.txt")
    val symlinkCreated =
      try {
        Files.createSymbolicLink(symlinkLog.toPath(), outsideSecret.toPath())
        true
      } catch (_: Exception) {
        false
      }

    File(inputDir, "TEST-SecurityTest.xml")
      .writeText(
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<testsuite name="com.example.SecurityTest" tests="1" failures="0" errors="0" skipped="0" time="0.1">
        |  <properties>
        |    <property name="modulePath" value=":app"/>
        |    <property name="testedVariantName" value="debug"/>
        |    <property name="testSuiteName" value="backupTest"/>
        |  </properties>
        |  <testcase name="testEscapedAttachmentPaths" classname="com.example.SecurityTest" time="0.1">
        |    <properties>
        |      <property name="androidx.report.schemaVersion" value="1"/>
        |      <property name="androidx.report.suiteType" value="androidx.test.backup"/>
        |      <property name="androidx.report.artifact.1.name" value="Safe summary"/>
        |      <property name="androidx.report.artifact.1.values" value="status=ok"/>
        |    </properties>
        |    <system-out><![CDATA[
        |[[ATTACHMENT|../outside.txt]]
        |[[ATTACHMENT|${outsideSecret.absolutePath}]]
        |[[ATTACHMENT|${siblingInsideInput.relativeTo(inputDir).invariantSeparatorsPath}]]
        |[[ATTACHMENT|attachments/1/../../other/secret.txt]]
        |[[ATTACHMENT|../outside.backup]]
        |[[ATTACHMENT|attachments/1/symlink-log.txt]]
        |]]></system-out>
        |  </testcase>
        |</testsuite>
        """
          .trimMargin(),
        Charsets.UTF_8,
      )

    val logger = MockLog()
    val aggregator =
      XMLReportAggregator(
        files = listOf(inputDir),
        projectName = "SecurityProject",
        rootDir = rootDir,
        adapterRegistry = SuiteReportAdapterRegistry(),
        logger = logger,
      )
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    val nestedCase = report.modules.single().packages.single().classes.single().testCases.single()
    val nestedResult = nestedCase.targets.single().testSuiteResults.single().variantResults["debug"]!!
    assertThat(nestedResult.logMeta).isNull()
    assertThat(nestedResult.detailId).isNull()
    assertThat(nestedResult.artifacts).containsExactly(ArtifactInfo(name = "Safe summary", values = mapOf("status" to "ok"), path = null))

    assertThat(File(outputDir, "artifacts").exists()).isFalse()
    assertThat(readReportDataJsJson(outputDir).toString()).doesNotContain("TOP SECRET")
    val warnings = logger.messages.filter { it.startsWith("W ") }
    assertThat(warnings.any { it.contains("../outside.txt") }).isTrue()
    assertThat(warnings.any { it.contains("other/secret.txt") }).isTrue()
    if (symlinkCreated) {
      assertThat(warnings.any { it.contains("symlink-log.txt") }).isTrue()
    }
  }

  @Test
  fun logReader_separatesCapturedTruncationFrom512KbInlinePreviewTruncation() {
    val attachDir1 = File(inputDir, "attachments/1").apply { mkdirs() }
    val largeLogFile = File(attachDir1, "logcat-127.0.0.1_6520.txt")
    val prefix = "A".repeat(MAX_INLINE_LOG_BYTES - 1)
    largeLogFile.writeText(prefix + "·trailing-lines\n", Charsets.UTF_8)

    val attachDir2 = File(inputDir, "attachments/2").apply { mkdirs() }
    val runnerCappedLogFile = File(attachDir2, "host-log.txt")
    runnerCappedLogFile.writeText("Runner tail-capped host log\n", Charsets.UTF_8)

    File(inputDir, "TEST-LargeLogTest.xml")
      .writeText(
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<testsuite name="com.example.LargeLogTest" tests="1" failures="1" errors="0" skipped="0" time="1.0">
        |  <properties>
        |    <property name="modulePath" value=":app"/>
        |    <property name="testedVariantName" value="debug"/>
        |    <property name="testSuiteName" value="backupTest"/>
        |  </properties>
        |  <testcase name="testLargeLogcat" classname="com.example.LargeLogTest" time="1.0">
        |    <properties>
        |      <property name="androidx.report.schemaVersion" value="1"/>
        |      <property name="androidx.report.suiteType" value="androidx.test.backup"/>
        |      <property name="androidx.report.actor.source.serial" value="127.0.0.1:6520"/>
        |      <property name="androidx.report.file.logcat-127.0.0.1_6520.txt.truncated" value="false"/>
        |      <property name="androidx.report.file.host-log.txt.truncated" value="true"/>
        |    </properties>
        |    <failure message="failed">failed</failure>
        |    <system-out><![CDATA[
        |[[ATTACHMENT|attachments/1/logcat-127.0.0.1_6520.txt]]
        |[[ATTACHMENT|attachments/2/host-log.txt]]
        |]]></system-out>
        |  </testcase>
        |</testsuite>
        """
          .trimMargin(),
        Charsets.UTF_8,
      )

    val aggregator = XMLReportAggregator(listOf(inputDir), "LargeLogProject", rootDir)
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    val result =
      report.modules
        .single()
        .packages
        .single()
        .classes
        .single()
        .testCases
        .single()
        .targets
        .single()
        .testSuiteResults
        .single()
        .variantResults["debug"]!!
    val detailId = checkNotNull(result.detailId)
    assertThat(result.logMeta?.map { it.title }).containsExactly("Host log", "Logcat (source)").inOrder()

    val lazyDetail = readLazyTestDetailJs(outputDir, detailId, gson)
    assertThat(lazyDetail.logs).hasSize(2)

    val captureCappedEntry = lazyDetail.logs[0]
    assertThat(captureCappedEntry.title).isEqualTo("Host log")
    assertThat(captureCappedEntry.capturedTruncated).isTrue()
    assertThat(captureCappedEntry.previewTruncated).isFalse()
    assertThat(captureCappedEntry.content).isEqualTo("Runner tail-capped host log\n")

    val previewCappedEntry = lazyDetail.logs[1]
    assertThat(previewCappedEntry.title).isEqualTo("Logcat (source)")
    assertThat(previewCappedEntry.capturedTruncated).isFalse()
    assertThat(previewCappedEntry.previewTruncated).isTrue()
    assertThat(previewCappedEntry.content).doesNotContain("\uFFFD")
    assertThat(previewCappedEntry.content.toByteArray(Charsets.UTF_8).size).isEqualTo(MAX_INLINE_LOG_BYTES - 1)
    assertThat(File(outputDir, previewCappedEntry.artifactPath!!).length()).isEqualTo(largeLogFile.length())
  }

  @Test
  fun detailRecordReader_ignoresBlankMalformedEntriesAndPreservesCommasInArtifactValues() {
    val attachDir = File(inputDir, "attachments/1").apply { mkdirs() }
    File(attachDir, "host-log.txt").writeText("valid log line\n", Charsets.UTF_8)
    val attachDir3 = File(inputDir, "attachments/3").apply { mkdirs() }
    File(attachDir3, "archive.backup").writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))

    File(inputDir, "TEST-MalformedEntriesTest.xml")
      .writeText(
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<testsuite name="com.example.MalformedEntriesTest" tests="1" failures="0" errors="0" skipped="0" time="0.2">
        |  <properties>
        |    <property name="modulePath" value=":app"/>
        |    <property name="testedVariantName" value="debug"/>
        |    <property name="testSuiteName" value="backupTest"/>
        |  </properties>
        |  <testcase name="testMalformedEntries" classname="com.example.MalformedEntriesTest" time="0.2">
        |    <properties>
        |      <property name="androidx.report.schemaVersion" value="1"/>
        |      <property name="androidx.report.suiteType" value="androidx.test.backup"/>
        |      <property name="androidx.report.attr.transport" value="LOCAL"/>
        |      <property name="androidx.report.attr.transport.filterable" value="true"/>
        |      <property name="androidx.report.attr.blankAttr" value="   "/>
        |      <property name="androidx.report.actor.device.role" value="SINGLE"/>
        |      <property name="androidx.report.actor.device.name" value="Pixel 8"/>
        |      <property name="androidx.report.actor.device.api" value="34"/>
        |      <property name="androidx.report.actor..name" value="IgnoredBlankId"/>
        |      <property name="androidx.report.step.1.name" value="Backup"/>
        |      <property name="androidx.report.step.1.on" value="device"/>
        |      <property name="androidx.report.step.1.status" value="PASSED"/>
        |      <property name="androidx.report.step.1.durationMs" value="120"/>
        |      <property name="androidx.report.artifact.1.name" value="Backup summary"/>
        |      <property name="androidx.report.artifact.1.values" value="summary=1,234 items, size=4 KB"/>
        |      <property name="androidx.report.artifact.2.name" value="   "/>
        |      <property name="androidx.report.file.archive.backup.values" value="size=412 B, files=3"/>
        |    </properties>
        |    <system-out><![CDATA[
        |[[ATTACHMENT|attachments/1/host-log.txt]]
        |[[ATTACHMENT|attachments/2/missing.bin]]
        |[[ATTACHMENT|attachments/3/archive.backup]]
        |]]></system-out>
        |  </testcase>
        |</testsuite>
        """
          .trimMargin(),
        Charsets.UTF_8,
      )

    val logger = MockLog()
    val aggregator =
      XMLReportAggregator(
        files = listOf(inputDir),
        projectName = "MalformedEntriesProject",
        rootDir = rootDir,
        adapterRegistry = SuiteReportAdapterRegistry(),
        logger = logger,
      )
    aggregator.writeReport(outputDir)
    val report = aggregator.generateReport(outputDir)

    assertThat(logger.messages.filter { it.startsWith("E ") || it.startsWith("T") }).isEmpty()
    val result =
      report.modules
        .single()
        .packages
        .single()
        .classes
        .single()
        .testCases
        .single()
        .targets
        .single()
        .testSuiteResults
        .single()
        .variantResults["debug"]!!
    val detailId = checkNotNull(result.detailId)
    assertThat(result.summaryAttributes).containsExactly("transport", SummaryAttributeValue("LOCAL", "Local", "Transport"))
    assertThat(result.actors).hasSize(1)
    assertThat(result.steps).hasSize(1)
    assertThat(result.artifacts)
      .containsExactly(
        ArtifactInfo(name = "Backup summary", values = mapOf("summary" to "1,234 items", "size" to "4 KB"), path = null),
        ArtifactInfo(
          name = "archive.backup",
          values = mapOf("size" to "412 B", "files" to "3"),
          path = "artifacts/$detailId/archive.backup",
        ),
      )
      .inOrder()
    assertThat(result.logMeta).hasSize(1)
    assertThat(readLazyTestDetailJs(outputDir, detailId, gson).logs.single().content).isEqualTo("valid log line\n")
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

internal fun readReportDataJsJson(outputDir: File): JsonObject {
  val content = File(outputDir, "data.js").readText(Charsets.UTF_8)
  assertThat(content).startsWith("const TEST_DATA_SOURCE = ")
  val json = content.removePrefix("const TEST_DATA_SOURCE = ").trim().removeSuffix(";")
  return JsonParser.parseString(json).asJsonObject
}

internal fun readLazyDetailJsonObject(outputDir: File, detailId: String): JsonObject {
  val content = File(outputDir, "details/$detailId.js").readText(Charsets.UTF_8)
  val prefix = "window.__AGP_TEST_DETAILS = window.__AGP_TEST_DETAILS || {};\nwindow.__AGP_TEST_DETAILS[\"$detailId\"] = "
  assertThat(content).startsWith(prefix)
  val json = content.removePrefix(prefix).trim().removeSuffix(";")
  return JsonParser.parseString(json).asJsonObject
}

internal fun readLazyTestDetailJs(outputDir: File, detailId: String, gson: Gson = Gson()): LazyTestDetail =
  gson.fromJson(readLazyDetailJsonObject(outputDir, detailId), LazyTestDetail::class.java)

internal fun readLazyEnvironmentDetailJs(outputDir: File, envId: String, gson: Gson = Gson()): LazyEnvironmentDetail =
  gson.fromJson(readLazyDetailJsonObject(outputDir, envId), LazyEnvironmentDetail::class.java)
