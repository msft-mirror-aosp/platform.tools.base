/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.Version
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** Test for updating lint baselines using the updateLintBaseline task. */
class UpdateLintBaselineTest {

  @get:Rule
  val project =
    GradleRule.from("lintBaseline") {
      androidApplication(":app") {
        android {
          namespace = "com.example.android.lint.kotlin"
          compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION

          defaultConfig {
            minSdk = 15
            targetSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          }

          lint {
            xmlReport = true
            xmlOutput = projectDotFile("lint-report.xml")
            error += "SyntheticAccessor"
            warningsAsErrors = true
            baseline = projectDotFile("lint-baseline.xml")
          }
        }
      }
    }

  @get:Rule
  val projectWithoutIssues =
    GradleRule.from("projectWithoutIssues") {
      androidApplication(":app") { android { namespace = "com.example.android.lint.kotlin.noissues" } }
    }

  @Test
  fun checkUpdateLintBaseline() {
    // This test runs updateLintBaseline in 7 scenarios:
    //   (1)  when there is no existing baseline,
    //   (2)  when there is already a correct existing baseline, and there are no changes since
    //        the last run,
    //   (3)  when there is already a correct existing baseline, and there was a clean since the
    //        last run,
    //   (4)  when there is already a correct existing baseline with old lint/AGP versions,
    //   (5)  when there is already an incorrect existing baseline,
    //   (6)  when a user runs updateLintBase and lint at the same time.
    //   (7)  when there is no baseline file specified.

    // First test the case when there is no existing baseline.
    val baselineFile = project.build.androidApplication(":app").resolve("lint-baseline.xml").toFile()
    PathSubject.assertThat(baselineFile).doesNotExist()
    project.build.executor.run(":app:updateLintBaseline").apply {
      assertTask(":app:lintAnalyzeDebug").didWork()
      assertTask(":app:updateLintBaselineDebug").didWork()
    }
    PathSubject.assertThat(baselineFile).exists()

    val baselineFileContents = baselineFile.readBytes()

    // Then test the case when there is already a correct existing baseline, and there are no
    // changes since the last run. updateLintBaseline should still do work because it is never
    // UP-TO-DATE.
    project.build.executor.run(":app:updateLintBaseline").apply {
      assertTask(":app:lintAnalyzeDebug").wasUpToDate()
      assertTask(":app:updateLintBaselineDebug").didWork()
    }
    PathSubject.assertThat(baselineFile).exists()
    assertThat(baselineFile.readBytes()).isEqualTo(baselineFileContents)

    // Then test the case when there is already a correct existing baseline, and there was a
    // clean since the last run.
    project.build.executor.run("clean")
    PathSubject.assertThat(baselineFile).exists()
    assertThat(baselineFile.readBytes()).isEqualTo(baselineFileContents)
    project.build.executor.run(":app:updateLintBaseline").apply {
      assertTask(":app:lintAnalyzeDebug").didWork()
      assertTask(":app:updateLintBaselineDebug").didWork()
    }
    PathSubject.assertThat(baselineFile).exists()
    assertThat(baselineFile.readBytes()).isEqualTo(baselineFileContents)

    // Then test the case when there is already a correct existing baseline from a previous
    // AGP/lint version. In this case, the new baseline file should not change (b/248338457).
    TestFileUtils.searchAndReplace(baselineFile, Version.ANDROID_GRADLE_PLUGIN_VERSION, "7.3.0")
    val previousVersionsBaselineFileContents = baselineFile.readBytes()
    assertThat(previousVersionsBaselineFileContents).isNotEqualTo(baselineFileContents)
    project.build.executor.run(":app:updateLintBaseline").apply {
      assertTask(":app:lintAnalyzeDebug").wasUpToDate()
      assertTask(":app:updateLintBaselineDebug").didWork()
    }
    PathSubject.assertThat(baselineFile).exists()
    assertThat(baselineFile.readBytes()).isEqualTo(previousVersionsBaselineFileContents)

    // Then test the case when there is already an incorrect existing baseline.
    baselineFile.writeText("invalid")
    assertThat(baselineFile.readBytes()).isNotEqualTo(baselineFileContents)
    project.build.executor.run(":app:updateLintBaseline").apply {
      assertTask(":app:lintAnalyzeDebug").wasUpToDate()
      assertTask(":app:updateLintBaselineDebug").didWork()
    }
    PathSubject.assertThat(baselineFile).exists()
    assertThat(baselineFile.readBytes()).isEqualTo(baselineFileContents)

    // Then test the case when a user runs updateLintBaseline and lint at the same time.
    project.build.executor.run("updateLintBaseline", "lint").apply {
      assertOutputDoesNotContain("Gradle detected a problem")
      assertTask(":app:lintAnalyzeDebug").wasUpToDate()
      assertTask(":app:updateLintBaselineDebug").didWork()
    }
    PathSubject.assertThat(baselineFile).exists()
    assertThat(baselineFile.readBytes()).isEqualTo(baselineFileContents)

    // Then test the case when there is no baseline file specified.
    assertThat(baselineFile.delete()).isTrue()
    project.build.androidApplication(":app").reconfigure {
      resetAndroidDsl()
      android {
        namespace = "com.example.android.lint.kotlin"
        compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION

        defaultConfig {
          minSdk = 15
          targetSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
        }

        lint {
          xmlReport = true
          xmlOutput = projectDotFile("lint-report.xml")
          error += "SyntheticAccessor"
          warningsAsErrors = true
        }
      }
    }

    project.build.executor.run(":app:updateLintBaseline").apply {
      assertTask(":app:lintAnalyzeDebug").didWork()
      assertTask(":app:updateLintBaselineDebug").didWork()
      assertOutputContains(
        """
        No baseline file is specified, so no baseline file will be created.

        Please specify a baseline file in the build.gradle file like so:

        ```
        android {
            lint {
                baseline = file("lint-baseline.xml")
            }
        }
        ```
        """
          .trimIndent()
      )
    }
    PathSubject.assertThat(baselineFile).doesNotExist()
  }

