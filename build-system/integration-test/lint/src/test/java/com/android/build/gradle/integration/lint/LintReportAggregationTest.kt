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
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** Integration test for lint report aggregation. */
class LintReportAggregationTest {

  @get:Rule
  val rule =
    GradleRule.configure().from {
      androidApplication(":app") {
        android {
          namespace = "com.example.app"
          defaultConfig { minSdk = 24 }
          lint {
            checkDependencies = false
            abortOnError = false
            enable += "SdCardPath"
            textReport = true
          }
        }
        files.add(
          "src/main/java/com/example/app/App.java",
          """
          package com.example.app;
          public class App {
              public void foo() {
                  String s = "/sdcard/foo";
              }
          }
          """
            .trimIndent(),
        )
        dependencies { implementation(project(":lib")) }
      }
      androidLibrary(":lib") {
        android {
          namespace = "com.example.lib"
          defaultConfig { minSdk = 21 }
          lint {
            abortOnError = false
            enable += "AuthLeak"
            textReport = true
          }
        }
        files.add(
          "src/main/java/com/example/lib/Lib.java",
          """
          package com.example.lib;
          public class Lib {
              public void bar() {
                  String s = "http://user:password@host/foo";
              }
          }
          """
            .trimIndent(),
        )
      }
    }

  @Test
  fun testLintReportAggregationEnabled() {
    val result = rule.build.executor.with(BooleanOption.LINT_REPORT_AGGREGATION, true).run(":app:lintDebug")

    assertThat(result.tasks).contains(":app:createLocalLintReportDebug")
    assertThat(result.tasks).contains(":app:createAggregatedLintReportDebug")
    assertThat(result.tasks).doesNotContain(":app:lintReportDebug")
  }

  @Test
  fun testLintReportAggregationDisabled() {
    val result = rule.build.executor.with(BooleanOption.LINT_REPORT_AGGREGATION, false).run(":app:lintDebug")

    assertThat(result.tasks).contains(":app:lintReportDebug")
    assertThat(result.tasks).doesNotContain(":app:createLocalLintReportDebug")
    assertThat(result.tasks).doesNotContain(":app:createAggregatedLintReportDebug")
  }

  @Test
  fun testLintReportAggregationSeparation() {
    verifyLintReportAggregationSeparation()
  }

  @Test
  fun testLintReportAggregationSeparationWithConditionallyReportedLintIssue() {
    rule.build.androidLibrary(":lib").reconfigure {
      android {
        lint {
          enable.clear()
          enable += "NewApi"
        }
      }
      files
        .update("src/main/java/com/example/lib/Lib.java")
        .replaceWith(
          """
          package com.example.lib;
          import java.util.List;
          public class Lib {
              public void bar(List<String> list) {
                  list.removeIf(s -> s.isEmpty());
              }
          }
          """
            .trimIndent()
        )
    }

    // Run lint on both lib and app with report aggregation enabled
    rule.build.executor.with(BooleanOption.LINT_REPORT_AGGREGATION, true).run(":lib:lintDebug", ":app:lintDebug")

    val libLocalReport = rule.build.directory.resolve("lib/build/reports/local-lint-results-debug.txt")
    val appLocalReport = rule.build.directory.resolve("app/build/reports/local-lint-results-debug.txt")
    val aggregatedReport = rule.build.directory.resolve("app/build/reports/aggregated-lint-results-debug.txt")

    // Lib issue (NewApi) should be present in lib's local report
    assertThat(libLocalReport).exists()
    assertThat(libLocalReport).contains("NewApi")

    // App's local report should NOT contain it (because it doesn't check dependencies)
    assertThat(appLocalReport).exists()
    assertThat(appLocalReport).doesNotContain("NewApi")
    assertThat(appLocalReport).contains("SdCardPath")

    // Aggregated report should NOT contain the issue from lib because it's "conditional"
    // and the app's minSdk (24) satisfies the requirement for the lib's usage of API 24.
    assertThat(aggregatedReport).exists()
    assertThat(aggregatedReport).doesNotContain("NewApi")
    assertThat(aggregatedReport).contains("SdCardPath")
  }

