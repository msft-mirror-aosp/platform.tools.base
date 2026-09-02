/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.build.gradle.integration.gradleapi

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.runner.FilterableParameterized
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.SyncIssue
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(FilterableParameterized::class)
class ObsoleteApiTest(private val provider: TestProjectProvider) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun setUps() =
      listOf(
        TestProjectProvider("Kotlin") {
          androidKotlinApplication {}
        },
        TestProjectProvider("Java") {
          androidJavaApplication {}
        },
      )
  }

  @get:Rule val rule = GradleRule.configure().disableBrokenBuiltInKotlinOptOutChecks().from(configAction = provider.configAction)

  @Test
  fun `test via model`() {
    val build = rule.build
    val model =
      build.modelBuilder
        // legacy incremental transform uses deprecated gradle api
        .withFailOnWarning(false)
        .with(BooleanOption.DEBUG_OBSOLETE_API, true)
        .suppressOptionWarning(BooleanOption.BUILT_IN_KOTLIN)
        .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
        .fetchModels()
    val issueModel = model.container.singleProjectInfo.issues ?: throw RuntimeException("failed to get issue model")
    val syncIssues = issueModel.syncIssues

    Truth.assertThat(syncIssues).hasSize(0)
  }

  @Test
  fun `Test from command line`() {
    val build = rule.build
    val result =
      build.executor
        // legacy incremental transform uses deprecated gradle api
        .withFailOnWarning(false)
        .with(BooleanOption.DEBUG_OBSOLETE_API, true)
        .run("help")

    result.stdout.use {
      ScannerSubject.assertThat(it).doesNotContain("API 'variant.getJavaCompile()' is obsolete")
      ScannerSubject.assertThat(it).doesNotContain("API 'applicationVariants' is obsolete")
    }
  }
}

class TestProjectProvider(val name: String, val configAction: GradleBuildDefinition.() -> Unit) {
  override fun toString(): String {
    return name
  }
}
