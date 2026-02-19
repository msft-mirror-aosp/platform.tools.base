/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.fixture

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/** A test that validates injecting custom plugins into a test project via the [GradleRule] fixture. */
class LibPluginTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidLibrary(":lib") {
        pluginCallbacks += LibCallback::class.java

        files {
          HelloWorldAndroid.setupJava(this)
          add("src/main/assets/FileToTransform.txt", "initial content")
        }
      }
    }

  @Test
  fun testReleaseVariantIsDisabled() {
    val build = rule.build

    build.executor
      .expectFailure()
      .run(":lib:assembleRelease")
      .assertErrorContains("Cannot locate tasks that match ':lib:assembleRelease' as task 'assembleRelease' not found in project ':lib'.")
  }

  class LibCallback : LibraryComponentCallback {
    override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
      androidComponents.apply { beforeVariants(selector().withBuildType("release")) { variant -> variant.enable = false } }
    }
  }
}
