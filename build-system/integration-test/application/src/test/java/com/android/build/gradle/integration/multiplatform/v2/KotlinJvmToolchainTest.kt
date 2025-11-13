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

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.plugins.AndroidKotlinMultiplatformLibraryComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.KotlinMultiplatformCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.Rule
import org.junit.Test

class KotlinJvmToolchainTest {
    @get:Rule
    val rule = GradleRule.from {
        androidKotlinMultiplatformLibrary(":library", createMinimumProject = false) {
            android {
                withJava()
                namespace = "com.mylibrary.foo"
                compileSdk = DEFAULT_COMPILE_SDK_VERSION

                withHostTest { }
                withDeviceTest {  }
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
            files.add(
                "src/androidMain/java/JavaLibFoo.java",
                //language=kotlin
                """
                        package com.mylibrary.foo;
                        class JavaLibFoo {}
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
        val result = build.executor
            .withFailOnWarning(false) // b/455891987
            .run("clean", ":library:assemble")
        ScannerSubject.assertThat(result.stdout).contains("kotlinc jvm-target=21")
        ScannerSubject.assertThat(result.stdout).contains("javac jvm-target=21")
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
        val result = build.executor
            .withFailOnWarning(false) // b/455891987
            .run("clean", ":library:assemble")
        ScannerSubject.assertThat(result.stdout).contains("kotlinc jvm-target=11")
        ScannerSubject.assertThat(result.stdout).contains("javac jvm-target=11")
    }

    @Test
    fun testCompilationLevelJvmTargetOverrideTargetLevelJvmTarget() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":library") {
                android {
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
                }
                pluginCallbacks += SetCompilationCompilerOptionsCallback::class.java
            }
        }
        val result = build.executor
            .withFailOnWarning(false) // b/455891987
            .run("clean", ":library:assemble")
        ScannerSubject.assertThat(result.stdout).contains("kotlinc jvm-target=11")
        ScannerSubject.assertThat(result.stdout).contains("javac jvm-target=11")
    }

    @Test
    fun testSettingJavaCompileTargetUsingVariantApi() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":library") {
                kotlin {
                    jvmToolchain(21)
                }
                android {
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
                }
                pluginCallbacks += KmpVariantApiCallback::class.java
            }
        }
        val result = build.executor
            .withFailOnWarning(false) // b/455891987
            .run("clean", ":library:assemble")
        ScannerSubject.assertThat(result.stdout).contains("kotlinc jvm-target=11")
        ScannerSubject.assertThat(result.stdout).contains("javac jvm-target=17")
    }

    class JvmTargetCallback: GenericCallback {
        override fun handleProject(project: Project) {
            project.afterEvaluate {
                project.tasks.named("compileAndroidMain") {
                    it.doLast { task ->
                        task as KotlinCompile
                        val jvmTarget = task.compilerOptions.jvmTarget.get().target
                        println("kotlinc jvm-target=$jvmTarget")
                    }
                }

                project.tasks.named("compileAndroidMainJavaWithJavac") {
                    it.doLast { task ->
                        task as JavaCompile
                        val jvmTarget = task.targetCompatibility
                        println("javac jvm-target=$jvmTarget")
                    }
                }
            }
        }
    }

    class KmpVariantApiCallback: AndroidKotlinMultiplatformLibraryComponentCallback {
        override fun handleExtension(
            project: Project,
            extension: KotlinMultiplatformAndroidComponentsExtension
        ) {
            extension.onVariants { variant ->
                variant.configureJavaCompileTask { task ->
                    task.targetCompatibility = "17"
                }
            }
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
                            compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
                        }
                    }
                }
            }
        }
    }
}
