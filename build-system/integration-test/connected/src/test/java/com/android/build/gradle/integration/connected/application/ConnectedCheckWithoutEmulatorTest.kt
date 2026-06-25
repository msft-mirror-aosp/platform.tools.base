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

package com.android.build.gradle.integration.connected.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ConnectedCheckWithoutEmulatorTest(private val enableBuiltInPlatform: Boolean) {

  companion object {
    @JvmStatic @Parameterized.Parameters(name = "builtInPlatform_{0}") fun parameters(): Collection<Boolean> = listOf(true, false)
  }

  @get:Rule val rule = GradleRule.fromProject(BasicSpec())

  @Test
  fun connectedCheckWithoutEmulator() {
    val build = rule.build
    val result =
      build.executor.with(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, enableBuiltInPlatform).expectFailure().run("connectedCheck")

    result.assertFailureMessage().contains("No connected devices")
  }
}
