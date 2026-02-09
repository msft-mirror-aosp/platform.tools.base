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
import java.io.File
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
          lint { enable += "AuthLeak" }
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
  fun testLintReportAggregationSeparationWithCheckDependencies() {
    rule.build.androidApplication(":app").reconfigure { android { lint { checkDependencies = true } } }
    verifyLintReportAggregationSeparation()
  }

  @Test
  fun testAppUpdateLintBaseline() {
    configureBaseline()
    checkLintBaselineUpdate(task = ":app:updateLintBaselineDebug", expectAppBaseline = true, expectLibBaseline = false)
  }

  @Test
  fun testLibUpdateLintBaseline() {
    configureBaseline()
    checkLintBaselineUpdate(task = ":lib:updateLintBaselineDebug", expectAppBaseline = false, expectLibBaseline = true)
  }

  @Test
  fun testTopLevelUpdateLintBaseline() {
    configureBaseline()
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

  private fun configureBaseline() {
    rule.build.androidApplication(":app").reconfigure { android { lint { baseline = File("lint-baseline.xml") } } }
    rule.build.androidLibrary(":lib").reconfigure { android { lint { baseline = File("lint-baseline.xml") } } }
  }

  private fun checkLintBaselineUpdate(task: String, expectAppBaseline: Boolean, expectLibBaseline: Boolean) {
    val build = rule.build
    val appBaseline = build.directory.resolve("app/lint-baseline.xml")
    val libBaseline = build.directory.resolve("lib/lint-baseline.xml")

    assertThat(appBaseline).doesNotExist()
    assertThat(libBaseline).doesNotExist()

    build.executor.with(BooleanOption.LINT_REPORT_AGGREGATION, true).run(task)

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
}
