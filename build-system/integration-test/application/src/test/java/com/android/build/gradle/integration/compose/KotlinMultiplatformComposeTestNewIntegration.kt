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

package com.android.build.gradle.integration.compose

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.KotlinMultiplatformCallback
import com.android.build.gradle.internal.TaskManager
import com.android.testutils.TestUtils
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.Rule
import org.junit.Test
import kotlin.collections.plusAssign

class KotlinMultiplatformComposeTestNewIntegration {

    @get:Rule
    val rule = GradleRule.Companion.configure()
        .withGradleOptions {
            // this is necessary because KMP does not work with project Isolation.
            // There were some tests where it worked but that's because they used the root
            // project, and it's fine in the root (the KMP plugin accesses things in the root
            // folder so if it's already there it's fine)
            withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        }.from {
            androidKotlinMultiplatformLibrary(":library") {
                applyPlugin(
                    PluginType.COMPOSE_COMPILER_PLUGIN,
                    version = TestUtils.KOTLIN_VERSION_FOR_COMPOSE_TESTS
                )

                pluginCallbacks += SetCompilationCompilerOptionsCallback::class.java
                android {
                    androidResources.enable = true
                }
                files.add(
                    "src/androidMain/kotlin/com/Example.kt",
                    //language=kotlin
                    """
                        package foo

                        import androidx.compose.foundation.layout.Column
                        import androidx.compose.material.Text
                        import androidx.compose.runtime.Composable

                        @Composable
                        fun MainView() {
                            Column {
                                Text(text = "Hello World")
                            }
                        }
                    """.trimIndent()
                )
            }
        }

    class SetCompilationCompilerOptionsCallback : KotlinMultiplatformCallback {
        override fun handleExtension(
            project: Project,
            extension: KotlinMultiplatformExtension
        ) {
            extension.apply {
                (this as ExtensionAware).extensions.findByType(
                    KotlinMultiplatformAndroidLibraryTarget::class.java
                )!!.apply {
                    compilations.all {
                        it.compileTaskProvider.configure {
                            compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
                        }
                    }
                }

                sourceSets.androidMain.dependencies {
                    implementation("androidx.compose.ui:ui-tooling:${TaskManager.COMPOSE_UI_VERSION}")
                    implementation("androidx.compose.material:material:${TaskManager.COMPOSE_UI_VERSION}")
                }
            }
        }
    }

    @Test
    fun testLibraryBuilds() {
        rule.configure()
            .build
            .executor
            .withFailOnWarning(false) // b/455891987
            .run(":lib:assemble")
    }
}
