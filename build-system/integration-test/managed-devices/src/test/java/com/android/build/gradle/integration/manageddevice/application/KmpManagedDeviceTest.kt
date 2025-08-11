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

package com.android.build.gradle.integration.manageddevice.application

import com.android.build.api.dsl.androidLibrary
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.KotlinMultiplatformCallback
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomAndroidSdk
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomSdkDir
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.pathString

class KmpManagedDeviceTest {

  @get:Rule
  val customAndroidSdkRule = CustomAndroidSdkRule()

  @get:Rule
  val rule = GradleRule.configure()
    .withCustomSdkDir(customAndroidSdkRule)
    .from {
      androidKotlinMultiplatformLibrary(":kmpLibrary") {
        files {
          add(
            "src/androidDeviceTest/kotlin/pkg/name/kmpLibrary/InstrumentedTest.kt",
            //language=kotlin
            """
            package pkg.name.kmpLibrary

            import androidx.test.ext.junit.runners.AndroidJUnit4

            import org.junit.Test
            import org.junit.runner.RunWith

            @RunWith(AndroidJUnit4::class)
            class ExampleInstrumentedTest {
              @Test
              fun exampleTest() {}
            }
            """.trimIndent()
          )
        }
        pluginCallbacks += KmpCallback::class.java
      }
      gradleProperties {
        add(BooleanOption.USE_ANDROID_X, true)
      }
    }

  private val executor: GradleTaskExecutor
    get() = rule.build.executor
      .withCustomAndroidSdk(customAndroidSdkRule)
      .withEnableInfoLogging(false)

  class KmpCallback : KotlinMultiplatformCallback {
    override fun handleExtension(
      project: Project,
      extension: KotlinMultiplatformExtension
    ) {
      extension.apply {
        androidLibrary {
          minSdk = 21
          withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            managedDevices.localDevices.create("device1") {
              it.device = "Pixel 2"
              it.sdkVersion = System.getProperty("sdk.repo.sysimage.apiLevel").toInt()
              it.systemImageSource = System.getProperty("sdk.repo.sysimage.source")
              it.require64Bit = true
            }
          }
        }
        sourceSets.getByName("androidDeviceTest") {
          it.dependencies {
            implementation("androidx.test:core:1.4.0-alpha06")
            implementation("androidx.test.ext:junit:1.1.3-alpha02")
            implementation("androidx.test:monitor:1.4.0-alpha06")
            implementation("androidx.test:rules:1.4.0-alpha06")
            implementation("androidx.test:runner:1.4.0-alpha06")
          }
        }
      }
    }
  }

  @Test
  fun runManagedDeviceTest() {
    executor.run(":kmpLibrary:device1AndroidDeviceTest")

    val reportDir = FileUtils.join(
      rule.build.subProject(":kmpLibrary").buildDir.pathString,
      "reports",
      "androidTests",
      "managedDevice",
      "androidmain",
      "allDevices",
    )
    assertThat(File(reportDir, "index.html")).exists()
    assertThat(File(reportDir, "pkg.name.kmpLibrary.html")).exists()
    assertThat(File(reportDir, "pkg.name.kmpLibrary.ExampleInstrumentedTest.html"))
      .exists()
  }
}
