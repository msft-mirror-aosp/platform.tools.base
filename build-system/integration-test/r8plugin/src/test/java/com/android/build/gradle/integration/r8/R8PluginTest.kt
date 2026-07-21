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

package com.android.build.gradle.integration.r8

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class R8PluginTest(val pluginApplied: Boolean) {

  companion object {
    @JvmStatic @Parameterized.Parameters(name = "pluginApplied_{0}") fun parameters() = listOf(true, false)
  }

  @get:Rule
  val project =
    GradleRule.from {
      if (pluginApplied) {
        gradleProperties { add(BooleanOption.R8_PLUGIN_SUPPORT, true) }
      }
      androidApplication {
        if (pluginApplied) {
          applyPlugin(PluginType.R8)
        }
        pluginCallbacks += MyAppCallback::class.java
      }
    }

  class MyAppCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      project.plugins.findPlugin("com.android.r8")?.also { println("R8 plugin applied") }
    }
  }

  @Test
  fun testPluginsApplied() {
    val executor = project.build.executor
    val result = executor.run(":app:assembleDebug")
    if (pluginApplied) {
      result.assertOutputContains("R8 plugin applied")
    } else {
      result.assertOutputDoesNotContain("R8 plugin applied")
    }
  }
}
