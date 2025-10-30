/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.dsl

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.integration.common.fixture.project.builder.GroovyBuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.KtsBuildWriter
import com.google.common.truth.Truth
import org.junit.Test

/**
 * Class to validate that we support all the methods from the Android extensions,
 *
 * This does not test the content, this is handled by [BasicDslProxyTest] and [DslRecorderTest]
 */
class AndroidProxyTest {

    @Test
    fun testFullApp_Groovy() {
        val content = generateGroovyContent {
            generateFullDsl()
        }
        Truth.assertThat(content).isEqualTo("""
            android {
              namespace = 'foo'
              compileSdk {
                version = release(36)
              }
              defaultConfig {
                minSdk {
                  version = release(21)
                }
                maxSdk {
                  version = release(99)
                }
                targetSdk {
                  version = release(36)
                }
              }
              androidResources {
                generateLocaleConfig = true
              }
              compileOptions {
                coreLibraryDesugaringEnabled = true
              }
              splits {
                abi {
                  reset()
                  include('x86', 'armeabi')
                }
              }
              buildTypes {
                named('debug') {
                  debuggable = false
                }
              }
            }

        """.trimIndent())

    }

    @Test
    fun testFullApp_Kts() {
        val content = generateKtsContent {
            generateFullDsl()
        }
        Truth.assertThat(content).isEqualTo("""
            android {
              namespace = "foo"
              compileSdk {
                version = release(36)
              }
              defaultConfig {
                minSdk {
                  version = release(21)
                }
                maxSdk {
                  version = release(99)
                }
                targetSdk {
                  version = release(36)
                }
              }
              androidResources {
                generateLocaleConfig = true
              }
              compileOptions {
                isCoreLibraryDesugaringEnabled = true
              }
              splits {
                abi {
                  reset()
                  include("x86", "armeabi")
                }
              }
              buildTypes {
                named("debug") {
                  isDebuggable = false
                }
              }
            }

        """.trimIndent())
    }

    @Test
    fun testCompilePreview_Kts() {
        val content = generateKtsContent {
            compileSdk {
                version = preview("FOO")
            }
        }

        Truth.assertThat(content).isEqualTo("""
            android {
              compileSdk {
                version = preview("FOO")
              }
            }

        """.trimIndent())
    }

    @Test
    fun testCompileAddon_Kts() {
        val content = generateKtsContent {
            compileSdk {
                version = addon("vendor", "name", 36)
            }
        }

        Truth.assertThat(content).isEqualTo("""
            android {
              compileSdk {
                version = addon("vendor", "name", 36)
              }
            }

        """.trimIndent())
    }

    @Test
    fun testCompileMinorApi_Kts() {
        val content = generateKtsContent {
            compileSdk {
                version = release(36) {
                    minorApiLevel = 1
                }
            }
        }

        Truth.assertThat(content).isEqualTo("""
            android {
              compileSdk {
                version = release(36) {
                  minorApiLevel = 1
                }
              }
            }

        """.trimIndent())
    }

    @Test
    fun testCompileSdkExtensionApi_Kts() {
        val content = generateKtsContent {
            compileSdk {
                version = release(36) {
                    sdkExtension = 17
                }
            }
        }

        Truth.assertThat(content).isEqualTo("""
            android {
              compileSdk {
                version = release(36) {
                  sdkExtension = 17
                }
              }
            }

        """.trimIndent())
    }

    @Test
    fun testCompileFullRelease_Kts() {
        val content = generateKtsContent {
            compileSdk {
                version = release(36) {
                    minorApiLevel = 1
                    sdkExtension = 17
                }
            }
        }

        Truth.assertThat(content).isEqualTo("""
            android {
              compileSdk {
                version = release(36) {
                  minorApiLevel = 1
                  sdkExtension = 17
                }
              }
            }

        """.trimIndent())
    }

    @Test
    fun testCompileEmptylease_Kts() {
        val content = generateKtsContent {
            compileSdk {
                version = release(36) { }
            }
        }

        Truth.assertThat(content).isEqualTo("""
            android {
              compileSdk {
                version = release(36)
              }
            }

        """.trimIndent())
    }

    @Test
    fun testMinPreview_Kts() {
        val content = generateKtsContent {
            defaultConfig {
                minSdk {
                    version = preview("FOO")
                }
            }
        }

        Truth.assertThat(content).isEqualTo("""
            android {
              defaultConfig {
                minSdk {
                  version = preview("FOO")
                }
              }
            }

        """.trimIndent())
    }

    @Test
    fun testTargetPreview_Kts() {
        val content = generateKtsContent {
            defaultConfig {
                targetSdk {
                    version = preview("FOO")
                }
            }
        }

        Truth.assertThat(content).isEqualTo("""
            android {
              defaultConfig {
                targetSdk {
                  version = preview("FOO")
                }
              }
            }

        """.trimIndent())
    }

    @Test
    fun publishing_singleVariant() {
        val content = generateKtsContent {
            publishing {
                singleVariant("foo") {
                    publishApk()
                }
            }
        }

        Truth.assertThat(content).isEqualTo("""
            android {
              publishing {
                singleVariant("foo") {
                  publishApk()
                }
              }
            }

        """.trimIndent())
    }


    private fun generateKtsContent(action: ApplicationExtension.() -> Unit): String {
        val dslRecorder = DefaultDslRecorder()
        dslRecorder.runNestedBlock("android", listOf(), ApplicationExtension::class.java, action)

        val kts = KtsBuildWriter()
        dslRecorder.writeContent(kts)
        return kts.toString()
    }

    private fun generateGroovyContent(action: ApplicationExtension.() -> Unit): String {
        val dslRecorder = DefaultDslRecorder()
        dslRecorder.runNestedBlock("android", listOf(), ApplicationExtension::class.java, action)

        val groovy = GroovyBuildWriter()
        dslRecorder.writeContent(groovy)
        return groovy.toString()
    }

    private fun ApplicationExtension.generateFullDsl() {
        apply {
            namespace = "foo"

            compileSdk {
                version = release(36)
            }

            defaultConfig {
                minSdk {
                    version = release(21)
                }
                maxSdk {
                    version = release(99)
                }
                targetSdk {
                    version = release(36)
                }
            }

            androidResources {
                generateLocaleConfig = true
            }

            compileOptions {
                isCoreLibraryDesugaringEnabled = true
            }

            splits {
                abi {
                    reset()
                    include("x86", "armeabi")
                }
            }

            buildTypes {
                named("debug") {
                    it.isDebuggable = false
                }
            }
        }
    }
}
