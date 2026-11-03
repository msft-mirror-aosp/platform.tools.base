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

package com.android.build.gradle.internal.test.report

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Test

class TestReportDataModelTest {

  private val gson: Gson = GsonBuilder().create()

  @Test
  fun `test TestCase serialization`() {
    val stackTraceGroup = StackTraceGroup(id = "st-1", stackTrace = "stacktrace", occurrences = mapOf("unitTest" to listOf("release")))

    val testSuiteResult =
      TestSuiteTestResult(
        testSuiteName = "unitTest",
        variantResults =
          mapOf("debug" to VariantTestResult(status = "pass"), "release" to VariantTestResult(status = "fail", stackTraceId = "st-1")),
      )

    val target =
      Target(
        name = UNKNOWN_TARGET,
        testSuiteSummaries = emptyList(),
        testSuiteResults = listOf(testSuiteResult),
        commonStackTraces = listOf(stackTraceGroup),
      )

    val testCase = TestCase(name = "testSomething", targets = listOf(target))

    val jsonString = gson.toJson(testCase)

    assertThat(jsonString).contains("\"name\":\"testSomething\"")
    assertThat(jsonString).contains("\"name\":\"$UNKNOWN_TARGET\"")
    assertThat(jsonString).contains("\"testSuiteName\":\"unitTest\"")
    assertThat(jsonString).contains("\"status\":\"pass\"")
    assertThat(jsonString).contains("\"status\":\"fail\"")
    assertThat(jsonString).contains("\"stackTraceId\":\"st-1\"")
    assertThat(jsonString).contains("\"stackTrace\":\"stacktrace\"")
  }

  @Test
  fun `test RootReport serialization`() {
    val testSuiteResult =
      TestSuiteTestResult(testSuiteName = "unitTest", variantResults = mapOf("debug" to VariantTestResult(status = "pass")))
    val target =
      Target(
        name = UNKNOWN_TARGET,
        testSuiteSummaries = emptyList(),
        testSuiteResults = listOf(testSuiteResult),
        commonStackTraces = emptyList(),
      )
    val testCase = TestCase(name = "test1", targets = listOf(target))
    val classType = ClassType(name = "MyTest", testSuiteSummaries = emptyList(), testCases = listOf(testCase))
    val pkg = Package(name = "com.example", testSuiteSummaries = emptyList(), classes = listOf(classType))
    val module = Module(name = ":app", testSuiteSummaries = emptyList(), packages = listOf(pkg))

    val rootReport =
      RootReport(
        projectName = "project",
        timestamp = "Mar 4, 2026, 6:09PM",
        numberOfModules = 1,
        numberOfPackages = 1,
        numberOfClasses = 1,
        variants = listOf("debug", "release"),
        testSuites = listOf("unitTest"),
        targets = listOf(UNKNOWN_TARGET),
        modules = listOf(module),
        relativeRootDir = "",
      )

    val jsonString = gson.toJson(rootReport)

    val expectedJson =
      """
      {"projectName":"project","timestamp":"Mar 4, 2026, 6:09PM","numberOfModules":1,"numberOfPackages":1,"numberOfClasses":1,"variants":["debug","release"],"testSuites":["unitTest"],"targets":["$UNKNOWN_TARGET"],"modules":[{"name":":app","testSuiteSummaries":[],"packages":[{"name":"com.example","testSuiteSummaries":[],"classes":[{"name":"MyTest","testSuiteSummaries":[],"testCases":[{"name":"test1","targets":[{"name":"$UNKNOWN_TARGET","testSuiteSummaries":[],"testSuiteResults":[{"testSuiteName":"unitTest","variantResults":{"debug":{"status":"pass"}}}],"commonStackTraces":[]}]}]}]}]}],"relativeRootDir":"","suiteDescriptors":{},"suiteEnvironments":[]}
      """
        .trimIndent()
        .replace(Regex("\\s"), "")

    assertThat(jsonString.replace(Regex("\\s"), "")).isEqualTo(expectedJson)
  }

  @Test
  fun `test RootReport serialization with relativeRootDir`() {
    val rootReport =
      RootReport(
        projectName = "project",
        timestamp = "Mar 4, 2026, 6:09PM",
        numberOfModules = 0,
        numberOfPackages = 0,
        numberOfClasses = 0,
        variants = emptyList(),
        testSuites = emptyList(),
        targets = emptyList(),
        modules = emptyList(),
        relativeRootDir = "../../../../../../",
      )

    val jsonString = gson.toJson(rootReport)
    assertThat(jsonString).contains("\"relativeRootDir\":\"../../../../../../\"")

    val deserialized = gson.fromJson(jsonString, RootReport::class.java)
    assertThat(deserialized.relativeRootDir).isEqualTo("../../../../../../")
  }

