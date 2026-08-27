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

package com.android.build.gradle.integration.dependencies

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.utils.TestFileUtils
import org.junit.Rule
import org.junit.Test

/** Reproduction test for b/486826364. */
class GStringMatchingFallbackTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication(":app") {}
    androidLibrary(":library") {}
  }

  @Test
  fun testGStringInMatchingFallbacks() {
    val build = rule.build
    val appBuildFile = build.directory.resolve("app/build.gradle")

    TestFileUtils.appendToFile(
      appBuildFile.toFile(),
      """
      android {
          buildTypes {
              debug {
                  def fallbackValue = 'debug'
                  matchingFallbacks = ["${'$'}{fallbackValue}"]
              }
          }
      }

      dependencies {
          implementation project(":library")
      }
      """
        .trimIndent(),
    )

    // Fetch models and verify that matchingFallbacks contains ONLY real java.lang.String objects.
    val models = build.modelBuilder.fetchModels().container
    val appDsl = models.getProject(":app").androidDsl!!
    val debugBuildType = appDsl.buildTypes.find { it.name == "debug" }!!

    debugBuildType.matchingFallbacks.map { it.toString() }
  }
}
