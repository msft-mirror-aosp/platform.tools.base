/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.integration.application

import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyLibraryCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import kotlin.io.path.appendText
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/**
 * Integration test for b/482133164.
 *
 * Verifies that accessing legacy variant APIs (e.g., `applicationVariants`, `libraryVariants`, `testVariants`) triggers a runtime
 * deprecation warning.
 *
 * This test explicitly sets `android.newDsl=false` to allow access to the legacy APIs, then asserts that the [DeprecationReporter] outputs
 * the correct warning to stdout when these properties are accessed.
 */
class LegacyVariantApiDeprecationTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication(":app") { android { namespace = "com.example.app" } }
      androidLibrary(":lib") { android { namespace = "com.example.lib" } }
      androidFeature(":feature") { android { namespace = "com.example.feature" } }
      androidTest(":test") {
        android {
          namespace = "com.example.test"
          targetProjectPath = ":app"
        }
      }
    }

  @Test
  fun testAppAndTestedVariantsDeprecation() {
    val build =
      rule.build {
        androidApplication(":app") { pluginCallbacks.add(AppDeprecationCallback::class.java) }
        gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
      }

    val result = build.executor.run("help")

    result.assertOutputContains("applicationVariants")
    result.assertOutputContains("testVariants")
    result.assertOutputContains("unitTestVariants")
    result.assertOutputContains("AndroidComponentsExtension")
  }

  @Test
  fun testLibraryVariantsDeprecation() {
    val build =
      rule.build {
        androidLibrary(":lib") { pluginCallbacks.add(LibDeprecationCallback::class.java) }
        gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
      }

    val result = build.executor.run("help")

    result.assertOutputContains("libraryVariants")
    result.assertOutputContains("testVariants")
    result.assertOutputContains("unitTestVariants")
    result.assertOutputContains("AndroidComponentsExtension")
  }

  @Test
  fun testDynamicFeatureVariantsDeprecation() {
    val build =
      rule.build {
        androidFeature(":feature") { pluginCallbacks.add(AppDeprecationCallback::class.java) }
        gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
      }

    val featureBuildFile = build.directory.resolve("feature/build.gradle")

    featureBuildFile.appendText(
      """
      android {
          applicationVariants
          testVariants
          unitTestVariants
      }
      """
        .trimIndent()
    )

    val result = build.executor.run("help")

    result.assertOutputContains("applicationVariants")
    result.assertOutputContains("testVariants")
    result.assertOutputContains("unitTestVariants")
    result.assertOutputContains("AndroidComponentsExtension")
  }
}

class AppDeprecationCallback : LegacyApplicationCallback {
  override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
    extension.applicationVariants
    extension.testVariants
    extension.unitTestVariants
  }
}

class LibDeprecationCallback : LegacyLibraryCallback {
  override fun handleExtension(project: Project, extension: LibraryExtension) {
    extension.libraryVariants
    extension.testVariants
    extension.unitTestVariants
  }
}
