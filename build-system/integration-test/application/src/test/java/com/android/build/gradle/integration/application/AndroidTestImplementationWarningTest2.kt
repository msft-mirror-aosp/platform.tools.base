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

package com.android.build.gradle.integration.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.DeviceTestBuilder
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class AndroidTestImplementationWarningTest2 {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        pluginCallbacks += DisableAndroidTestForOneFlavorCallback::class.java
        android {
          flavorDimensions += "version"
          productFlavors {
            create("demo") { it.dimension = "version" }
            create("full") { it.dimension = "version" }
          }
        }
        dependencies { androidTestImplementation("com.google.guava:guava:19.0") }
      }
    }

  class DisableAndroidTestForOneFlavorCallback : ApplicationComponentCallback {

    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.beforeVariants { variantBuilder ->
        // Disable for 'demo' flavor
        if (variantBuilder.productFlavors.any { it.second == "demo" }) {
          variantBuilder.deviceTests[DeviceTestBuilder.ANDROID_TEST_TYPE]?.enable = false
        }
      }
    }
  }

  @Test
  fun checkNoWarningWhenOneVariantHasAndroidTestsEnabled() {
    val result = rule.build.modelBuilder.ignoreSyncIssues().fetchModels()
    val models = result.container.getProject(":app")
    val issues = models.issues?.syncIssues ?: throw RuntimeException("Missing issues model")

    // We expect NO warning about androidTestImplementation being ignored,
    // because 'full' flavor still has androidTest enabled.

    val warning = issues.find { it.message.contains("androidTestImplementation") }
    assertThat(warning).isNull()
  }
}
