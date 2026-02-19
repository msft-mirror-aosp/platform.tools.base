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

package com.android.build.gradle.integration.api

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.DeviceTestBuilder
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.builder.core.ComponentTypeImpl
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/** Test enabling and disabling device tests through the variant builder APIs. */
class AndroidTestEnablementInBuilder {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android {
          buildTypes {
            create("stagingWithTest") {}
            create("stagingWithoutTest") {}
          }
        }
        pluginCallbacks += MyAppCallback::class.java
      }
    }

  // enable or disable the device tests depending on the build type.
  class MyAppCallback : ApplicationComponentCallback {

    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.beforeVariants(androidComponents.selector().withBuildType("stagingWithTest")) { variantBuilder ->
        variantBuilder.deviceTests[DeviceTestBuilder.ANDROID_TEST_TYPE]?.enable = true
      }
      androidComponents.beforeVariants(androidComponents.selector().withBuildType("stagingWithoutTest")) { variantBuilder ->
        variantBuilder.deviceTests[DeviceTestBuilder.ANDROID_TEST_TYPE]?.enable = false
      }
    }
  }

  @Test
  fun testDeviceTestStatus() {
    val project = rule.build
    val result = project.modelBuilder.fetchModels()
    Truth.assertThat(result).isNotNull()
    val models = result.container.getProject(":app")
    // check the device tests are either enabled or disabled depending on the build type.
    models.basicAndroidProject?.variants?.forEach { variant ->
      if (variant.buildType == "stagingWithTest") {
        Truth.assertThat(variant.deviceTestArtifacts[ComponentTypeImpl.ANDROID_TEST.artifactName]).isNotNull()
      }
      if (variant.buildType == "stagingWithoutTest") {
        Truth.assertThat(variant.deviceTestArtifacts[ComponentTypeImpl.ANDROID_TEST.artifactName]).isNull()
      }
    }
  }
}
