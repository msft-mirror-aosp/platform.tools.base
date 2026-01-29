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
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class BuiltInKotlinDslTest() {

  @get:Rule val rule = GradleRule.from { androidApplication { HelloWorldAndroid.setupKotlin(files) } }

  @Test
  fun `set enableKotlin=true, expect Kotlin compile task to run`() {
    val build = rule.build { androidApplication { android { enableKotlin = true } } }

    val result = build.executor.run(":app:assembleDebug")

    assertThat(result.tasks).contains(":app:compileDebugKotlin")
  }

  @Test
  fun `set enableKotlin=false, expect Kotlin compile task to not run`() {
    val build = rule.build { androidApplication { android { enableKotlin = false } } }

    val result = build.executor.run(":app:assembleDebug")

    assertThat(result.tasks).doesNotContain(":app:compileDebugKotlin")
  }
}
