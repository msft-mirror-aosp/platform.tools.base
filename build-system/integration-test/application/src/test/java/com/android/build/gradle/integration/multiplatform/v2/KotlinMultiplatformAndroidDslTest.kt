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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.plugins.AndroidKotlinMultiplatformLibraryComponentCallback
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformAndroidDslTest {

    @get:Rule
    val rule = GradleRule.from {
        androidKotlinMultiplatformLibrary(":library", createMinimumProject = false) {
            android {
                namespace = "com.mylibrary.foo"
            }
        }
    }

    @Test
    fun testCompileSdkVersionRelease() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":library") {
                android {
                    compileSdk {
                        version = release(36) {
                            minorApiLevel = 0
                            sdkExtension = 4
                        }
                    }
                }
                pluginCallbacks += SdkReleaseCallback::class.java
            }
        }

        build.executor.run(":library:assembleAndroidMain")
    }

    class SdkReleaseCallback : AndroidKotlinMultiplatformLibraryComponentCallback {
        override fun handleExtension(
            project: Project,
            extension: KotlinMultiplatformAndroidComponentsExtension
        ) {
            extension.finalizeDsl { extension ->
                assert(extension.compileSdk == 36) {
                    "compileSdk should be 36"
                }
                assert(extension.compileSdkExtension == 4) {
                    "compileSdkExtension should be 4"
                }
            }
        }
    }

    @Test
    fun testMinSdkVersionRelease() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":library") {
                android {
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                    minSdk {
                        version = release(36)
                    }
                }
            }
        }

        build.executor.run(":library:assembleAndroidMain")

        build.kotlinMultiplatformLibrary(":library").assertAar(AarSelector.NO_BUILD_TYPE) {
            manifest().contains("android:minSdkVersion=\"36\"")
        }
    }

    @Test
    fun testMinSdkVersionPreview() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":library") {
                android {
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                    minSdk {
                        version = preview("S")
                    }
                }
            }
        }

        build.executor.run(":library:assembleAndroidMain")

        build.kotlinMultiplatformLibrary(":library").assertAar(AarSelector.NO_BUILD_TYPE) {
            manifest().contains("android:minSdkVersion=\"S\"")
        }
    }
}
