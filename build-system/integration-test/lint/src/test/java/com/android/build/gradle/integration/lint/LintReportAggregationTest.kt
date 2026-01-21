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
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** Integration test for lint report aggregation. */
class LintReportAggregationTest {

  @get:Rule
  val rule = GradleRule.configure().from { androidApplication(":app") { android { namespace = "com.example.android.lint.kotlin" } } }

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
}