  @Test
  fun testLintReportAggregationSeparationWithCheckDependencies() {
    rule.build.androidApplication(":app").reconfigure { android { lint { checkDependencies = true } } }
    verifyLintReportAggregationSeparation()
  }

  @Test
  fun testAppUpdateLintBaseline() {
    checkLintBaselineUpdate(task = ":app:updateLintBaselineDebug", expectAppBaseline = true, expectLibBaseline = false)
  }

  @Test
  fun testLibUpdateLintBaseline() {
    checkLintBaselineUpdate(task = ":lib:updateLintBaselineDebug", expectAppBaseline = false, expectLibBaseline = true)
  }

  @Test
  fun testTopLevelUpdateLintBaseline() {
    checkLintBaselineUpdate(task = "updateLintBaseline", expectAppBaseline = true, expectLibBaseline = true)
  }

  private fun verifyLintReportAggregationSeparation() {
    rule.build.executor.with(BooleanOption.LINT_REPORT_AGGREGATION, true).run(":app:lintDebug")
    val localReport = rule.build.directory.resolve("app/build/reports/local-lint-results-debug.txt")
    assertThat(localReport).exists()
    // App issue (SdCardPath) should be present
    assertThat(localReport).contains("SdCardPath")
    // Lib issue (AuthLeak) should NOT be present
    assertThat(localReport).doesNotContain("AuthLeak")

    val aggregatedReport = rule.build.directory.resolve("app/build/reports/aggregated-lint-results-debug.txt")
    assertThat(aggregatedReport).exists()
    // App issue (SdCardPath) should be present
    assertThat(aggregatedReport).contains("SdCardPath")
    // Lib issue (AuthLeak) should be present
    assertThat(aggregatedReport).contains("AuthLeak")
  }

  private fun checkLintBaselineUpdate(task: String, expectAppBaseline: Boolean, expectLibBaseline: Boolean) {
    val build = rule.build
    val appBaseline = build.directory.resolve("app/lint-baseline.xml")
    val libBaseline = build.directory.resolve("lib/lint-baseline.xml")

    assertThat(appBaseline).doesNotExist()
    assertThat(libBaseline).doesNotExist()

    build.executor.with(BooleanOption.LINT_REPORT_AGGREGATION, true).with(BooleanOption.LINT_DEFAULT_BASELINE_CONVENTION, true).run(task)

    if (expectAppBaseline) {
      assertThat(appBaseline).exists()
      assertThat(appBaseline).contains("SdCardPath")
      assertThat(appBaseline).doesNotContain("AuthLeak")
    } else {
      assertThat(appBaseline).doesNotExist()
    }

    if (expectLibBaseline) {
      assertThat(libBaseline).exists()
      assertThat(libBaseline).contains("AuthLeak")
      assertThat(libBaseline).doesNotContain("SdCardPath")
    } else {
      assertThat(libBaseline).doesNotExist()
    }
  }

  @Test
  fun testUpdateLintBaselineWithExplicitBaseline() {
    rule.build.androidApplication(":app").reconfigure { android { lint { baseline = java.io.File("explicit-baseline.xml") } } }
    val build = rule.build
    val explicitBaseline = build.directory.resolve("app/explicit-baseline.xml")
    val defaultBaseline = build.directory.resolve("app/lint-baseline.xml")

    assertThat(explicitBaseline).doesNotExist()
    assertThat(defaultBaseline).doesNotExist()

    build.executor.with(BooleanOption.LINT_REPORT_AGGREGATION, true).run(":app:updateLintBaselineDebug")

    assertThat(explicitBaseline).exists()
    assertThat(defaultBaseline).doesNotExist()
  }
}
