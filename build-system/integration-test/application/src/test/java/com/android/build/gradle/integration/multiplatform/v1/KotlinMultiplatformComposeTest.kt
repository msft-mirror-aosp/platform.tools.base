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

package com.android.build.gradle.integration.multiplatform.v1

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.internal.TaskManager.Companion.COMPOSE_UI_VERSION
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils
import com.android.testutils.TestUtils.KOTLIN_VERSION_FOR_COMPOSE_TESTS
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.Rule
import org.junit.Test

/** Check Compose works with KMP projects. */
class KotlinMultiplatformComposeTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withGradleOptions {
            // this is necessary because KMP does not work with project Isolation.
            // There were some tests where it worked but that's because they used the root
            // project, and it's fine in the root (the KMP plugin accesses things in the root
            // folder so if it's already there it's fine)
            withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        }.from {
            androidLibrary {
                applyPlugin(PluginType.KOTLIN_MPP, version = KOTLIN_VERSION_FOR_COMPOSE_TESTS)
                pluginCallbacks += Callback::class.java
                android {
                    defaultConfig.minSdk = 24
                    buildFeatures {
                        compose = true
                    }
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_1_8
                        targetCompatibility = JavaVersion.VERSION_1_8
                    }
                    composeOptions {
                        useLiveLiterals = false
                        kotlinCompilerExtensionVersion = TestUtils.COMPOSE_COMPILER_FOR_TESTS
                    }
                }
                dependencies {
                    implementation("androidx.compose.ui:ui-tooling:$COMPOSE_UI_VERSION")
                    implementation("androidx.compose.material:material:$COMPOSE_UI_VERSION")
                }
                files.add(
                    "src/main/kotlin/com/Example.kt",
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

    class Callback: GenericCallback {
        override fun handleProject(project: Project) {
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.apply {
                androidTarget { target ->
                    target.compilations.all { compilation ->
                        compilation.compilerOptions.options.jvmTarget.set(JvmTarget.JVM_1_8)
                    }
                }
            }
        }
    }

    /** Regression test for b/203594737. */
    @Test
    fun testLibraryBuilds() {
        rule.build.executor
            .with(BooleanOption.USE_ANDROID_X, true)
            .run(":lib:assembleDebug")
    }
}