  @Test
  fun testMissingBaselineIsEmptyBaseline() {
    // Test that android.experimental.lint.missingBaselineIsEmptyBaseline has the desired
    // effects when running the updateLintBaseline and lint tasks.
    projectWithoutIssues.build.androidApplication(":app").reconfigure {
      android.lint {
        disable += listOf("MissingApplicationIcon", "GradleDependency")
        baseline = projectDotFile("lint-baseline.xml")
      }
    }

    // First run updateLintBaseline without the boolean flag and check that an empty baseline
    // file is written.
    val baselineFile = projectWithoutIssues.build.androidApplication(":app").resolve("lint-baseline.xml").toFile()
    PathSubject.assertThat(baselineFile).doesNotExist()
    projectWithoutIssues.build.executor.run("updateLintBaseline")
    PathSubject.assertThat(baselineFile).exists()
    PathSubject.assertThat(baselineFile).doesNotContain("</issue>")

    // Then run updateLinBaseline with the boolean flag and check that the baseline file is
    // deleted.
    projectWithoutIssues.build.executor.with(BooleanOption.MISSING_LINT_BASELINE_IS_EMPTY_BASELINE, true).run("updateLintBaseline")
    PathSubject.assertThat(baselineFile).doesNotExist()

    // Then run lint twice with the boolean flag and check that the lint reporting task is
    // up-to-date the second time. Regression test for b/237813416.
    projectWithoutIssues.build.executor.with(BooleanOption.MISSING_LINT_BASELINE_IS_EMPTY_BASELINE, true).run("lint").apply {
      assertTask(":app:lintReportDebug").didWork()
    }
    PathSubject.assertThat(baselineFile).doesNotExist()
    projectWithoutIssues.build.executor.with(BooleanOption.MISSING_LINT_BASELINE_IS_EMPTY_BASELINE, true).run("lint").apply {
      assertTask(":app:lintReportDebug").wasUpToDate()
    }

    // Finally, run lint with the boolean flag and without a baseline file when there is an
    // issue, in which case the build should fail.
    projectWithoutIssues.build.androidApplication(":app").reconfigure {
      android.lint {
        error += listOf("MissingApplicationIcon", "GradleDependency")
        disable -= listOf("MissingApplicationIcon", "GradleDependency")
      }
    }
    projectWithoutIssues.build.executor
      .with(BooleanOption.MISSING_LINT_BASELINE_IS_EMPTY_BASELINE, true)
      .expectFailure()
      .run("lint")
      .assertErrorContains("MissingApplicationIcon")
  }

  /** Test that android.lint.baselineOmitLineNumbers has the desired effect. */
  @Test
  fun testOmitLineNumbers() {
    // First run updateLintBaseline without the boolean flag and check that the baseline file
    // specifies line numbers.
    val baselineFile = project.build.androidApplication(":app").resolve("lint-baseline.xml").toFile()
    PathSubject.assertThat(baselineFile).doesNotExist()
    project.build.executor.run(":app:updateLintBaseline")
    PathSubject.assertThat(baselineFile).exists()
    PathSubject.assertThat(baselineFile).contains("line=")

    // Then run updateLinBaseline with the boolean flag and check that the baseline file does
    // not specify line numbers.
    project.build.executor.with(BooleanOption.LINT_BASELINE_OMIT_LINE_NUMBERS, true).run(":app:updateLintBaseline")
    PathSubject.assertThat(baselineFile).exists()
    PathSubject.assertThat(baselineFile).doesNotContain("line=")
  }
}
