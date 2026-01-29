/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.integration.dsl

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/** Regression test for b/215407138. Ensure that finalizeDsl blocks are run before all checks on the extension objects. */
class CompileSdkSetThroughDSLFinalizeBlock {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication(createMinimumProject = false) {
        // only set up namespace and manifest, but not the compileSdk as it'll come via the plugin.
        android { namespace = "com.example.app" }
        files.setupMinimumManifest()
        pluginCallbacks += MyCallback::class.java
      }
    }

  class MyCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.apply {
        finalizeDsl { extension ->
          extension.compileSdk = 31
          extension.defaultConfig {
            minSdk = 19
            targetSdk = 31
          }
        }
      }
    }
  }

  @Test
  fun testCompileSdkVersion() {
    val result = rule.build.executor.run("tasks")
    Truth.assertThat(result.failureMessage).isNull()
  }
}
