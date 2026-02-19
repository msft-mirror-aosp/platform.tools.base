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

package com.android.build.gradle.integration.manifest

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class ProcessManifestPlaceholdersTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication { android { pluginCallbacks += ManifestPlaceHolderProviderCallback::class.java } }
        .dependencies { api(project(":lib")) }
      androidLibrary { android { namespace = "com.example.text" } }
        .files {
          add(
            "src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <meta-data android:name="example_meta" android:value="${'$'}{exampleDataPlaceholder}" />
                <application />
            </manifest>
            """
              .trimIndent(),
          )
        }
    }

  @Test
  fun testBuild() {
    rule.build.executor.run("assembleDebug")
  }
}

class ManifestPlaceHolderProviderCallback : ApplicationComponentCallback {

  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.onVariants { it.manifestPlaceholders.put("exampleDataPlaceholder", "exampleDataValue") }
  }
}
