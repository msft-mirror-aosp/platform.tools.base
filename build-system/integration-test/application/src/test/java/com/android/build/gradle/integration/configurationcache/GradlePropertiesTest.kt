/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.build.gradle.integration.configurationcache

import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.options.BooleanOption
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GradlePropertiesTest {

  @get:Rule val project = GradleTestProject.builder().fromTestApp(HelloWorldApp.forPlugin("com.android.application")).create()

  @Before
  fun setUp() {
    project.projectDir.resolve(".gradle/configuration-cache").deleteRecursively()
  }

  @Test
  fun testAccessingChangedGradlePropertiesAtConfiguration() {
    // AndroidX must be enabled when Jetifier is enabled
    executor().with(BooleanOption.ENABLE_JETIFIER, true).run("assembleDebug")
    var result = executor().with(BooleanOption.ENABLE_JETIFIER, true).run("assembleDebug")
    result.assertConfigurationCacheHit()
    result = executor().with(BooleanOption.ENABLE_JETIFIER, false).run("assembleDebug")
    result.assertOutputContains(
      "Calculating task graph as configuration cache cannot be reused because " + "Gradle property 'android.enableJetifier' has changed"
    )
  }

  @Test
  fun testCapturingStandardInstrumentationTestRunnerArgs() {
    executor().run("assembleDebug")
    val result = executor().withArgument("-Pandroid.testInstrumentationRunnerArguments.size=medium").run("assembleDebug")
    result.assertOutputContains(
      "Calculating task graph as configuration cache cannot be reused " +
        "because the set of Gradle properties has changed: 'android.testInstrumentationRunnerArguments.size' was added"
    )
  }

  @Test
  fun testCapturingCustomInstrumentationTestRunnerArgs() {
    executor().withArgument("-Pandroid.testInstrumentationRunnerArguments.foo=origin").run("assembleDebug")
    val result = executor().withArgument("-Pandroid.testInstrumentationRunnerArguments.foo=changed").run("assembleDebug")
    result.assertOutputContains(
      "Calculating task graph as configuration cache cannot be reused " +
        "because the set of Gradle properties has changed: the value of " +
        "'android.testInstrumentationRunnerArguments.foo' was changed."
    )
  }

  private fun executor(): GradleTaskExecutor = project.executor()
}
