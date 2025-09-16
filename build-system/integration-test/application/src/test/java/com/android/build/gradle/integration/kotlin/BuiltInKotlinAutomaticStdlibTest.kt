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

package com.android.build.gradle.integration.kotlin

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.testutils.TestUtils
import org.junit.Rule
import org.junit.Test

class BuiltInKotlinAutomaticStdlibTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

            HelloWorldAndroid.setupKotlin(files)
        }
    }

    @Test
    fun testKotlinStdlibAutomaticallyAdded() {
        val result =
            rule.build
                .executor
                .run(":app:dependencies", "--configuration", "debugCompileClasspath")
        result.assertOutputContains(
            "--- org.jetbrains.kotlin:kotlin-stdlib:2.2.10"
        )
    }

    @Test
    fun testKotlinStdlibAddedByUser() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    // This version number should not be changed when upgrading Kotlin. If it must
                    // be changed, it should be set to a version other than KOTLIN_VERSION_FOR_TESTS
                    // to test that this version is used instead of KOTLIN_VERSION_FOR_TESTS.
                    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
                }
            }
        }
        val result =
            build.executor
                .run(":app:dependencies", "--configuration", "debugCompileClasspath")
        result.assertOutputContains("--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
        result.assertOutputDoesNotContain(
            "--- org.jetbrains.kotlin:kotlin-stdlib:${TestUtils.KOTLIN_VERSION_FOR_TESTS}"
        )
    }

    @Test
    fun testKotlinStdlibDefaultDependencyFalse() {
        val build =
            rule.build {
                gradleProperties {
                    add("kotlin.stdlib.default.dependency", "false")
                }
            }
        val result =
            build.executor
                .run(":app:dependencies", "--configuration", "debugCompileClasspath")
        result.assertOutputDoesNotContain(
            "--- org.jetbrains.kotlin:kotlin-stdlib:${TestUtils.KOTLIN_VERSION_FOR_TESTS}"
        )
    }

    /** Regression test for b/443037365. */
    @Test
    fun testKotlinStdlibWithoutVersion() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    implementation("org.jetbrains.kotlin:kotlin-stdlib")
                }
            }
            gradleProperties {
                add("kotlin.stdlib.default.dependency", "false")
            }
        }
        val result = build.executor.run(":app:dependencies", "--configuration", "debugCompileClasspath")
        result.assertOutputContains("--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.10")
    }
}
