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

package com.android.build.gradle.integration.r8

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType.KTS
import org.junit.Rule
import org.junit.Test

class DefaultProguardFilesTest {
  @get:Rule
  val rule =
    GradleRule.configure().from {
      androidApplication(":baseModule") {
        android {
          namespace = "com.example.baseModule"
          buildTypes {
            named("release") {
              it.isMinifyEnabled = true
              it.proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
          }
          dynamicFeatures += setOf(":feature")
        }
        files { add("src/main/res/raw/base_file.txt", "base file") }
      }
      androidFeature {
        android {
          namespace = "com.example.feature"
          buildTypes {
            named("release") {
              it.isMinifyEnabled = false
              it.proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
          }
        }
        dependencies { implementation(project(":baseModule")) }
        files {
          add("src/main/res/raw/main_feature_file.txt", "feature file")
          add("src/androidTest/res/raw/android_test_feature_file.txt", "hello")
        }
      }
      buildFileType = KTS
    }

  /** Regression test for b/295666695. */
  @Test
  fun testDefaultProguardFilesHaveTaskDependencies() {
    val result = rule.build.executor.expectFailure().run("assembleRelease")

    // If default Proguard files didn't have task dependencies, the build would fail with an
    // error different from the error below (see b/295666695), so by checking the error below,
    // we're ensuring that default Proguard files have task dependencies.
    result.assertErrorContains(
      "Default file proguard-android-optimize.txt should not be specified in this module. It can be specified in the base module instead."
    )
  }
}