  @Test
  fun testExtensibleSuiteWireJsonKeys() {
    val descriptor =
      SuiteDescriptor(
        adapterId = "backupRestore",
        suiteType = "androidx.test.backup",
        title = "Backup & Restore",
        likelyCauseHeading = "Backup rule that left the data out · Likely cause",
        summaryColumns =
          listOf(
            SummaryColumnDef(key = "transport", label = "Transport", filterable = true, sortable = true),
            SummaryColumnDef(key = "devices", label = "Devices", filterable = true, sortable = true),
          ),
        docsUrl = "https://developer.android.com/identity/data/testing",
      )
    val descriptorJson = JsonParser.parseString(gson.toJson(descriptor)).asJsonObject
    assertThat(descriptorJson.keySet())
      .containsExactly("adapterId", "suiteType", "title", "likelyCauseHeading", "summaryColumns", "docsUrl")
    assertThat(descriptorJson.get("title").asString).isEqualTo("Backup & Restore")
    assertThat(descriptorJson.get("likelyCauseHeading").asString).isEqualTo("Backup rule that left the data out · Likely cause")
    assertThat(descriptorJson.getAsJsonArray("summaryColumns")[0].asJsonObject.keySet())
      .containsExactly("key", "label", "filterable", "sortable")

    val envInfo =
      SuiteEnvironmentInfo(
        envId = "env_01",
        modulePath = ":app",
        variantName = "debug",
        suiteName = "backupTest",
        targetName = "default",
        suiteType = "androidx.test.backup",
        applicationId = "com.example.journalingapp",
        applicationName = "JournalingApp",
        versionName = "1.2.0",
        versionCode = 12,
        targetSdkVersion = 34,
        agpVersion = "9.6.0-dev",
        gradleVersion = "9.7.1",
        testLibraryVersion = "1.0.0-alpha04",
        runner = "junit-jupiter",
        backupRules =
          BackupRulesInfo(
            allowBackup = true,
            fullBackupContent = "@xml/backup_rules",
            fullBackupContentFile = "backup_rules.xml",
            fullBackupContentArtifactPath = "artifacts/env_01/backup_rules.xml",
            dataExtractionRules = "@xml/data_extraction_rules",
            dataExtractionRulesFile = "data_extraction_rules.xml",
            dataExtractionRulesArtifactPath = "artifacts/env_01/data_extraction_rules.xml",
          ),
      )
    val envJson = JsonParser.parseString(gson.toJson(envInfo)).asJsonObject
    assertThat(envJson.get("suiteType").asString).isEqualTo("androidx.test.backup")
    assertThat(envJson.get("targetSdkVersion").asInt).isEqualTo(34)
    assertThat(envJson.getAsJsonObject("backupRules").keySet())
      .containsExactly(
        "allowBackup",
        "fullBackupContent",
        "fullBackupContentFile",
        "fullBackupContentArtifactPath",
        "dataExtractionRules",
        "dataExtractionRulesFile",
        "dataExtractionRulesArtifactPath",
      )

    val variantResult =
      VariantTestResult(
        status = "fail",
        stackTraceId = "st-1",
        durationMs = 41420L,
        suiteType = "androidx.test.backup",
        adapterId = "backupRestore",
        detailId = "t_0001",
        envId = "env_01",
        properties = mapOf("customTag" to "smoke"),
        summaryAttributes =
          mapOf("transport" to SummaryAttributeValue(value = "DEVICE_TO_DEVICE", displayValue = "Device to device", label = "Transport")),
        sourceLocation = SourceLocationInfo(file = "CrossDeviceMigrationTests.kt", line = 47, className = "pkg.Tests", methodName = "t1"),
        callout = CalloutInfo(kind = "ASSERTION_FAILURE", failedStep = 5, title = "Failed", message = "msg", likelyCause = "cause"),
        actors = listOf(ActorInfo(id = "source", role = "SOURCE", name = "Pixel 6", api = 31, serial = "emulator-5554")),
        steps =
          listOf(
            StepInfo(n = 1, name = "Verify", on = "target", shared = false, call = "runOnDevice()", durationMs = 980L, status = "FAILED")
          ),
        artifacts = listOf(ArtifactInfo(name = "Backup file summary", values = mapOf("app_backup" to "1.8 MB"), path = null)),
        logMeta = listOf(LogMetaInfo(title = "Host log", lineCount = 42, artifactPath = "artifacts/t_0001/host-log.txt")),
      )
    val resultJson = JsonParser.parseString(gson.toJson(variantResult)).asJsonObject
    val transportAttr = resultJson.getAsJsonObject("summaryAttributes").getAsJsonObject("transport")
    assertThat(transportAttr.keySet()).containsExactly("value", "displayValue", "label")
    assertThat(transportAttr.get("label").asString).isEqualTo("Transport")
    assertThat(resultJson.get("detailId").asString).isEqualTo("t_0001")

    val lazyTestDetail =
      LazyTestDetail(
        logs =
          listOf(
            LazyLogEntry(
              title = "Host log",
              content = "line 1",
              lineCount = 1,
              capturedTruncated = true,
              previewTruncated = false,
              artifactPath = "artifacts/t_0001/host-log.txt",
            )
          ),
        systemOut = "stdout",
        systemOutTruncated = true,
        systemErr = "stderr",
        systemErrTruncated = false,
      )
    val lazyTestJson = JsonParser.parseString(gson.toJson(lazyTestDetail)).asJsonObject
    assertThat(lazyTestJson.keySet()).containsExactly("logs", "systemOut", "systemOutTruncated", "systemErr", "systemErrTruncated")
    assertThat(lazyTestJson.has("backupRules")).isFalse()
    assertThat(lazyTestJson.getAsJsonArray("logs")[0].asJsonObject.keySet())
      .containsExactly("title", "content", "lineCount", "capturedTruncated", "previewTruncated", "artifactPath")

    val lazyEnvDetail =
      LazyEnvironmentDetail(
        backupRules =
          listOf(
            LazyBackupRuleEntry(
              label = "backup_rules.xml",
              attribute = "@xml/backup_rules",
              artifactPath = "artifacts/env_01/backup_rules.xml",
              content = "<full-backup-content/>",
            )
          )
      )
    val lazyEnvJson = JsonParser.parseString(gson.toJson(lazyEnvDetail)).asJsonObject
    assertThat(lazyEnvJson.keySet()).containsExactly("backupRules")
    assertThat(lazyEnvJson.getAsJsonArray("backupRules")[0].asJsonObject.keySet())
      .containsExactly("label", "attribute", "artifactPath", "content")
  }
}
