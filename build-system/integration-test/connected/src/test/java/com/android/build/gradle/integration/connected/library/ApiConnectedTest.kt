/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.build.gradle.integration.connected.library

import com.android.build.gradle.integration.common.fixture.SUPPORT_LIB_MIN_SDK
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.connected.utils.getEmulator
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class ApiConnectedTest {

  companion object {
    @JvmField @ClassRule val emulator: ExternalResource = getEmulator()
  }

  @get:Rule
  val project =
    GradleRule.fromProject("api") {
      androidApplication(":app") {
        android {
          namespace = "com.android.tests.basic"
          compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          defaultConfig {
            minSdk = SUPPORT_LIB_MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          installation {
            // fail fast (30s) if no response
            timeOutInMs = 30000
          }
        }
        dependencies {
          androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
          androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
        }
      }
      androidLibrary(":lib") {
        android {
          namespace = "com.android.tests.libstest.lib2"
          compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          defaultConfig {
            minSdk = SUPPORT_LIB_MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
        }
        dependencies {
          androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
          androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
        }
      }
    }

  @Before
  fun setUp() {
    // run the uninstall tasks in order to (1) make sure nothing is installed at the beginning
    // of each test and (2) check the adb connection before taking the time to build anything.
    project.build.executor.run("uninstallAll")
  }

  @Test
  fun connectedCheck() {
    project.build.executor.run("connectedAndroidTest")
  }
}
