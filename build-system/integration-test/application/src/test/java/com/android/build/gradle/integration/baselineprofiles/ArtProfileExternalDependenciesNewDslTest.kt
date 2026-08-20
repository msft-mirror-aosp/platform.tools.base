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
import com.google.common.truth.Truth
import kotlin.io.path.readText
import org.junit.Rule
import org.junit.Test

/** test is for application build type baseline profile API */
class ArtProfileExternalDependenciesNewDslTest {
  private val activityDependency = "androidx.activity:activity-compose:1.5.1"
  private val fragmentDependency = "androidx.fragment:fragment:1.4.1"
  private val lifecycleDependency = "androidx.lifecycle:lifecycle-livedata-core:2.5.1"
  private val baselineProfileContent =
    """
    HSPLcom/google/Foo;->mainMethod(II)I
    HSPLcom/google/Foo;->mainMethod-name-with-hyphens(II)I
    """
      .trimIndent()

  @get:Rule
  val rule = GradleRule.from {
    androidApplication(":app") {
      android {
        namespace = "com.example.app"
        defaultConfig { minSdk = 28 }
      }
      dependencies {
        implementation(activityDependency)
        implementation(fragmentDependency)
        implementation(lifecycleDependency)
      }
      files.add("src/main/baselineProfiles/file.txt", baselineProfileContent)
    }
    gradleProperties { add("android.useAndroidX", "true") }
  }

  @Test
  fun testIgnoreFrom() {
    rule.build.executor.run(":app:assembleRelease")

    val appProject = rule.build.androidApplication(":app")
    val mergedFile =
      appProject.buildDir
        .resolve(SdkConstants.FD_INTERMEDIATES)
        .resolve(InternalArtifactType.MERGED_ART_PROFILE.getFolderName())
        .resolve("release")
        .resolve("mergeReleaseArtProfile")
        .resolve(SdkConstants.FN_ART_PROFILE)

    Truth.assertThat(mergedFile.readText()).contains(baselineProfileContent)
    Truth.assertThat(mergedFile.readText()).contains("HSPLandroidx/compose/")
    Truth.assertThat(mergedFile.readText()).contains("HSPLandroidx/fragment/")

    appProject.reconfigure { android { buildTypes { named("release") { it.baselineProfile { ignoreFrom += fragmentDependency } } } } }

    rule.build.executor.run(":app:assembleRelease")

    Truth.assertThat(mergedFile.readText()).contains(baselineProfileContent)
    Truth.assertThat(mergedFile.readText()).contains("HSPLandroidx/compose/")
    Truth.assertThat(mergedFile.readText()).doesNotContain("HSPLandroidx/fragment/")
  }

  @Test
  fun testIgnoreFromBothDsls() {
    rule.build.executor.run(":app:assembleRelease")

    val appProject = rule.build.androidApplication(":app")
    val mergedFile =
      appProject.buildDir
        .resolve(SdkConstants.FD_INTERMEDIATES)
        .resolve(InternalArtifactType.MERGED_ART_PROFILE.getFolderName())
        .resolve("release")
        .resolve("mergeReleaseArtProfile")
        .resolve(SdkConstants.FN_ART_PROFILE)

    Truth.assertThat(mergedFile.readText()).contains(baselineProfileContent)
    Truth.assertThat(mergedFile.readText()).contains("HSPLandroidx/lifecycle/LiveData")
    Truth.assertThat(mergedFile.readText()).contains("HSPLandroidx/fragment/")

    appProject.reconfigure {
      android {
        buildTypes {
          named("release") {
            it.optimization { it.baselineProfile { ignoreFrom += lifecycleDependency } }
            it.baselineProfile { ignoreFrom += fragmentDependency }
          }
        }
      }
    }

    rule.build.executor.run(":app:assembleRelease")

    Truth.assertThat(mergedFile.readText()).contains(baselineProfileContent)
    Truth.assertThat(mergedFile.readText()).doesNotContain("HSPLandroidx/lifecycle/LiveData")
    Truth.assertThat(mergedFile.readText()).doesNotContain("HSPLandroidx/fragment/")
  }

  @Test
  fun testIgnoreFromAllExternalDependencies() {
    val appProject = rule.build.androidApplication(":app")
    appProject.reconfigure {
      android { buildTypes { named("release") { it.baselineProfile { ignoreFromAllExternalDependencies = true } } } }
    }

    rule.build.executor.run(":app:assembleRelease")

    val mergedFile =
      appProject.buildDir
        .resolve(SdkConstants.FD_INTERMEDIATES)
        .resolve(InternalArtifactType.MERGED_ART_PROFILE.getFolderName())
        .resolve("release")
        .resolve("mergeReleaseArtProfile")
        .resolve(SdkConstants.FN_ART_PROFILE)

    Truth.assertThat(mergedFile.readText()).contains(baselineProfileContent)
    Truth.assertThat(mergedFile.readText()).doesNotContain("HSPLandroidx/compose/")
    Truth.assertThat(mergedFile.readText()).doesNotContain("HSPLandroidx/fragment/")
  }

  @Test
  fun testIgnoreFromDependencyNotFound() {
    val appProject = rule.build.androidApplication(":app")
    appProject.reconfigure {
      android {
        buildTypes {
          named("release") {
            it.baselineProfile {
              ignoreFrom += "Unknown Dependency 1"
              ignoreFrom += "Unknown Dependency 2"
            }
          }
        }
      }
    }

    val result = rule.build.executor.run(":app:assembleRelease")
    result.assertOutputContains("Baseline profiles from [Unknown Dependency 1, Unknown Dependency 2] are specified to be ignored")
  }
}
