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

package com.android.build.gradle.integration.r8

import com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.getOutputDir
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import java.io.File
import org.junit.Rule
import org.junit.Test

class IncludeDefaultRulesTest {
  @get:Rule
  val rule =
    GradleRule.from {
      gradleProperties { add(BooleanOption.R8_GRADUAL_API, true) }
      androidApplication {
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

  val pathPrefix =
    "The proguard configuration file for the following section is Android Gradle plugin ${com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION} (extracted file: "

  @Test
  fun `test includeDefault Rules positive case`() {
    val build =
      rule.build {
        androidApplication {
          android {
            buildTypes {
              named("release") {
                it.optimization {
                  enable = true
                  keepRules { includeDefault = true }
                }
              }
            }
          }
        }
      }
    build.executor.run(":app:assembleRelease")
    val globalFile = getGlobalProguardPath(build)
    val configTxt = build.getReleaseConfigurationTxtFile()
    assertThat(configTxt).contains(pathPrefix + globalFile.absolutePath)
  }

  @Test
  fun `test includeDefault Rules default with optimization enabled`() {
    val build = rule.build { androidApplication { android { buildTypes { named("release") { it.optimization.enable = true } } } } }
    build.executor.run(":app:assembleRelease")
    val globalFile = getGlobalProguardPath(build)
    val configTxt = build.getReleaseConfigurationTxtFile()
    assertThat(configTxt).contains(pathPrefix + globalFile.absolutePath)
  }

  @Test
  fun `test includeDefaultRules defaults with no optimization enable`() {
    val build = rule.build {}
    build.executor.run(":app:assembleRelease")
    val configTxt = build.getReleaseConfigurationTxtFile()
    val globalFile = getGlobalProguardPath(build)
    assertThat(configTxt).contains(pathPrefix + globalFile.absolutePath)
  }

  @Test
  fun `test includeDefaultRules false`() {
    val build =
      rule.build {
        androidApplication {
          android {
            buildTypes {
              named("release") {
                it.optimization.enable = true
                it.optimization.keepRules { includeDefault = false }
              }
            }
          }
        }
      }
    build.executor.run(":app:assembleRelease")
    val configTxt = build.getReleaseConfigurationTxtFile()
    val globalFile = getGlobalProguardPath(build)
    assertThat(configTxt).doesNotContain(pathPrefix + globalFile.absolutePath)
  }

  @Test
  fun `test includeDefaultRules true but no optimization enable`() {
    val build =
      rule.build {
        androidApplication { android { buildTypes { named("release") { it.optimization.keepRules { includeDefault = true } } } } }
      }
    build.executor.run(":app:assembleRelease")
    val configTxt = build.getReleaseConfigurationTxtFile()
    val globalFile = getGlobalProguardPath(build)
    assertThat(configTxt).contains(pathPrefix + globalFile.absolutePath)
  }

  private fun getGlobalProguardPath(build: GradleBuild) =
    InternalArtifactType.DEFAULT_PROGUARD_FILES.getOutputDir(build.androidApplication().buildDir.toFile())
      .resolve("global/proguard-android-optimize.txt-${ANDROID_GRADLE_PLUGIN_VERSION}")

  private fun GradleBuild.getReleaseConfigurationTxtFile(): File {
    return FileUtils.join(androidApplication().outputsDir.toFile(), "mapping", "release", "configuration.txt").also {
      assertThat(it).exists()
    }
  }
}
