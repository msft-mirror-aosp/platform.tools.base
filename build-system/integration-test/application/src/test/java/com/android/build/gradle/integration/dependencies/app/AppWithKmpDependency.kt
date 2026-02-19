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

package com.android.build.gradle.integration.dependencies.app

import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.builder.model.v2.ide.SyncIssue
import org.junit.Rule
import org.junit.Test

class AppWithKmpDependency : ModelComparator() {

  @get:Rule
  val rule =
    GradleRule.configure().disableBrokenBuiltInKotlinOptOutChecks().from {
      androidApplication {
        android { defaultConfig.minSdk = 21 }
        // this is a kmp dependency published with -android and -desktop variants
        dependencies { implementation("androidx.lifecycle:lifecycle-runtime:2.8.0-alpha02") }
      }
    }

  @Test
  fun `test VariantDependencies model with kotlin attribute`() {
    val result = rule.build.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels(variantName = "debug")

    with(result)
      .compareVariantDependencies(projectAction = { getProject(DEFAULT_APP_PATH) }, goldenFile = "app_VariantDependencies_android")
  }

  @Test
  fun checkPackagedClassesContainAndroidSpecificClass() {
    val build = rule.build
    build.executor.run(":app:assembleDebug")

    build.androidApplication().assertApk(ApkSelector.DEBUG) { classes().contains("androidx/lifecycle/ReportFragment") }
  }
}
