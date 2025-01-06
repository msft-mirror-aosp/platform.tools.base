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

package com.android.build.gradle.integration.compose

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.options.BooleanOption
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.junit.Rule
import org.junit.Test

/** Tests Compose plugin options for KotlinCompile. */
class ComposePluginOptionsTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            applyPlugin(PluginType.COMPOSE_COMPILER_PLUGIN)
            android {
                defaultConfig {
                    minSdk = 24
                }
                buildFeatures {
                    compose = true
                }
            }
            kotlin {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                    freeCompilerArgs.addAll(
                        "-P",
                        "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=false"
                    )
                }

            }
            dependencies {
                implementation("androidx.compose.runtime:runtime:+")
            }
            files.add(
                "src/main/java/com/example/KotlinClass.kt",
                // language=kotlin
                """
                class KotlinClass
                """.trimIndent()
            )
        }
        gradleProperties {
            add(BooleanOption.USE_ANDROID_X, true)
        }
    }

    /** Regression test for b/318384658. */
    @Test
    fun `test AGP does not override user-specified plugin options`() {
        rule.build.executor.withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .run(":app:compileDebugKotlin")
    }
}
