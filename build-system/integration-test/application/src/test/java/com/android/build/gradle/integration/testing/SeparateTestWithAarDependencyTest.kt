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

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.SUPPORT_LIB_MIN_SDK
import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThatApk
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.testutils.apk.Apk
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SeparateTestWithAarDependencyTest : ModelComparator() {

  @get:Rule val project = GradleTestProject.builder().fromTestProject("separateTestModule").disableBuiltInKotlin().create()

  @Before
  fun setUp() {
    TestFileUtils.appendToFile(
      project.getSubproject(":app").buildFile,
      """
                apply plugin: "com.android.application"
                android {
                    compileSdkVersion ${GradleTestProject.DEFAULT_COMPILE_SDK_VERSION}
                    buildToolsVersion "${GradleTestProject.DEFAULT_BUILD_TOOL_VERSION}"
                    defaultConfig {
                         minSdkVersion $SUPPORT_LIB_MIN_SDK
                    }
                    dependencies {
                        api 'androidx.appcompat:appcompat:1.6.1'
                        api 'androidx.legacy:legacy-support-v4:1.0.0'
                        api 'androidx.media:media:1.6.0'
                    }
                }
            """
        .trimIndent(),
    )
  }

  @Test
  fun `test VariantDependencies model`() {
    val result = project.modelV2().fetchModels(variantName = "debug")

    with(result).compareVariantDependencies(projectAction = { getProject(":test") }, goldenFile = "test_VariantDependencies")
  }

  @Test
  fun checkTestApk() {
    project.executor().run("assembleDebug")
    val apk: Apk = project.getSubproject("test").getApk(GradleTestProject.ApkType.DEBUG)

    assertThatApk(apk).named("Test app shouldn't contain app code").doesNotContainClass("Lcom/android/tests/basic/Main;")
    assertThatApk(apk).named("Test app shouldn't contain app layout").doesNotContainResource("layout/main.xml")
    assertThatApk(apk).named("Test app shouldn't contain app dependency code").doesNotContainClass("Landroid/support/v7/app/ActionBar;")
    assertThatApk(apk)
      .named("Test app shouldn't contain app dependency resources")
      .doesNotContainResource("layout/abc_action_bar_title_item.xml")
  }
}
