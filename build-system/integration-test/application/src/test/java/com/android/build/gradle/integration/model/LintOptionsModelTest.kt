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

package com.android.build.gradle.integration.model

import com.android.build.gradle.integration.common.fixture.model.toValueString
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import java.io.File
import org.junit.Rule
import org.junit.Test

class LintOptionsModelTest {
  @get:Rule val rule = GradleRule.from { androidApplication(":app") {} }

  @Test
  fun `test baseline convention disabled by default`() {
    val result = rule.build.modelBuilder.fetchModels()
    val androidDsl = result.container.getProject(":app").androidDsl ?: throw RuntimeException("No AndroidDsl model")
    Truth.assertThat(androidDsl.lintOptions?.baseline).isNull()
  }

  @Test
  fun `test baseline convention enabled`() {
    val result = rule.build.modelBuilder.with(BooleanOption.LINT_DEFAULT_BASELINE_CONVENTION, true).fetchModels()
    val androidDsl = result.container.getProject(":app").androidDsl ?: throw RuntimeException("No AndroidDsl model")
    // The file does not exist on disk, so it should have the {!} suffix.
    Truth.assertThat(androidDsl.lintOptions?.baseline?.toValueString(result.normalizer)).isEqualTo("{PROJECT}/app/lint-baseline.xml{!}")
  }

  @Test
  fun `test baseline convention enabled and file exists`() {
    val build = rule.build { androidApplication(":app") { files { add("lint-baseline.xml", "<baseline/>") } } }
    val result = build.modelBuilder.with(BooleanOption.LINT_DEFAULT_BASELINE_CONVENTION, true).fetchModels()
    val androidDsl = result.container.getProject(":app").androidDsl ?: throw RuntimeException("No AndroidDsl model")
    // The file exists on disk, so it should have the {F} suffix.
    Truth.assertThat(androidDsl.lintOptions?.baseline?.toValueString(result.normalizer)).isEqualTo("{PROJECT}/app/lint-baseline.xml{F}")
  }

  @Test
  fun `test baseline explicit in DSL overrides convention`() {
    val build = rule.build { androidApplication(":app") { android { lint { baseline = File("explicit-baseline.xml") } } } }
    val result = build.modelBuilder.with(BooleanOption.LINT_DEFAULT_BASELINE_CONVENTION, true).fetchModels()
    val androidDsl = result.container.getProject(":app").androidDsl ?: throw RuntimeException("No AndroidDsl model")
    // The file does not exist on disk, so it should have the {!} suffix.
    Truth.assertThat(androidDsl.lintOptions?.baseline?.toValueString(result.normalizer)).isEqualTo("{PROJECT}/app/explicit-baseline.xml{!}")
  }

  @Test
  fun `test baseline convention in multiple modules`() {
    val build = rule.build { androidLibrary(":lib") {} }
    val result = build.modelBuilder.with(BooleanOption.LINT_DEFAULT_BASELINE_CONVENTION, true).fetchModels()

    val appDsl = result.container.getProject(":app").androidDsl!!
    val libDsl = result.container.getProject(":lib").androidDsl!!

    Truth.assertThat(appDsl.lintOptions?.baseline?.toValueString(result.normalizer)).isEqualTo("{PROJECT}/app/lint-baseline.xml{!}")
    Truth.assertThat(libDsl.lintOptions?.baseline?.toValueString(result.normalizer)).isEqualTo("{PROJECT}/lib/lint-baseline.xml{!}")
  }
}
