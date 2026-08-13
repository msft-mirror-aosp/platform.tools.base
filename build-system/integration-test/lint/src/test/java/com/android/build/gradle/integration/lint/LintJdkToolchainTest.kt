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

package com.android.build.gradle.integration.lint

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Integration test for verifying Android Lint toolchain DSL and version checks. */
class LintJdkToolchainTest {

  private val java17Path = TestUtils.getJava17Jdk().toFile().canonicalPath.replace("\\", "/")
  private val java21Path = TestUtils.getJava21Jdk().toFile().canonicalPath.replace("\\", "/")

  private val javaExe = if (System.getProperty("os.name").contains("Windows")) "bin/java.exe" else "bin/java"

  /**
   * Converts a JDK home directory path into the expected java executable path string (e.g., `prebuilts/.../bin/java[.exe]`) using
   * platform-native path separators.
   */
  private fun getExpectedJavaPath(fullPath: String): String {
    val normalizedPath = fullPath.replace("\\", "/")
    val prebuiltsIdx = normalizedPath.indexOf("prebuilts/")
    val path =
      if (prebuiltsIdx >= 0) {
        normalizedPath.substring(prebuiltsIdx) + "/$javaExe"
      } else {
        "$normalizedPath/$javaExe"
      }
    return path.replace('/', File.separatorChar)
  }

  @get:Rule
  val rule =
    GradleRule.from {
      settings { applyPlugin(PluginType.ANDROID_SETTINGS) }
      androidApplication(":app") {
        // Configured via DSL in individual tests
      }
      javaLibrary(":standaloneLib") {
        applyPlugin(PluginType.KOTLIN_JVM)
        applyPlugin(PluginType.LINT)
      }
      gradleProperties { add(BooleanOption.RUN_LINT_IN_PROCESS, false) }
    }

  @Test
  fun testLintWithToolchainDsl() {
    val build = rule.build
    build
      .androidApplication(":app")
      .files
      .update("build.gradle")
      .append(
        """
        android.lint.toolchain {
          languageVersion = JavaLanguageVersion.of(21)
        }
        """
          .trimIndent()
      )

    val executor =
      build.executor
        .withFailOnWarning(false)
        .withArgument("--info")
        .withArgument("-Dorg.gradle.java.home=$java17Path")
        .withArgument("-Porg.gradle.java.installations.paths=$java21Path")

    val result = executor.run(":app:lintDebug")

    val expectedJava21 = getExpectedJavaPath(java21Path)
    result.assertOutputContains(expectedJava21)
    result.assertTask(":app:lintAnalyzeDebug").didWork()
  }

  @Test
  fun testStandaloneLintWithToolchainDsl() {
    val build = rule.build
    build
      .javaLibrary(":standaloneLib")
      .files
      .update("build.gradle")
      .append(
        """
        lint.toolchain {
          languageVersion = JavaLanguageVersion.of(21)
        }
        """
          .trimIndent()
      )

    val executor =
      build.executor
        .withFailOnWarning(false)
        .withArgument("--info")
        .withArgument("-Dorg.gradle.java.home=$java17Path")
        .withArgument("-Porg.gradle.java.installations.paths=$java21Path")

    val result = executor.run(":standaloneLib:lint")

    val expectedJava21 = getExpectedJavaPath(java21Path)
    result.assertOutputContains(expectedJava21)
    result.assertTask(":standaloneLib:lintAnalyzeJvmMain").didWork()
  }

  @Test
  fun testLintWithToolchainIncompatibleBytecodeThrowsError() {
    val build = rule.build
    build
      .androidApplication(":app")
      .files
      .update("build.gradle")
      .append(
        """
        android.compileOptions {
          targetCompatibility = JavaVersion.VERSION_21
        }
        android.lint.toolchain {
          languageVersion = JavaLanguageVersion.of(17)
        }
        """
          .trimIndent()
      )

    val executor = build.executor.withFailOnWarning(false).expectFailure().withArgument("-Dorg.gradle.java.home=$java17Path")

    val result = executor.run(":app:lintDebug")
    result.assertErrorContains("cannot analyze project code compiled for Java 21")
  }

  @Test
  fun testLintWithToolchainIncompatibleBaselineThrowsError() {
    val build = rule.build
    build
      .androidApplication(":app")
      .files
      .update("build.gradle")
      .append(
        """
        android.lint.toolchain {
          languageVersion = JavaLanguageVersion.of(11)
        }
        """
          .trimIndent()
      )

    val executor = build.executor.withFailOnWarning(false).expectFailure().withArgument("-Dorg.gradle.java.home=$java17Path")

    val result = executor.run(":app:lintDebug")
    result.assertErrorContains("below AGP's minimum required JDK (17)")
  }

  @Test
  fun testLintWithToolchainMatchingDaemonJdkForcesOutOfProcess() {
    val build = rule.build
    build
      .androidApplication(":app")
      .files
      .update("build.gradle")
      .append(
        """
        android.lint.toolchain {
          languageVersion = JavaLanguageVersion.of(17)
        }
        """
          .trimIndent()
      )

    val executor =
      build.executor
        .withFailOnWarning(false)
        .withArgument("--info")
        .withArgument("-Dorg.gradle.java.home=$java17Path")
        .with(BooleanOption.RUN_LINT_IN_PROCESS, true)

    val result = executor.run(":app:lintDebug")

    val expectedJava17 = getExpectedJavaPath(java17Path)
    result.assertOutputContains(expectedJava17)
    result.assertOutputContains("Android Lint is running out of process using toolchain launcher")
    result.assertTask(":app:lintAnalyzeDebug").didWork()
  }

  @Test
  fun testSettingsLintWithToolchainDsl() {
    val build = rule.build
    build.directory
      .resolve("settings.gradle")
      .toFile()
      .appendText(
        """

        android {
            lint {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(21)
                }
            }
        }
        """
          .trimIndent()
      )

    val executor =
      build.executor
        .withFailOnWarning(false)
        .withArgument("--info")
        .withArgument("-Dorg.gradle.java.home=$java17Path")
        .withArgument("-Porg.gradle.java.installations.paths=$java21Path")

    val result = executor.run(":app:lintDebug")

    val expectedJava21 = getExpectedJavaPath(java21Path)
    result.assertOutputContains(expectedJava21)
    result.assertTask(":app:lintAnalyzeDebug").didWork()
  }

  @Test
  fun testLintWithToolchainPropertyDsl() {
    val build = rule.build
    build
      .androidApplication(":app")
      .files
      .update("build.gradle")
      .append(
        """
        android.lint.toolchain.languageVersion = JavaLanguageVersion.of(21)
        """
          .trimIndent()
      )

    val executor =
      build.executor
        .withFailOnWarning(false)
        .withArgument("--info")
        .withArgument("-Dorg.gradle.java.home=$java17Path")
        .withArgument("-Porg.gradle.java.installations.paths=$java21Path")

    val result = executor.run(":app:lintDebug")

    val expectedJava21 = getExpectedJavaPath(java21Path)
    result.assertOutputContains(expectedJava21)
    result.assertTask(":app:lintAnalyzeDebug").didWork()
  }
}
