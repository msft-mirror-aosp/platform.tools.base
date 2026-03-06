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

package com.android.build.gradle.integration.lint

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** Integration test for disabling lint on a variant. */
class LintDisableVariantTest {

  @get:Rule val rule = GradleRule.configure().from { androidApplication(":app") { android { namespace = "com.example.app" } } }

  @Test
  fun testDisableLintOnVariant() {
    rule.build.androidApplication(":app").files.update("build.gradle") {
      append(
        """
        androidComponents {
            beforeVariants(selector().withName("debug"), { variantBuilder ->
                variantBuilder.enableLint = false
            })
        }
        """
          .trimIndent()
      )
    }

    val result = rule.build.executor.run(":app:lintRelease")
    assertThat(result.tasks).contains(":app:lintReportRelease")

    val resultDebug = rule.build.executor.expectFailure().run(":app:lintDebug")
    resultDebug.assertErrorContains("task 'lintDebug' not found in project ':app'")
  }

  @Test
  fun testDefaultVariantSkippedIfDisabled() {
    rule.build.androidApplication(":app").files.update("build.gradle") {
      append(
        """
        android {
            buildTypes {
                debug {
                    isDefault = true
                }
                release {}
            }
        }
        androidComponents {
            beforeVariants(selector().withName("debug"), { variantBuilder ->
                variantBuilder.enableLint = false
            })
        }
        """
          .trimIndent()
      )
    }

    // "lint" should now depend on "lintRelease" instead of "lintDebug" because "debug" is disabled for lint
    val result = rule.build.executor.run(":app:lint")
    assertThat(result.tasks).contains(":app:lintReportRelease")
    assertThat(result.tasks).doesNotContain(":app:lintReportDebug")
  }

  @Test
  fun testAllVariantsDisabled() {
    rule.build.androidApplication(":app").files.update("build.gradle") {
      append(
        """
        androidComponents {
            beforeVariants(selector().all(), { variantBuilder ->
                variantBuilder.enableLint = false
            })
        }
        """
          .trimIndent()
      )
    }

    // "lint" should have no variant-specific lint dependencies
    val result = rule.build.executor.run(":app:lint")
    assertThat(result.tasks).doesNotContain(":app:lintReportDebug")
    assertThat(result.tasks).doesNotContain(":app:lintReportRelease")
  }
}
