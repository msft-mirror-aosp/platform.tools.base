/*
 * Copyright (C) 2024 The Android Open Source Project
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
import com.android.build.gradle.options.OptionalBooleanOption
import org.junit.Rule
import org.junit.Test

/** Integration test for printing the lint text report to stdout via various options. */
class LintPrintTextReportTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android {
          namespace = "com.example.app"
          lint {
            disable += listOf("AllowBackup", "MissingApplicationIcon", "GradleDependency")
            error += "Fake"
          }
        }
      }
    }

  @Test
  fun testPrintTextReportOption() {
    rule.build.executor.with(OptionalBooleanOption.LINT_PRINT_TEXT_REPORT, false).run("lintDebug").apply {
      assertOutputDoesNotContain("Unknown issue id \"Fake\"")
    }

    // Check that we see the report when -Pandroid.experimental.lint.printTextReport=true is used
    rule.build.executor.with(OptionalBooleanOption.LINT_PRINT_TEXT_REPORT, true).run("lintDebug").apply {
      assertOutputContains("Unknown issue id \"Fake\"")
    }
  }

  @Test
  fun testPrintTextReportOptionOverridesDsl() {
    // Check that OptionalBooleanOption.LINT_PRINT_TEXT_REPORT=false overrides DSL = true
    rule.build.androidApplication().reconfigure { android.lint.printTextReport = true }
    rule.build.executor.with(OptionalBooleanOption.LINT_PRINT_TEXT_REPORT, false).run("lintDebug").apply {
      assertOutputDoesNotContain("Unknown issue id \"Fake\"")
    }

    // Check that OptionalBooleanOption.LINT_PRINT_TEXT_REPORT=true overrides DSL = false
    rule.build.androidApplication().reconfigure { android.lint.printTextReport = false }
    rule.build.executor.with(OptionalBooleanOption.LINT_PRINT_TEXT_REPORT, true).run("lintDebug").apply {
      assertOutputContains("Unknown issue id \"Fake\"")
    }
  }

  @Test
  fun testPrintTextReportDsl() {
    // Check that we see the report when lint.printTextReport = true is used
    rule.build.androidApplication().reconfigure { android.lint.printTextReport = true }
    rule.build.executor.run("lintDebug").apply { assertOutputContains("Unknown issue id \"Fake\"") }
  }
}
