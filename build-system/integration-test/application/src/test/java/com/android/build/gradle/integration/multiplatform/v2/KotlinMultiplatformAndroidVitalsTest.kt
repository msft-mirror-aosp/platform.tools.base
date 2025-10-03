/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.SdkConstants.MAX_SUPPORTED_ANDROID_PLATFORM_VERSION
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.internal.plugins.KotlinMultiplatformAndroidPlugin.Companion.ANDROID_EXTENSION_ON_KOTLIN_EXTENSION_NAME
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformAndroidVitalsTest {

    @get:Rule
    val rule = GradleRule.from {
        androidKotlinMultiplatformLibrary(":shared") { }
    }

    @Test
    fun testMissingCompileSdkException() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":shared") {
                android {
                    compileSdk = null
                }
            }
        }
        val result = build.executor.expectFailure().run(":shared:assembleAndroidMain")
        result.assertErrorContains(
            "compileSdk version is not set.\n" +
                    "Specify the compileSdk version in the module's build file like so:\n" +
                    "kotlin {\n" +
                    "    $ANDROID_EXTENSION_ON_KOTLIN_EXTENSION_NAME {\n" +
                    "        compileSdk = ${MAX_SUPPORTED_ANDROID_PLATFORM_VERSION.apiLevel}\n" +
                    "    }\n" +
                    "}\n"
        )
    }

    /**
     * To ensure hooks against the kotlin multiplatform plugin can be invoked eagerly if kmp is
     * applied first.
     */
    @Test
    fun kotlinMultiplatformPluginIsAppliedFirst() {
        rule.build.executor.run(":shared:androidPrebuild")
    }

    @Test
    fun `fail when another android plugin is applied before kmp android`() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":kmpModule") {
                // Android lib plugin has to be applied before the kotlin multiplatform lib plugin
                applyPlugin(PluginType.ANDROID_LIB, applyFirst = true)
            }
        }
        val result = build.executor.expectFailure().run(":kmpModule:assembleAndroidMain")
        result.assertErrorContains(
            "'com.android.kotlin.multiplatform.library' and 'com.android.library' plugins cannot be applied in the same project."
        )
    }

    @Test
    fun creatingTwoUnitTestCompilationsShouldFail() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":kmpModule") {
                android {
                    withHostTest {}
                }
                android {
                    withHostTest {}
                }
            }
        }

        val result = build.executor.expectFailure().run(":kmpModule:assembleAndroidMain")
        result.assertErrorContains(
            "Android host tests have already been enabled, and a corresponding compilation (`hostTest`) has already been created."
        )
    }

    @Test
    fun creatingArbitraryCompilationShouldFail() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":kmpModule") {
                android {
                    compilations.create("randomCompilationName") { }
                }
            }
        }

        val result = build.executor.expectFailure().run(":kmpModule:assembleAndroidMain")
        result.assertErrorContains(
            "Kotlin multiplatform android plugin doesn't support creating arbitrary compilations."
        )
    }
}
