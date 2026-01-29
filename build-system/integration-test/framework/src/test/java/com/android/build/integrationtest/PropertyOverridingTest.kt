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

package com.android.build.integrationtest

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.options.BooleanOption.ENABLE_APP_COMPILE_TIME_R_CLASS
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

const val PROPERTY_OVERRIDE_NAME = "kotlin.stdlib.default.dependency"

// Check that property defined in command line as -P<prop> overwrites one in gradle.properties
// file that is defined with `gradleProperties.add`
class PropertyOverridingTest {

  @get:Rule val rule = GradleRule.from {}

  @Test
  fun testKotlinPropertyOverriding() {
    val build =
      rule.build {
        androidApplication { pluginCallbacks += KotlinPropertyCheckCallback::class.java }
        gradleProperties { add(PROPERTY_OVERRIDE_NAME, "false") }
      }
    build.executor
      .withArgument("-P$PROPERTY_OVERRIDE_NAME=true")
      .run("tasks")
      .assertOutputContains("property: $PROPERTY_OVERRIDE_NAME=true")
  }

  @Test
  fun testAndroidPropertyOverriding() {
    val build =
      rule.build {
        androidApplication { pluginCallbacks += AndroidPropertyCheckCallback::class.java }
        gradleProperties { add(ENABLE_APP_COMPILE_TIME_R_CLASS, false) }
      }
    build.executor
      .with(ENABLE_APP_COMPILE_TIME_R_CLASS, true)
      .run("tasks")
      .assertOutputContains("property: ${ENABLE_APP_COMPILE_TIME_R_CLASS.propertyName}=true")
  }
}

class KotlinPropertyCheckCallback : GenericCallback {

  override fun handleProject(project: Project) {
    project.providers.gradleProperty(PROPERTY_OVERRIDE_NAME).let { println("property: $PROPERTY_OVERRIDE_NAME=${it.get()}") }
  }
}

class AndroidPropertyCheckCallback : GenericCallback {

  override fun handleProject(project: Project) {
    project.providers.gradleProperty(ENABLE_APP_COMPILE_TIME_R_CLASS.propertyName).let {
      println("property: ${ENABLE_APP_COMPILE_TIME_R_CLASS.propertyName}=${it.get()}")
    }
  }
}
