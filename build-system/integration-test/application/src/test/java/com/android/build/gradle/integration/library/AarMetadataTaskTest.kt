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
package com.android.build.gradle.integration.library

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.DESUGAR_DEPENDENCY_VERSION
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicBuilds.Companion.HELLO_WORLD_LIBRARY
import com.android.build.gradle.integration.common.output.AarMetadataSubject
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.tasks.AarMetadataTask
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/** Tests for [AarMetadataTask]. */
class AarMetadataTaskTest {
  @get:Rule val rule = GradleRule.from(configAction = HELLO_WORLD_LIBRARY)

  @Test
  fun testBasic() {
    rule.build.executor.run(":lib:assembleDebug")
    rule.build.androidLibrary().assertAar(AarSelector.DEBUG) {
      aarMetadata {
        formatVersion().isEqualTo("1.0")
        metadataVersion().isEqualTo("1.0")
        minCompileSdk().isEqualTo(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION.toString())
        minAgpVersion().isEqualTo("1.0.0")
        minCompileSdkExtension().isEqualTo("0")
        coreLibraryDesugaringEnabled().isEqualTo("false")
        desugarJdkLibId().isNull()
      }
    }
  }

  @Test
  fun testBasicWithMinAgpAutoEncoding() {
    rule.build.executor.with(BooleanOption.AUTO_ENCODE_MINIMUM_AGP_VERSION_IN_AAR_METADATA, true).run(":lib:assembleDebug")
    rule.build.androidLibrary().assertAar(AarSelector.DEBUG) {
      aarMetadata {
        formatVersion().isEqualTo("1.0")
        metadataVersion().isEqualTo("1.0")
        minCompileSdk().isEqualTo(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION.toString())
        minAgpVersion().isEqualTo("8.9.1")
        minCompileSdkExtension().isEqualTo("0")
        coreLibraryDesugaringEnabled().isEqualTo("false")
        desugarJdkLibId().isNull()
      }
    }
  }

