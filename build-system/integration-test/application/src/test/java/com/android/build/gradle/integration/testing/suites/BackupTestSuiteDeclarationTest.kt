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
import com.google.common.truth.Truth
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.TestRun
import com.google.wireless.android.sdk.stats.TestRun.BackupTestRun.BackupFailureReason
import com.google.wireless.android.sdk.stats.TestRun.BackupTestRun.BackupTransportMode
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

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
    Truth.assertThat(loggingFile.readText()).contains("System property : androidx.test.backup.device.serial = emulator-5554")

    // Selecting another device reuses the configuration cache, and the new value still reaches the test JVM.
    runWithSerial("emulator-5556").assertOutputContains("Reusing configuration cache.")
    Truth.assertThat(loggingFile.readText()).contains("System property : androidx.test.backup.device.serial = emulator-5556")

    // Values reach the test JVM unchanged, including '=' and spaces.
    runWithSerial("a=b c")
    Truth.assertThat(loggingFile.readText()).contains("System property : androidx.test.backup.device.serial = a=b c")
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
    Truth.assertThat(testRun.testInvocationType).isEqualTo(TestRun.TestInvocationType.GRADLE_TEST)
    // The test of the toy engine and the two tests above
    Truth.assertThat(testRun.numberOfTestsExecuted).isEqualTo(3)
    Truth.assertThat(testRun.backupTestRun.passedTestCount).isEqualTo(3)
    Truth.assertThat(testRun.backupTestRun.failedTestCount).isEqualTo(0)
    Truth.assertThat(testRun.backupTestRun.backupTestLibraryVersion).isEqualTo(CUSTOM_BACKUP_VERSION)
    Truth.assertThat(testRun.backupTestRun.transportMode).isEqualTo(BackupTransportMode.LOCAL_TRANSPORT)
    Truth.assertThat(testRun.backupTestRun.storageDomainCount).isEqualTo(2)
    // The error of a test that passes isn't a failure.
    Truth.assertThat(testRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.NO_FAILURE)
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
    Truth.assertThat(testRun.backupTestRun.failedTestCount).isEqualTo(1)
    Truth.assertThat(testRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.TIMEOUT)
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
    Truth.assertThat(backupEvents).hasSize(1)
    return backupEvents.single().testRun
  }
}

class BackupSuiteCallback : ApplicationComponentCallback {
  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.finalizeDsl { applicationExtension ->
      val backupSuite = applicationExtension.testOptions.backupTests.getByName("myBackup")
      Truth.assertThat(backupSuite.backupTestLibraryVersion).isEqualTo(CUSTOM_BACKUP_VERSION)
      Truth.assertThat(backupSuite.targetVariants).containsExactly("debug")
    }

    androidComponents.beforeVariants(androidComponents.selector().withBuildType("debug")) { variantBuilder ->
      val listOfTestSuites = variantBuilder.suites.values.joinToString { it.name }
      if (variantBuilder.suites.size != 1) {
        throw RuntimeException("Expected 1 testSuites Tests, got $listOfTestSuites")
      }
      val testSuiteBuilder =
        variantBuilder.suites["myBackup"]
          ?: throw RuntimeException("Cannot find myBackup test suite in test suites : " + variantBuilder.suites.keys.joinToString(", "))

      Truth.assertThat(testSuiteBuilder.junitEngineSpec.includeEngines).contains("junit-jupiter")
      val inputs = testSuiteBuilder.junitEngineSpec.inputs
      Truth.assertThat(inputs)
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
      Truth.assertThat(myBackupTestSuite).isInstanceOf(TestSuite::class.java)

      // Makes the toy engine fail its test with the message of the toyEngineFailure Gradle property, when it is set.
      val failure = project.providers.gradleProperty("toyEngineFailure")
      myBackupTestSuite.configureTestTasks { failure.orNull?.let { systemProperty("toyEngine.failure", it) } }
    }

    // Verify targetVariants.add("debug") excluded the release build type
    androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
      val testSuites = variant as HasTestSuites
      Truth.assertThat(testSuites.suites).isEmpty()
    }
  }
}
