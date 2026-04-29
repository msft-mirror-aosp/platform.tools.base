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
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class VariantApiPropertiesTest {
  @get:Rule
  val rule =
    GradleRule.from {
      rootProject { buildscript { classpath("com.google.truth:truth:0.44") } }
      androidApplication {
        android {
          defaultConfig { versionName = "1.2.3-alpha04" }
          buildTypes {
            named("debug") {
              it.javaCompileOptions.annotationProcessorOptions {
                className("Foo")
                argument("value", "debugArg")
              }
              it.versionNameSuffix = "-xD"
            }
          }
          flavorDimensions += "dimension"
          productFlavors {
            create("flavor1") {
              it.javaCompileOptions.annotationProcessorOptions {
                className("Bar")
                argument("value", "flavor1Arg")
              }
            }
          }
        }
      }
    }

  @Test
  fun testMergedJavaCompileOptions() {
    val project = rule.build { androidApplication { pluginCallbacks += MergedJavaCompileOptionsChecker::class.java } }
    project.executor.run("help")
  }

  @Test
  fun testOutputFileName() {
    val project = rule.build { androidApplication { pluginCallbacks += OutputFileNameChecker::class.java } }
    project.executor.run("help")
  }
}

class MergedJavaCompileOptionsChecker : ApplicationComponentCallback {
  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug").withFlavor("dimension" to "flavor1")) {
      val options = it.javaCompilation?.annotationProcessor
      assertThat(options?.classNames?.get()).isEqualTo(listOf("Bar", "Foo"))
      assertThat(options?.arguments?.getting("value")?.get()).isEqualTo("debugArg")
    }
  }
}

class OutputFileNameChecker : ApplicationComponentCallback {
  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().withFlavor("dimension" to "flavor1")) {
      val firstOutput = it.outputs.firstOrNull()
      val firstOutputName = firstOutput?.outputFileName
      when (it.buildType) {
        "debug" -> assertThat(firstOutputName?.get()).isEqualTo("app-flavor1-debug.apk")
        "release" -> assertThat(firstOutputName?.get()).isEqualTo("app-flavor1-release-unsigned.apk")
        else -> throw IllegalStateException("Unexpected buildType ${it.buildType} (first output name: $firstOutputName)")
      }
      if (it.buildType == "debug") {
        firstOutputName?.set(firstOutputName.get().replace("flavor1", "flavor1-${firstOutput.versionName.get()}"))
        assertThat(firstOutputName?.get()).isEqualTo("app-flavor1-1.2.3-alpha04-xD-debug.apk")
      }
    }
  }
}
