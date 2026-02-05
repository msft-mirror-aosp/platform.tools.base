/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.internal.utils.COMPOSE_COMPILER_PLUGIN_ID
import com.android.build.gradle.internal.utils.KOTLIN_ANDROID_PLUGIN_ID
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.SyncIssue
import com.android.builder.model.v2.ide.AndroidGradlePluginProjectFlags
import com.android.builder.model.v2.models.ProjectSyncIssues
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class ComposeHelloWorldTest {

  @JvmField
  @Rule
  val project =
    GradleTestProject.builder()
      .fromTestProject("composeHelloWorld")
      // increase max heap size to avoid OOMs (b/350788568)
      .withHeap("2048m")
      .withKotlinGradlePlugin(true)
      .withComposeCompilerGradlePlugin(true)
      .create()

  @Test
  fun appAndTestsBuildSuccessfully() {
    val tasks = listOf("clean", "assembleDebug", "assembleDebugAndroidTest")
    project.executor().run(tasks)
    // run once again to test configuration caching
    project.executor().run(tasks)
  }

  @Test
  fun testScreenshotTestAndTestFixturesCompilation() {
    project.executor().run(":app:compileDebugTestFixturesKotlin")
    val testFixturesClassFile =
      project
        .getSubproject("app")
        .getIntermediateFile(
          "built_in_kotlinc",
          "debugTestFixtures",
          "compileDebugTestFixturesKotlin",
          "classes",
          "com",
          "example",
          "helloworldcompose",
          "FixtureKt.class",
        )
    assertThat(testFixturesClassFile).exists()

    project.executor().run(":app:compileDebugScreenshotTestKotlin")
    val screenshotTestClassFile =
      project
        .getSubproject("app")
        .getIntermediateFile(
          "built_in_kotlinc",
          "debugScreenshotTest",
          "compileDebugScreenshotTestKotlin",
          "classes",
          "com",
          "example",
          "helloworldcompose",
          "ScreenshotTestKt.class",
        )
    assertThat(screenshotTestClassFile).exists()
  }

  @Test
  fun testErrorWhenComposeCompilerPluginNotAppliedWithKotlin2() {
    TestFileUtils.searchAndReplace(project.getSubproject("app").buildFile, "apply plugin: '$COMPOSE_COMPILER_PLUGIN_ID'", "")
    TestFileUtils.appendToFile(
      project.getSubproject("app").buildFile,
      """
      android {
          buildFeatures {
              compose = true
          }
      }
      """
        .trimIndent(),
    )
    val result = project.executor().expectFailure().run("assembleDebug")
    ScannerSubject.assertThat(result.stderr).contains("Starting in Kotlin 2.0, the Compose Compiler Gradle plugin is required")
  }

  @Test
  fun testModel() {
    val appModel = project.modelV2().ignoreSyncIssues().fetchModels().container.getProject(":app")
    assertThat(appModel.androidProject?.flags?.getFlagValue(AndroidGradlePluginProjectFlags.BooleanFlag.JETPACK_COMPOSE.name)).isTrue()
  }

  @Test
  fun testSyncIssue() {
    TestFileUtils.appendToFile(
      project.getSubproject("app").buildFile,
      """
      android {
          buildFeatures {
              compose = false
          }
      }
      """
        .trimIndent(),
    )
    val result = project.modelV2().ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels()
    val syncIssues: ProjectSyncIssues? = result.container.getProject(":app").issues
    val syncIssue = syncIssues?.syncIssues?.filter { it.type == SyncIssue.TYPE_INCONSISTENT_BUILD_FEATURE_SETTING }?.single()
    assertThat(syncIssue).isNotNull()
    assertThat(syncIssue?.data).contains("buildFeatures.compose")
  }

  @Test
  fun testWithJetbrainsKotlin() {
    val buildFile = project.getSubproject(":app").buildFile
    TestFileUtils.searchAndReplace(
      buildFile,
      "apply plugin: 'com.android.application'",
      """
                apply plugin: 'com.android.application'
                apply plugin: '$KOTLIN_ANDROID_PLUGIN_ID'
            """
        .trimIndent(),
    )
    TestFileUtils.appendToFile(buildFile, "kotlin.compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8")

    val tasks = listOf("clean", "assembleDebug", "assembleDebugAndroidTest")
    project.executor().with(BooleanOption.BUILT_IN_KOTLIN, false).with(BooleanOption.USE_NEW_DSL, false).run(tasks)
    // run once again to test configuration caching
    project.executor().with(BooleanOption.BUILT_IN_KOTLIN, false).with(BooleanOption.USE_NEW_DSL, false).run(tasks)
  }
}