  @Test
  fun testDsl() {
    val build =
      rule.build {
        androidLibrary {
          android {
            defaultConfig {
              multiDexEnabled = true
              aarMetadata {
                minCompileSdk = 27
                minAgpVersion = "3.0.0"
                minCompileSdkExtension = 2
              }
            }
            compileOptions { isCoreLibraryDesugaringEnabled = true }

            dependencies { coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:$DESUGAR_DEPENDENCY_VERSION") }
          }
        }
      }

    build.executor.run(":lib:assembleDebug")
    build.androidLibrary().assertAar(AarSelector.DEBUG) {
      aarMetadata {
        minCompileSdk().isEqualTo("27")
        minAgpVersion().isEqualTo("3.0.0")
        minCompileSdkExtension().isEqualTo("2")
        coreLibraryDesugaringEnabled().isEqualTo("true")
        desugarJdkLibId().isEqualTo("com.android.tools:desugar_jdk_libs:$DESUGAR_DEPENDENCY_VERSION")
      }
    }
  }

  @Test
  fun testDsl_minCompileSdk_fallback() {
    // We do not explicitly set minCompileSdkVersion, so it should fall back to compileSdk.
    rule.build.executor.run(":lib:assembleDebug")
    rule.build.androidLibrary().assertAar(AarSelector.DEBUG) {
      aarMetadata { minCompileSdk().isEqualTo(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION.toString()) }
    }
  }

  @Test
  fun testDsl_productFlavor() {
    // We add minCompileSdkVersion to defaultConfig and a product flavor to ensure that the
    // product flavor value trumps the defaultConfig value.
    val build =
      rule.build {
        androidLibrary {
          android {
            defaultConfig {
              aarMetadata {
                minCompileSdk = 27
                minAgpVersion = "3.0.0"
                minCompileSdkExtension = 2
              }
            }
            flavorDimensions += "foo"
            productFlavors {
              create("premium") {
                it.aarMetadata {
                  minCompileSdk = 28
                  minAgpVersion = "3.1.0"
                  minCompileSdkExtension = 3
                }
              }
            }
          }
        }
      }

    build.executor.run(":lib:assemblePremiumDebug")
    build.androidLibrary().assertAar(AarSelector.DEBUG.withFlavor("premium")) {
      aarMetadata {
        minCompileSdk().isEqualTo("28")
        minAgpVersion().isEqualTo("3.1.0")
        minCompileSdkExtension().isEqualTo("3")
        coreLibraryDesugaringEnabled().isEqualTo("false")
        desugarJdkLibId().isNull()
      }
    }
  }

  @Test
  fun testDsl_buildType() {
    // We add minCompileSdkVersion to defaultConfig, a product flavor, and the debug build
    // type to ensure that the build type value trumps the other values.
    val build =
      rule.build {
        androidLibrary {
          android {
            defaultConfig {
              aarMetadata {
                minCompileSdk = 27
                minAgpVersion = "3.0.0"
                minCompileSdkExtension = 2
              }
            }
            flavorDimensions += "foo"
            productFlavors {
              create("premium") {
                it.aarMetadata {
                  minCompileSdk = 28
                  minAgpVersion = "3.1.0"
                  minCompileSdkExtension = 3
                }
              }
            }
            buildTypes {
              named("debug") {
                it.aarMetadata {
                  minCompileSdk = 29
                  minAgpVersion = "3.2.0"
                  minCompileSdkExtension = 4
                }
              }
            }
          }
        }
      }

    build.executor.run(":lib:assemblePremiumDebug")
    build.androidLibrary().assertAar(AarSelector.DEBUG.withFlavor("premium")) {
      aarMetadata {
        minCompileSdk().isEqualTo("29")
        minAgpVersion().isEqualTo("3.2.0")
        minCompileSdkExtension().isEqualTo("4")
        coreLibraryDesugaringEnabled().isEqualTo("false")
        desugarJdkLibId().isNull()
      }
    }
  }

  @Test
  fun testVariantApi() {
    val build =
      rule.build {
        androidLibrary {
          android {
            defaultConfig {
              aarMetadata {
                minCompileSdk = 26
                minAgpVersion = "2.0.0"
                minCompileSdkExtension = 1
              }
            }
          }
          pluginCallbacks += LibCallback::class.java
        }
      }
    build.executor.run(":lib:assembleDebug")
    build.androidLibrary().assertAar(AarSelector.DEBUG) {
      aarMetadata {
        minCompileSdk().isEqualTo("27")
        minAgpVersion().isEqualTo("3.0.0")
        minCompileSdkExtension().isEqualTo("2")
        coreLibraryDesugaringEnabled().isEqualTo("false")
        desugarJdkLibId().isNull()
      }
    }
  }

  class LibCallback : LibraryComponentCallback {
    override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
      androidComponents.apply {
        onVariants(
          selector().all(),
          {
            it.aarMetadata.minCompileSdk.set(27)
            it.aarMetadata.minAgpVersion.set("3.0.0")
            it.aarMetadata.minCompileSdkExtension.set(2)
          },
        )
      }
    }
  }

  @Test
  fun testCompileSdkPreview() {
    val build = rule.build { androidLibrary { android { compileSdkPreview = "Tiramisu" } } }

    build.executor.run(":lib:writeDebugAarMetadata")

    val aarMetadataFile =
      build
        .androidLibrary()
        .resolve(InternalArtifactType.AAR_METADATA)
        .resolve("debug/writeDebugAarMetadata/${AarMetadataTask.AAR_METADATA_FILE_NAME}")

    AarMetadataSubject.assertThat(aarMetadataFile) { forceCompileSdkPreview().isEqualTo("Tiramisu") }
  }

  @Test
  fun testMinSdkWithMinor() {
    val build =
      rule.build {
        androidLibrary { android { defaultConfig { aarMetadata { minCompileSdk { version = release(33) { minorApiLevel = 1 } } } } } }
      }

    build.executor.run(":lib:assembleDebug")

    build.androidLibrary().assertAar(AarSelector.DEBUG) {
      aarMetadata {
        this.minCompileSdk().isEqualTo("33")
        this.minCompileSdkMinor().isEqualTo("1")
      }
    }
  }
}
