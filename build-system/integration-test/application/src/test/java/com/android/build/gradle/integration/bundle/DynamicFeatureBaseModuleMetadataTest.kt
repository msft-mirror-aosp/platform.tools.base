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

package com.android.build.gradle.integration.bundle

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for b/524398423: a dynamic feature module that does not have the base application module on its compile classpath used to
 * fail with a bare `NoSuchElementException: Collection is empty` when resolving its applicationId from the base module metadata. It must
 * now fail with an actionable error message.
 */
class DynamicFeatureBaseModuleMetadataTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication(DEFAULT_APP_PATH) {
      android {
        defaultConfig { applicationId = "com.example.test" }
        dynamicFeatures.add(DEFAULT_FEATURE_PATH)
      }
    }
    androidFeature(DEFAULT_FEATURE_PATH) { android { namespace = "com.example.test.feature" } }
    androidLibrary(DEFAULT_LIB_PATH) { android { namespace = "com.example.test.lib" } }
  }

  private val flavors =
    """
    android {
        flavorDimensions 'distribution'
        productFlavors {
            play { dimension 'distribution' }
            github { dimension 'distribution' }
        }
    }
    """
      .trimIndent()

  private fun setUp(featureDependencies: String) {
    rule.build.androidApplication(DEFAULT_APP_PATH).files.update("build.gradle").append("\n$flavors\n")
    rule.build.androidLibrary(DEFAULT_LIB_PATH).files.update("build.gradle").append("\n$flavors\n")
    rule.build
      .androidFeature(DEFAULT_FEATURE_PATH)
      .files
      .update("build.gradle")
      .append("\n$flavors\ndependencies {\n    implementation project('$DEFAULT_LIB_PATH')\n$featureDependencies\n}\n")
  }

  private fun assertActionableFailure() {
    val result = rule.build.executor.expectFailure().run(":feature:extractDeepLinksPlayDebug")
    result.assertErrorContains(
      "Dynamic feature module ':feature' (variant 'playDebug') could not find a base application module on its compile classpath"
    )
    result.assertErrorContains("implementation(project(\":app\"))")
    result.assertErrorDoesNotContain("Collection is empty")
  }

  /** The reporter's setup: the dynamic feature only depends on a library, not on the base application module. */
  @Test
  fun featureWithoutDependencyOnBaseModuleFailsWithActionableError() {
    setUp(featureDependencies = "")
    assertActionableFailure()
  }

  /** runtimeOnly does not put the base module on the compile classpath. */
  @Test
  fun featureWithRuntimeOnlyDependencyOnBaseModuleFailsWithActionableError() {
    setUp(featureDependencies = "    runtimeOnly project('$DEFAULT_APP_PATH')")
    assertActionableFailure()
  }

  /** Positive control: with the dependency on the base module, the applicationId is resolved from the base module metadata. */
  @Test
  fun featureWithDependencyOnBaseModuleSucceeds() {
    setUp(featureDependencies = "    implementation project('$DEFAULT_APP_PATH')")
    val result = rule.build.executor.run(":feature:extractDeepLinksPlayDebug")
    assertThat(result.didWorkTasks).contains(":app:writePlayDebugModuleMetadata")
  }
}
