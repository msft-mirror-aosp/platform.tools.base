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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.Rule
import org.junit.Test

class KotlinJvmToolchainTest {
    @get:Rule
    val rule = GradleRule.from {
        androidKotlinMultiplatformLibrary(":library", createMinimumProject = false) {
            android {
                namespace = "com.mylibrary.foo"
                compileSdk = DEFAULT_COMPILE_SDK_VERSION
            }

            // Add a simple kotlin source file so that kotlin compilation task does work.
            files.add(
                "src/androidMain/kotlin/LibFoo.kt",
                //language=kotlin
                """
                        package com.mylibrary.foo
                        class LibFoo {}
                    """.trimIndent()
            )

            pluginCallbacks += JvmTargetCallback::class.java
        }
    }

    @Test
    fun testJvmToolchain() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":library") {
                kotlin {
                    jvmToolchain(21)
                }
            }
        }
        val result = build.executor.run("clean", ":library:compileAndroidMain")
        ScannerSubject.assertThat(result.stdout).contains("jvm-target=21")
    }

    @Test
    fun testJvmTargetOverrideJvmToolchain() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":library") {
                kotlin {
                    jvmToolchain(21)
                }
                android {
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }
        val result = build.executor.run("clean", ":library:compileAndroidMain")
        ScannerSubject.assertThat(result.stdout).contains("jvm-target=11")
    }

    class JvmTargetCallback: GenericCallback {
        override fun handleProject(project: Project) {
            project.afterEvaluate {
                project.tasks.named("compileAndroidMain") {
                    it.doLast { task ->
                        task as KotlinCompile
                        val jvmTarget = task.compilerOptions.jvmTarget.get().target
                        println("jvm-target=$jvmTarget")
                    }
                }
            }
        }
    }
}
