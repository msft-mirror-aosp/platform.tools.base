/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.integration.kotlin

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import org.junit.Rule
import org.junit.Test

class BuiltInKotlinAndKmpTest() {

  @get:Rule val rule = GradleRule.from {}

  @Test
  fun `fail when built-in Kotlin plugin is applied before kotlin-multiplatform plugin`() {
    val build =
      rule.build {
        androidLibrary {
          applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
          applyPlugin(PluginType.KOTLIN_MPP)
        }
      }

    val result = build.executor.expectFailure().run(":app:assembleDebug")

    // See https://youtrack.jetbrains.com/issue/KT-81117
    result.assertErrorContains("Cannot add extension with name 'kotlin', as there is an extension already registered with that name.")
  }

  @Test
  fun `fail when built-in Kotlin plugin is applied after kotlin-multiplatform plugin`() {
    val build =
      rule.build {
        androidLibrary {
          applyPlugin(PluginType.KOTLIN_MPP, applyFirst = true)
          applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        }
      }

    val result = build.executor.expectFailure().run(":app:assembleDebug")

    result.assertErrorContains(
      "The 'com.android.library' (or 'com.android.application') plugin is not compatible with the 'org.jetbrains.kotlin.multiplatform' plugin since AGP 9.0."
    )
  }
}
