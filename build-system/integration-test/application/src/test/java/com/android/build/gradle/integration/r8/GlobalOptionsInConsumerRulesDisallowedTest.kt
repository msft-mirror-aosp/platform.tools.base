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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import org.gradle.internal.impldep.com.amazonaws.util.Throwables
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GlobalOptionsInConsumerRulesDisallowedTest(
    val globalOptionsInConsumerRulesDisallowed: Boolean,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "globalOptionsInConsumerRulesDisallowed={0}")
        fun proguardAndroidTxtDisallowed() = listOf(true, false)
    }

    @get:Rule
    val rule = GradleRule.from {
        gradleProperties {
            add(
                BooleanOption.R8_GLOBAL_OPTIONS_IN_CONSUMER_RULES_DISALLOWED,
                globalOptionsInConsumerRulesDisallowed
            )
        }
        androidLibrary {
            android {
                defaultConfig.minSdk = 30
                defaultConfig.consumerProguardFiles("consumer-rules.pro")
            }
        }
        androidJavaApplication {
            android {
                defaultConfig {
                    applicationId = "com.example.test"
                }
                dynamicFeatures.add(DEFAULT_FEATURE_PATH)

                buildTypes {
                    named("debug") {
                        it.isMinifyEnabled = true
                    }
                }
            }
        }
        androidFeature {
            android {
                namespace = "com.example.test.feature"
                defaultConfig.proguardFiles("consumer-rules.pro")
            }

            dependencies {
                implementation(project(DEFAULT_APP_PATH))
            }
        }
    }

    @Test
    fun `library allowed consumer content`() {
        populateLibraryConsumerRules(
            """
            #ignore commented -dontoptimize
            -keep class ClassToOptimize { *; }
        """.trimIndent())
        rule.build.executor.run(":lib:assembleDebug") // no failure

    }

    @Test
    fun `library banned consumer content`() {
        populateLibraryConsumerRules("-dontoptimize")

        if (globalOptionsInConsumerRulesDisallowed) {
            rule.build.executor.expectFailure().run(":lib:assembleDebug").apply {
                assertThat(Throwables.getRootCause(exception).message).contains(
                    "Global keep option -dontoptimize was specified as a consumerProguardFile"
                )
            }
        } else {
            rule.build.executor.run(":lib:assembleDebug")
        }
    }

    private fun populateLibraryConsumerRules(content: String) {
        rule.build {
            androidLibrary {
                files {
                    add(relativePath = "consumer-rules.pro", content = content)
                }
            }
        }
    }

    @Test
    fun `feature allowed consumer content`() {
        populateFeatureConsumerRules(
            """
            #ignore commented -dontoptimize
            -keep class ClassToOptimize { *; }
        """.trimIndent())
        rule.build.executor.run(":app:assembleDebug") // no failure
    }

    @Test
    fun `feature banned consumer content`() {
        populateFeatureConsumerRules("-dontoptimize")

        if (globalOptionsInConsumerRulesDisallowed) {
            // both assembling the dynamic feature and app fail, due to invalid global options
            rule.build.executor.expectFailure().run(":feature:assemble").apply {
                assertThat(Throwables.getRootCause(exception).message).contains(
                    "Global keep option -dontoptimize was specified in"
                )
            }
            rule.build.executor.expectFailure().run(":app:assembleDebug").apply {
                assertThat(Throwables.getRootCause(exception).message).contains(
                    "Global keep option -dontoptimize was specified in"
                )
            }
        } else {
            rule.build.executor.run(":feature:assemble", ":app:assembleDebug")
        }
    }

    private fun populateFeatureConsumerRules(content: String) {
        rule.build {
            androidFeature {
                files {
                    add(relativePath = "consumer-rules.pro", content = content)
                }
            }
        }
    }
}
