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

package com.android.build.gradle.integration.testing.suites

import com.android.Version
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.api.dsl.BackupTestSuite
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HasTestSuites
import com.android.build.api.variant.TestSuite
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor.ConfigurationCaching
import com.android.build.gradle.integration.common.fixture.LoggingLevel
import com.android.build.gradle.integration.common.fixture.ProfileCapturer
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.internal.dsl.BackupAgpTestSuiteImpl
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.TestRun
import com.google.wireless.android.sdk.stats.TestRun.BackupTestRun.BackupFailureReason
import com.google.wireless.android.sdk.stats.TestRun.BackupTestRun.BackupTransportMode
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import org.w3c.dom.Element

/** Differs from [BackupAgpTestSuiteImpl.DEFAULT_BACKUP_VERSION], so the tests can tell a configured version from the default. */
private const val CUSTOM_BACKUP_VERSION = "${BackupAgpTestSuiteImpl.DEFAULT_BACKUP_VERSION}-custom"

/**
 * Integration test verifying the registration, DSL configuration, execution, and analytics tracking of the specialized [BackupTestSuite]
 * under an application module.
 */
class BackupTestSuiteDeclarationTest {
  @get:Rule
  val rule =
    GradleRule.configure()
      .withProfileOutput()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("androidx.test.backup:backup-host:$CUSTOM_BACKUP_VERSION").withDependencies("org.junit.jupiter:junit-jupiter-api:5.13.3")
        jar("androidx.test.backup:backup:$CUSTOM_BACKUP_VERSION")
        jar("org.junit.platform:junit-platform-engine:1.13.3")
        jar("org.junit.platform:junit-platform-launcher:1.13.3")
        jar("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
        jar("com.test:toy-junit-engine:1.0")
          .addClasses(ToyJunitEngineForTesting::class.java, ToyTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", ToyJunitEngineForTesting::class.java.name)
      }
      .from {
        rootProject { buildscript { classpath("com.google.truth:truth:0.44") } }
        gradleProperties {
          add(BooleanOption.TEST_SUITE_SUPPORT, true)
          add(BooleanOption.ENABLE_BACKUP_TEST, true)
        }
        androidApplication {
          pluginCallbacks += BackupSuiteCallback::class.java
          android {
            testOptions.backupTests.create("myBackup") {
              it.backupTestLibraryVersion = CUSTOM_BACKUP_VERSION
              it.targetVariants.add("debug")
            }
          }
          files {
            add("src/myBackup/test/java/com/example/MyBackupTest.java", "package com.example;\npublic class MyBackupTest {}")
          }
          dependencies {
            implementation("com.google.truth:truth:0.44")
          }
        }
      }

  @Test
  fun testBackupTestSuiteConfiguration() {
    rule.build.executor.run(":app:tasks")
  }

  @Test
  fun testDevicePropertiesReachTestJvm() {
    val build = rule.build
    val loggingFile =
      build.subProject(":app").resolve("build/intermediates/debug/testDebugMyBackupDefaultTestSuite/junit_engines_logging.txt").toFile()
    fun runWithSerial(serial: String) =
      build.executor
        .withConfigurationCaching(ConfigurationCaching.ON)
        .withArgument("-Dandroidx.test.backup.device.serial=$serial")
        .run(":app:testDebugMyBackupDefaultTestSuite")

    runWithSerial("emulator-5554")
    assertThat(loggingFile.readText()).contains("System property : androidx.test.backup.device.serial = emulator-5554")

    // Selecting another device reuses the configuration cache, and the new value still reaches the test JVM.
    runWithSerial("emulator-5556").assertOutputContains("Reusing configuration cache.")
    assertThat(loggingFile.readText()).contains("System property : androidx.test.backup.device.serial = emulator-5556")

    // Values reach the test JVM unchanged, including '=' and spaces, and report properties are forwarded lazily.
    build.executor
      .withConfigurationCaching(ConfigurationCaching.ON)
      .withArguments(
        listOf(
          "-Dandroidx.test.backup.device.serial=a=b c",
          "-Dandroidx.test.backup.report.captureLogcatOnPass=true",
          "-Dandroidx.test.backup.report.includeRawArchives=true",
        )
      )
      .run(":app:testDebugMyBackupDefaultTestSuite")
      .assertOutputContains("Reusing configuration cache.")
    val reportPropsLog = loggingFile.readText()
    assertThat(reportPropsLog).contains("System property : androidx.test.backup.device.serial = a=b c")
    assertThat(reportPropsLog).contains("System property : androidx.test.backup.report.captureLogcatOnPass = true")
    assertThat(reportPropsLog).contains("System property : androidx.test.backup.report.includeRawArchives = true")
  }

  @Test
  fun testSuiteEnvironmentInputsAndReportAggregation() {
    val build = rule.build {
      androidApplication {
        android {
          defaultConfig {
            applicationId = "com.example.journalingapp"
            versionCode = 12
            versionName = "1.2.0"
          }
        }
        files {
          update("src/main/AndroidManifest.xml")
            .replaceWith(
              """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application
                      android:allowBackup="true"
                      android:label="@string/app_name"
                      android:fullBackupContent="@xml/backup_rules"
                      android:dataExtractionRules="@xml/data_extraction_rules" />
              </manifest>
              """
                .trimIndent()
            )
          add(
            "src/main/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">MainLabel</string>
            </resources>
            """
              .trimIndent(),
          )
          add(
            "src/debug/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">DebugLabel</string>
            </resources>
            """
              .trimIndent(),
          )
          add(
            "src/extraRes/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">JournalingApp</string>
            </resources>
            """
              .trimIndent(),
          )
          add(
            "src/main/res/xml/backup_rules.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <full-backup-content>
                <exclude domain="sharedpref" path="secret.xml" />
            </full-backup-content>
            """
              .trimIndent(),
          )
          add(
            "src/main/res/xml/data_extraction_rules.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <data-extraction-rules>
                <device-transfer>
                    <exclude domain="sharedpref" path="user_session.xml" />
                </device-transfer>
            </data-extraction-rules>
            """
              .trimIndent(),
          )
          add(
            "src/myBackup/test/java/com/example/SuiteReportWiringTest.java",
            """
            package com.example;

            import java.io.File;
            import java.io.FileInputStream;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.util.Properties;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.TestReporter;
            import org.junit.jupiter.api.extension.MediaType;

            public class SuiteReportWiringTest {
              @Test
              public void firstCase(TestReporter reporter) throws Exception {
                System.out.println("first-case-stdout");
                String inputsFile = System.getenv("com.android.junit.engine.input.parameters");
                Properties props = new Properties();
                try (FileInputStream in = new FileInputStream(inputsFile)) {
                  props.load(in);
                }
                File resultsDir = new File(props.getProperty("com.android.junit.engine.results.dir"));
                if (resultsDir.exists()) {
                  throw new AssertionError("Expected RESULTS_DIR not to exist before engine execution");
                }
                if (!"true".equals(props.getProperty("com.android.agp.test.ALLOW_BACKUP"))) {
                  throw new AssertionError("Missing ALLOW_BACKUP in junit_inputs.txt");
                }
                String fullXml = props.getProperty("com.android.agp.test.FULL_BACKUP_CONTENT_XML");
                if (fullXml == null || !fullXml.contains("secret.xml")) {
                  throw new AssertionError("Missing FULL_BACKUP_CONTENT_XML in junit_inputs.txt");
                }
                String extractionXml = props.getProperty("com.android.agp.test.DATA_EXTRACTION_RULES_XML");
                if (extractionXml == null || !extractionXml.contains("user_session.xml")) {
                  throw new AssertionError("Missing DATA_EXTRACTION_RULES_XML in junit_inputs.txt");
                }
                String reportingDir = System.getProperty("junit.platform.reporting.output.dir");
                if (reportingDir == null || reportingDir.isEmpty()) {
                  throw new AssertionError("Expected junit.platform.reporting.output.dir system property");
                }

                reporter.publishEntry("androidx.report.schemaVersion", "1");
                reporter.publishEntry("androidx.report.suiteType", "androidx.test.backup");
                reporter.publishEntry("androidx.report.attr.transport", "LOCAL");
                reporter.publishEntry("androidx.report.attr.transport.filterable", "true");
                reporter.publishEntry("androidx.report.actor.source.role", "SOURCE");
                reporter.publishEntry("androidx.report.actor.source.name", "Cuttlefish");
                reporter.publishEntry("androidx.report.actor.source.serial", "127.0.0.1:6520");
                reporter.publishEntry("androidx.report.step.1.name", "Backup");
                reporter.publishEntry("androidx.report.step.1.status", "PASSED");
                reporter.publishEntry("androidx.report.step.1.durationMs", "120");
                reporter.publishFile(
                    "logcat-127.0.0.1_6520.txt",
                    MediaType.TEXT_PLAIN_UTF_8,
                    path ->
                        Files.write(
                            path,
                            "01-01 00:00:00.000 I/BackupManager: OK\n"
                                .getBytes(StandardCharsets.UTF_8)));
                reporter.publishFile(
                    "host-log.txt",
                    MediaType.TEXT_PLAIN_UTF_8,
                    path ->
                        Files.write(
                            path,
                            "Backup started\nRestore completed\n"
                                .getBytes(StandardCharsets.UTF_8)));
              }

              @Test
              public void secondCase() {
                System.out.println("second-case-stdout");
              }
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    build.executor.run(":app:testDebugMyBackupDefaultTestSuite")

    val taskIntermediatesDir = build.subProject(":app").resolve("build/intermediates/debug/testDebugMyBackupDefaultTestSuite").toFile()
    val junitInputs = Properties().also { props -> File(taskIntermediatesDir, "junit_inputs.txt").reader().use { props.load(it) } }
    assertThat(junitInputs.getProperty("com.android.agp.test.MERGED_MANIFEST")).isNull()
    assertThat(junitInputs.getProperty("com.android.agp.test.TESTED_RES_DIRS")).isNull()
    assertThat(junitInputs.getProperty("com.android.agp.test.TEST_LIBRARY_VERSION")).isEqualTo(CUSTOM_BACKUP_VERSION)
    assertThat(junitInputs.getProperty("com.android.agp.test.ALLOW_BACKUP")).isEqualTo("true")
    assertThat(junitInputs.getProperty("com.android.agp.test.FULL_BACKUP_CONTENT")).isEqualTo("@xml/backup_rules")
    assertThat(junitInputs.getProperty("com.android.agp.test.FULL_BACKUP_CONTENT_FILE")).isEqualTo("backup_rules.xml")
    assertThat(junitInputs.getProperty("com.android.agp.test.FULL_BACKUP_CONTENT_XML")).contains("secret.xml")
    assertThat(junitInputs.getProperty("com.android.agp.test.DATA_EXTRACTION_RULES")).isEqualTo("@xml/data_extraction_rules")
    assertThat(junitInputs.getProperty("com.android.agp.test.DATA_EXTRACTION_RULES_FILE")).isEqualTo("data_extraction_rules.xml")
    assertThat(junitInputs.getProperty("com.android.agp.test.DATA_EXTRACTION_RULES_XML")).contains("user_session.xml")

    // Verify per-testcase stdout and FileEntry attachment marker in Gradle's JUnit XML (build/test-results/<task>).
    val rawXmlFile =
      build
        .subProject(":app")
        .resolve("build/test-results/testDebugMyBackupDefaultTestSuite/TEST-com.example.SuiteReportWiringTest.xml")
        .toFile()
    val xmlDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rawXmlFile)
    val testCaseNodes = xmlDoc.getElementsByTagName("testcase")
    val systemOutByTest = mutableMapOf<String, String>()
    for (i in 0 until testCaseNodes.length) {
      val testCase = testCaseNodes.item(i) as Element
      val systemOutNodes = testCase.getElementsByTagName("system-out")
      val outText = if (systemOutNodes.length > 0) systemOutNodes.item(0).textContent else ""
      systemOutByTest[testCase.getAttribute("name")] = outText
    }
    val firstOut = systemOutByTest.entries.single { it.key.startsWith("firstCase") }.value
    val secondOut = systemOutByTest.entries.single { it.key.startsWith("secondCase") }.value
    assertThat(firstOut).contains("first-case-stdout")
    assertThat(firstOut).contains("[[ATTACHMENT|")
    assertThat(firstOut).doesNotContain("second-case-stdout")
    assertThat(secondOut).contains("second-case-stdout")
    assertThat(secondOut).doesNotContain("first-case-stdout")

    // Verify enriched <testsuite><properties> and namespaced attachments/<hash>/... in TEST_SUITE_RESULTS, with zero JSON sidecar files.
    val suiteResultsRoot = build.subProject(":app").resolve("build/intermediates/test_suite_results").toFile()
    assertThat(suiteResultsRoot.walk().filter { it.isFile && it.extension == "json" }.toList()).isEmpty()
    val aggregatedSuiteXml =
      checkNotNull(
        suiteResultsRoot.walk().firstOrNull {
          it.isFile && it.name.startsWith("TEST-com.example.SuiteReportWiringTest_") && it.name.endsWith(".xml")
        }
      )
    val xmlHash = aggregatedSuiteXml.name.removePrefix("TEST-com.example.SuiteReportWiringTest_").removeSuffix(".xml")
    val aggregatedDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(aggregatedSuiteXml)
    val suitePropsElement =
      (aggregatedDoc.documentElement.childNodes.let { nodes ->
        (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().first { it.tagName == "properties" }
      })
    val suiteProps = mutableMapOf<String, String>()
    val propNodes = suitePropsElement.getElementsByTagName("property")
    for (i in 0 until propNodes.length) {
      val prop = propNodes.item(i) as Element
      suiteProps[prop.getAttribute("name")] = prop.getAttribute("value")
    }
    assertThat(suiteProps["modulePath"]).isEqualTo(":app")
    assertThat(suiteProps["testedVariantName"]).isEqualTo("debug")
    assertThat(suiteProps["testSuiteName"]).isEqualTo("myBackup")
    assertThat(suiteProps["testTarget"]).isEqualTo("default")
    assertThat(suiteProps["suiteType"]).isEqualTo("androidx.test.backup")
    assertThat(suiteProps["applicationId"]).isEqualTo("com.example.journalingapp")
    assertThat(suiteProps["applicationName"]).isEqualTo("JournalingApp")
    assertThat(suiteProps["versionName"]).isEqualTo("1.2.0")
    assertThat(suiteProps["versionCode"]).isEqualTo("12")
    assertThat(suiteProps["agpVersion"]).isEqualTo(Version.ANDROID_GRADLE_PLUGIN_VERSION)
    assertThat(suiteProps["gradleVersion"]).isNotEmpty()
    assertThat(suiteProps["testLibraryVersion"]).isEqualTo(CUSTOM_BACKUP_VERSION)
    assertThat(suiteProps["runner"]).isEqualTo("junit-jupiter")
    assertThat(suiteProps["allowBackup"]).isEqualTo("true")
    assertThat(suiteProps["fullBackupContent"]).isEqualTo("@xml/backup_rules")
    assertThat(suiteProps["fullBackupContentFile"]).isEqualTo("backup_rules.xml")
    assertThat(suiteProps["fullBackupContentXml"]).contains("secret.xml")
    assertThat(suiteProps["dataExtractionRules"]).isEqualTo("@xml/data_extraction_rules")
    assertThat(suiteProps["dataExtractionRulesFile"]).isEqualTo("data_extraction_rules.xml")
    assertThat(suiteProps["dataExtractionRulesXml"]).contains("user_session.xml")

    val namespacedLogcat = File(aggregatedSuiteXml.parentFile, "attachments/$xmlHash/1/logcat-127.0.0.1_6520.txt")
    val namespacedAttachment = File(aggregatedSuiteXml.parentFile, "attachments/$xmlHash/2/host-log.txt")
    assertThat(namespacedLogcat.isFile).isTrue()
    assertThat(namespacedAttachment.isFile).isTrue()
    assertThat(namespacedAttachment.readText()).isEqualTo("Backup started\nRestore completed\n")

    // Verify end-to-end HTML report generation resolves report entries and FileEntry attachments into data.js, details/, and artifacts/.
    val htmlReportDir = build.subProject(":app").resolve("build/reports/tests/testDebugMyBackupDefaultTestSuite").toFile()
    val dataJsJson =
      JsonParser.parseString(File(htmlReportDir, "data.js").readText().removePrefix("const TEST_DATA_SOURCE = ").trim().removeSuffix(";"))
        .asJsonObject
    val envJson = dataJsJson.getAsJsonArray("suiteEnvironments").single().asJsonObject
    assertThat(envJson.get("applicationId").asString).isEqualTo("com.example.journalingapp")
    assertThat(envJson.get("applicationName").asString).isEqualTo("JournalingApp")
    assertThat(envJson.get("versionName").asString).isEqualTo("1.2.0")
    assertThat(envJson.get("versionCode").asInt).isEqualTo(12)

    val firstCaseResultJson =
      dataJsJson
        .getAsJsonArray("modules")
        .single()
        .asJsonObject
        .getAsJsonArray("packages")
        .first { it.asJsonObject.get("name").asString == "com.example" }
        .asJsonObject
        .getAsJsonArray("classes")
        .first { it.asJsonObject.get("name").asString == "SuiteReportWiringTest" }
        .asJsonObject
        .getAsJsonArray("testCases")
        .first { it.asJsonObject.get("name").asString.startsWith("firstCase") }
        .asJsonObject
        .getAsJsonArray("targets")
        .single()
        .asJsonObject
        .getAsJsonArray("testSuiteResults")
        .single()
        .asJsonObject
        .getAsJsonObject("variantResults")
        .getAsJsonObject("debug")
    val logMetaTitles = firstCaseResultJson.getAsJsonArray("logMeta").map { it.asJsonObject.get("title").asString }
    assertThat(logMetaTitles).containsExactly("Host log", "Logcat (source)").inOrder()

    val testDetailJs = File(htmlReportDir, "details/t_0001.js")
    val envDetailJs = File(htmlReportDir, "details/env_01.js")
    assertThat(testDetailJs.isFile).isTrue()
    assertThat(File(htmlReportDir, "details/t_0002.js").exists()).isFalse()
    val testDetailText = testDetailJs.readText()
    assertThat(testDetailText).contains("Backup started\\nRestore completed\\n")
    assertThat(testDetailText).contains("first-case-stdout")
    assertThat(testDetailText).doesNotContain("[[ATTACHMENT|")
    assertThat(File(htmlReportDir, "artifacts/t_0001/host-log.txt").readText()).isEqualTo("Backup started\nRestore completed\n")
    assertThat(File(htmlReportDir, "artifacts/t_0001/logcat-127.0.0.1_6520.txt").readText())
      .isEqualTo("01-01 00:00:00.000 I/BackupManager: OK\n")
    assertThat(envDetailJs.isFile).isTrue()
    assertThat(envDetailJs.readText()).contains("user_session.xml")
    assertThat(File(htmlReportDir, "artifacts/env_01/backup_rules.xml").readText()).contains("secret.xml")
    assertThat(File(htmlReportDir, "artifacts/env_01/data_extraction_rules.xml").readText()).contains("user_session.xml")
  }

  @Test
  fun testBackupTestSuiteExecutionAndAnalytics() {
    // Tests that publish the JUnit report entries of the backup library, like its backups and restores do
    val build = rule.build {
      androidApplication {
        files {
          add(
            "src/myBackup/test/java/com/example/BackupRestoreTest.java",
            """
            package com.example;

            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.TestReporter;

            public class BackupRestoreTest {
              @Test
              public void backupAndRestore(TestReporter reporter) {
                reporter.publishEntry("BackupRestore.transportMode", "LOCAL");
                reporter.publishEntry("BackupRestore.storageDomainCount", "2");
              }

              @Test
              public void expectedRestoreFailure(TestReporter reporter) {
                reporter.publishEntry("BackupRestore.errorCode", "RESTORE_FAILED");
              }
            }
            """
              .trimIndent(),
          )
        }
      }
    }
    val capturer = ProfileCapturer(build.profileDirectory!!, ".trk")
    val events = capturer.captureAndroidEvent {
      build.executor.run(":app:testDebugMyBackupDefaultTestSuite")
    }

    val testRun = backupTestRun(events)
    assertThat(testRun.testInvocationType).isEqualTo(TestRun.TestInvocationType.GRADLE_TEST)
    // The test of the toy engine and the two tests above
    assertThat(testRun.numberOfTestsExecuted).isEqualTo(3)
    assertThat(testRun.backupTestRun.passedTestCount).isEqualTo(3)
    assertThat(testRun.backupTestRun.failedTestCount).isEqualTo(0)
    assertThat(testRun.backupTestRun.backupTestLibraryVersion).isEqualTo(CUSTOM_BACKUP_VERSION)
    assertThat(testRun.backupTestRun.transportMode).isEqualTo(BackupTransportMode.LOCAL_TRANSPORT)
    assertThat(testRun.backupTestRun.storageDomainCount).isEqualTo(2)
    // The error of a test that passes isn't a failure.
    assertThat(testRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.NO_FAILURE)
  }

  @Test
  fun testFailedRunReportsBackupFailureReason() {
    val build = rule.build {
      androidApplication {
        files {
          add(
            "src/myBackup/test/java/com/example/BackupRestoreTest.java",
            """
            package com.example;

            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.TestReporter;

            public class BackupRestoreTest {
              @Test
              public void restoreTimesOut(TestReporter reporter) {
                reporter.publishEntry("BackupRestore.errorCode", "RESTORE_POLL_TIMEOUT");
                throw new AssertionError("Restore timed out");
              }
            }
            """
              .trimIndent(),
          )
        }
      }
    }
    val capturer = ProfileCapturer(build.profileDirectory!!, ".trk")
    val events = capturer.captureAndroidEvent {
      build.executor.expectFailure().run(":app:testDebugMyBackupDefaultTestSuite")
    }

    val testRun = backupTestRun(events)
    assertThat(testRun.backupTestRun.failedTestCount).isEqualTo(1)
    assertThat(testRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.TIMEOUT)
  }

  @Test
  fun testFailedTestsShowTheExceptionMessage() {
    // Runs at Gradle's default log level, as users do: at the fixture's default INFO level, Gradle prints whole exceptions anyway.
    // With the default SHORT exception format, the console would show only "java.lang.Exception at <file>:<line>".
    rule.build.executor
      .withLoggingLevel(LoggingLevel.LIFECYCLE)
      .withArgument("-PtoyEngineFailure=Restore restored no app data")
      .expectFailure()
      .run(":app:testDebugMyBackupDefaultTestSuite")
      .assertOutputContains("java.lang.Exception: Restore restored no app data")
  }

  @Test
  fun testTestsThatJUnitCannotRunFailTheRun() {
    val build = rule.build {
      androidApplication {
        files {
          add(
            "src/myBackup/test/java/com/example/ValueReturningTest.java",
            """
            package com.example;

            public class ValueReturningTest {
              @org.junit.jupiter.api.Test
              public int returnsValue() {
                return 1;
              }
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    // JUnit doesn't run a test method that returns a value, so the run fails instead of passing without it.
    build.executor.expectFailure().run(":app:testDebugMyBackupDefaultTestSuite").assertOutputContains("must not return a value")
  }

  @Test
  fun testSuiteWithoutTestsWarns() {
    // A class for the device, which isn't a test
    val build = rule.build {
      androidApplication {
        files { add("src/myBackup/androidTest/java/com/example/SeedAction.java", "package com.example;\npublic class SeedAction {}") }
      }
    }
    val task = ":app:testDebugMyBackupDefaultTestSuite"
    val warning = "Backup test suite 'myBackup' has no tests. Add JUnit 5 tests to src/myBackup/test/kotlin/ or src/myBackup/test/java/."
    val testFile = build.subProject(":app").resolve("src/myBackup/test/java/com/example/MyBackupTest.java").toFile()
    val test = testFile.readText()
    assertThat(testFile.delete()).isTrue()

    val result = build.executor.run(task)
    result.assertOutputContains(warning)
    assertThat(result.skippedTasks).contains(task)

    testFile.writeText(test)
    build.executor.run(task).assertOutputDoesNotContain(warning)
  }

  @Test
  fun testBackupLibrariesAreOnlyAddedToSuiteClasspaths() {
    // Applying a Kotlin compiler plugin makes AGP create compiler plugin classpaths for the suite's compilations.
    val build = rule.build { androidApplication { applyPlugin(PluginType.COMPOSE_COMPILER_PLUGIN) } }

    build.executor
      .run(":app:dependencies", "--configuration", "debugMyBackupTestRuntimeClasspath")
      .assertOutputContains("androidx.test.backup:backup-host:$CUSTOM_BACKUP_VERSION")
    build.executor
      .run(":app:dependencies", "--configuration", "debugMyBackupAndroidTestRuntimeClasspath")
      .assertOutputContains("androidx.test.backup:backup:$CUSTOM_BACKUP_VERSION")
    val compilerPluginClasspaths =
      listOf("kotlinCompilerPluginClasspathDebugMyBackupTest", "kotlinCompilerPluginClasspathDebugMyBackupAndroidTest")
    for (configuration in compilerPluginClasspaths) {
      build.executor.run(":app:dependencies", "--configuration", configuration).assertOutputDoesNotContain("androidx.test.backup")
    }
  }

  /** Returns the backup test run that [events] report. */
  private fun backupTestRun(events: Collection<AndroidStudioEvent>): TestRun {
    val backupEvents = events.filter {
      it.category == AndroidStudioEvent.EventCategory.TESTS &&
        it.kind == AndroidStudioEvent.EventKind.TEST_RUN &&
        it.testRun.testKind == TestRun.TestKind.BACKUP_TEST
    }
    assertThat(backupEvents).hasSize(1)
    return backupEvents.single().testRun
  }
}

class BackupSuiteCallback : ApplicationComponentCallback {
  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.finalizeDsl { applicationExtension ->
      val backupSuite = applicationExtension.testOptions.backupTests.getByName("myBackup")
      assertThat(backupSuite.backupTestLibraryVersion).isEqualTo(CUSTOM_BACKUP_VERSION)
      assertThat(backupSuite.targetVariants).containsExactly("debug")
    }

    androidComponents.beforeVariants(androidComponents.selector().withBuildType("debug")) { variantBuilder ->
      val listOfTestSuites = variantBuilder.suites.values.joinToString { it.name }
      if (variantBuilder.suites.size != 1) {
        throw RuntimeException("Expected 1 testSuites Tests, got $listOfTestSuites")
      }
      val testSuiteBuilder =
        variantBuilder.suites["myBackup"]
          ?: throw RuntimeException("Cannot find myBackup test suite in test suites : " + variantBuilder.suites.keys.joinToString(", "))

      assertThat(testSuiteBuilder.junitEngineSpec.includeEngines).contains("junit-jupiter")
      val inputs = testSuiteBuilder.junitEngineSpec.inputs
      assertThat(inputs)
        .containsExactly(
          AgpTestSuiteInputParameters.TEST_CLASSES,
          AgpTestSuiteInputParameters.TEST_CLASSPATH,
          AgpTestSuiteInputParameters.MAIN_CLASSES,
          AgpTestSuiteInputParameters.MAIN_CLASSPATH,
          AgpTestSuiteInputParameters.TEST_APKS,
          AgpTestSuiteInputParameters.TESTED_APKS,
        )

      // Remove APK inputs so the test task can execute purely on host without requiring a physical or emulated Android device.
      inputs.remove(AgpTestSuiteInputParameters.TEST_APKS)
      inputs.remove(AgpTestSuiteInputParameters.TESTED_APKS)

      // Add the test fixture engine to execute a dummy test on the host and trigger test listeners
      testSuiteBuilder.junitEngineSpec.includeEngines.add("[engine:toy-junit-engine-for-tests]")
      testSuiteBuilder.junitEngineSpec.enginesDependencies.add(
        "com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}"
      )
      testSuiteBuilder.junitEngineSpec.enginesDependencies.add("org.junit.platform:junit-platform-launcher")
      testSuiteBuilder.junitEngineSpec.enginesDependencies.add("com.test:toy-junit-engine:1.0")
      testSuiteBuilder.junitEngineSpec.enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
    }

    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
      val testSuites = variant as HasTestSuites
      val myBackupTestSuite =
        testSuites.suites["myBackup"]
          ?: throw RuntimeException("Cannot find myBackup test suite in test suites : " + testSuites.suites.keys.joinToString(", "))
      assertThat(myBackupTestSuite).isInstanceOf(TestSuite::class.java)

      // Exercises generated and variant-added static Android res directories alongside main/debug layers.
      val genResTask = project.tasks.register("generateDebugExtraRes", GenerateExtraResTask::class.java)
      variant.sources.res?.addGeneratedSourceDirectory(genResTask, GenerateExtraResTask::outputDir)
      variant.sources.res?.addStaticSourceDirectory("src/extraRes")

      // Makes the toy engine fail when the `toyEngineFailure` Gradle property is set.
      val failure = project.providers.gradleProperty("toyEngineFailure")
      myBackupTestSuite.configureTestTasks {
        failure.orNull?.let { systemProperty("toyEngine.failure", it) }
      }
    }

    // Verify targetVariants.add("debug") excluded the release build type
    androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
      val testSuites = variant as HasTestSuites
      assertThat(testSuites.suites).isEmpty()
    }
  }
}

abstract class GenerateExtraResTask : DefaultTask() {
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val valuesDir = File(outputDir.get().asFile, "values").apply { mkdirs() }
    File(valuesDir, "generated.xml").writeText("<resources><string name=\"generated_token\">ok</string></resources>")
  }
}
