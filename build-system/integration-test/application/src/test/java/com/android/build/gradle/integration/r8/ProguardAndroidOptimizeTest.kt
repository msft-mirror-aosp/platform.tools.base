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

package com.android.build.gradle.integration.r8

import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.internal.impldep.com.amazonaws.util.Throwables
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProguardAndroidOptimizeTest(val proguardAndroidTxtDisallowed: Boolean) {
  companion object {
    @JvmStatic @Parameterized.Parameters(name = "proguardAndroidTxtDisallowed={0}") fun proguardAndroidTxtDisallowed() = listOf(true, false)
  }

  @get:Rule
  val rule =
    GradleRule.from {
      gradleProperties { add(BooleanOption.R8_PROGUARD_ANDROID_TXT_DISALLOWED, proguardAndroidTxtDisallowed) }
      androidApplication {
        applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        android {
          defaultConfig.minSdk = 24
          buildTypes { named("release") { it.isMinifyEnabled = true } }
        }
        files {
          add(
            "src/main/java/com/example/app/ClassToOptimize.kt",
            // language=kotlin
            """
            class ClassToOptimize
            """
              .trimIndent(),
          )
        }
      }
    }

  @Test
  fun `test proguard-android-txt disallowed with flag`() {
    val build =
      rule.build {
        androidApplication {
          android {
            buildTypes {
              named("release") {
                it.optimization.keepRules {
                  // proguard-android.txt isn't supported when the flag is set
                  files.add(getDefaultProguardFile("proguard-android.txt"))
                }
              }
            }
          }
        }
      }

    if (proguardAndroidTxtDisallowed) {
      val result = build.executor.expectFailure().run(":app:assembleRelease")
      assertThat(Throwables.getRootCause(result.exception).message)
        .contains("`getDefaultProguardFile('proguard-android.txt')` is no longer supported")
    } else {
      build.executor.run(":app:assembleRelease") // builds fine
    }
  }

  @Test
  fun `test proguard-android-optimize-txt always allowed`() {
    val build =
      rule.build {
        androidApplication {
          android {
            buildTypes {
              named("release") {
                it.optimization.keepRules {
                  // proguard-android-optimize.txt is fine
                  files.add(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
              }
            }
          }
        }
      }
    build.executor.run(":app:assembleRelease")
    assertThat(build.getReleaseConfigurationTxtFile()).doesNotContain("-dontoptimize")
  }

  @Test
  fun `test default -dontoptimize behavior`() {
    val build =
      rule.build {
        // with `optimization.keepRules.files` being empty, you get the default rules
        // which should not contain -dontoptimize
      }
    build.executor.run(":app:assembleRelease")
    if (proguardAndroidTxtDisallowed) {
      assertThat(build.getReleaseConfigurationTxtFile()).doesNotContain("-dontoptimize")
    } else {
      assertThat(build.getReleaseConfigurationTxtFile()).contains("-dontoptimize")
    }
  }

  private fun GradleBuild.getReleaseConfigurationTxtFile(): File {

    return FileUtils.join(androidApplication().outputsDir.toFile(), "mapping", "release", "configuration.txt").also {
      assertThat(it).exists()
    }
  }
}
