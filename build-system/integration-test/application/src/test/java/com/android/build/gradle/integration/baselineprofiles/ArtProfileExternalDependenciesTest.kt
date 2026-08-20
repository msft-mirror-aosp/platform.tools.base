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

package com.android.build.gradle.integration.baselineprofiles

import com.android.SdkConstants
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.readText
import org.junit.Rule
import org.junit.Test

class ArtProfileExternalDependenciesTest {

  private val activityDependency = "androidx.activity:activity-compose:1.5.1"
  private val fragmentDependency = "androidx.fragment:fragment:1.4.1"
  private val baselineProfileContent =
    """
    HSPLcom/google/Foo;->mainMethod(II)I
    HSPLcom/google/Foo;->mainMethod-name-with-hyphens(II)I
    """
      .trimIndent()

  @get:Rule
  val rule = GradleRule.from {
    androidJavaApplication(":app") {
      android {
        namespace = "com.example.app"
        defaultConfig.minSdk = 28
      }
      dependencies {
        implementation(activityDependency)
        implementation(fragmentDependency)
      }
      files.add("src/main/baselineProfiles/file.txt", baselineProfileContent)
    }
    gradleProperties { add("android.useAndroidX", "true") }
  }

  @Test
  fun testIgnoreFrom() {
    rule.build.executor.run("assembleRelease")

    val app = rule.build.androidApplication(":app")
    val mergedFile =
      app.intermediatesDir.resolve(
        FileUtils.join(
          InternalArtifactType.MERGED_ART_PROFILE.getFolderName(),
          "release",
          "mergeReleaseArtProfile",
          SdkConstants.FN_ART_PROFILE,
        )
      )

    assertThat(mergedFile.readText()).contains(baselineProfileContent)
    assertThat(mergedFile.readText()).contains("HSPLandroidx/compose/")
    assertThat(mergedFile.readText()).contains("HSPLandroidx/fragment/")

    app.reconfigure { android { buildTypes.named("release") { it.baselineProfile { ignoreFrom.add(fragmentDependency) } } } }

    rule.build.executor.run("assembleRelease")

    assertThat(mergedFile.readText()).contains(baselineProfileContent)
    assertThat(mergedFile.readText()).contains("HSPLandroidx/compose/")
    assertThat(mergedFile.readText()).doesNotContain("HSPLandroidx/fragment/")
  }

  @Test
  fun testIgnoreFromAllExternalDependencies() {
    val app = rule.build.androidApplication(":app")
    app.reconfigure { android { buildTypes.named("release") { it.baselineProfile { ignoreFromAllExternalDependencies = true } } } }

    rule.build.executor.run("assembleRelease")

    val mergedFile =
      app.intermediatesDir.resolve(
        FileUtils.join(
          InternalArtifactType.MERGED_ART_PROFILE.getFolderName(),
          "release",
          "mergeReleaseArtProfile",
          SdkConstants.FN_ART_PROFILE,
        )
      )

    assertThat(mergedFile.readText()).contains(baselineProfileContent)
    assertThat(mergedFile.readText()).doesNotContain("HSPLandroidx/compose/")
    assertThat(mergedFile.readText()).doesNotContain("HSPLandroidx/fragment/")
  }

  @Test
  fun testIgnoreFromDependencyNotFound() {
    val app = rule.build.androidApplication(":app")
    app.reconfigure {
      android {
        buildTypes.named("release") {
          it.baselineProfile {
            ignoreFrom.add("Unknown Dependency 1")
            ignoreFrom.add("Unknown Dependency 2")
          }
        }
      }
    }

    val result = rule.build.executor.run("assembleRelease")
    result.assertOutputContains("Baseline profiles from [Unknown Dependency 1, Unknown Dependency 2] are specified to be ignored")
  }
}
