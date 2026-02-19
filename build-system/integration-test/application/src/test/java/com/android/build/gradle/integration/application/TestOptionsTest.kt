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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import org.junit.Rule
import org.junit.Test

class TestOptionsTest {
  @get:Rule val rule = GradleRule.fromProject(BasicSpec())

  @Test
  fun testApplicationBuildFailedWhenSetTestOptionsTargetSdk() {
    val build =
      rule.build {
        androidApplication(":app") {
          android {
            testOptions {
              targetSdk = 22
              unitTests { isIncludeAndroidResources = true }
            }
          }
        }
      }

    val result = build.executor.expectFailure().run("assemble")
    result.assertErrorContains("targetSdk is set as 22 in testOptions for non library module")
  }

  @Test
  fun testApplicationBuildFailedWhenSetTestOptionsTargetSdkSpecRelease() {
    val build =
      rule.build {
        androidApplication(":app") {
          android {
            testOptions {
              targetSdk { version = release(22) }
              unitTests { isIncludeAndroidResources = true }
            }
          }
        }
      }
    val result = build.executor.expectFailure().run("assemble")
    result.assertErrorContains("targetSdk is set as version = release(22) in testOptions for non library module")
  }

  @Test
  fun testApplicationBuildFailedWhenSetTestOptionsTargetSdkSpecPreview() {
    val build =
      rule.build {
        androidApplication(":app") {
          android {
            testOptions {
              targetSdk { version = preview("T") }
              unitTests { isIncludeAndroidResources = true }
            }
          }
        }
      }

    val result = build.executor.expectFailure().run("assemble")
    result.assertErrorContains("targetSdk is set as version = preview(\"T\") in testOptions for non library module")
  }
}
