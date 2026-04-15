/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.testing

import com.android.build.gradle.integration.common.fixture.SUPPORT_LIB_MIN_SDK
import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThatApk
import org.junit.Rule
import org.junit.Test

class SeparateTestWithAarDependencyTest : ModelComparator() {

  @get:Rule
  val project =
    GradleRule.fromProject("separateTestModule") {
      androidApplication(":app") {
        android {
          namespace = "com.android.tests.basic"
          compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          defaultConfig { minSdk = SUPPORT_LIB_MIN_SDK }
        }
        dependencies {
          api("androidx.appcompat:appcompat:1.6.1")
          api("androidx.legacy:legacy-support-v4:1.0.0")
          api("androidx.media:media:1.6.0")
        }
      }
      androidTest(":test") {
        android {
          namespace = "com.example.android.testing.blueprint.test"
          compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          targetProjectPath = ":app"
        }
        dependencies {
          implementation("junit:junit:4.12")
          implementation("androidx.test:runner:1.4.0-alpha06")
          implementation("androidx.test:rules:1.4.0-alpha06")
        }
      }
      androidLibrary(":lib") {
        android {
          namespace = "com.android.tests.lib"
          compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          defaultConfig { minSdk = SUPPORT_LIB_MIN_SDK }
        }
      }
    }

  @Test
  fun `test VariantDependencies model`() {
    val result = project.build.modelBuilder.fetchModels(variantName = "debug")

    with(result).compareVariantDependencies(projectAction = { getProject(":test") }, goldenFile = "test_VariantDependencies")
  }

  @Test
  fun checkTestApk() {
    project.build.executor.run("assembleDebug")

    val apkFile = project.build.androidTest(":test").getApkLocationForCopy(ApkSelector.DEBUG).toFile()
    val apkSubject = assertThatApk(apkFile)
    apkSubject.named("Test app shouldn't contain app code").doesNotContainClass("Lcom/android/tests/basic/Main;")
    apkSubject.named("Test app shouldn't contain app layout").doesNotContainResource("layout/main.xml")
    apkSubject.named("Test app shouldn't contain app dependency code").doesNotContainClass("Landroid/support/v7/app/ActionBar;")
    apkSubject.named("Test app shouldn't contain app dependency resources").doesNotContainResource("layout/abc_action_bar_title_item.xml")
  }
}
