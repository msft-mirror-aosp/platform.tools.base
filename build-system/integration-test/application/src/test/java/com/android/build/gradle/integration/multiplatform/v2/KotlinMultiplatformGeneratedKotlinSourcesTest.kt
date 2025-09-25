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
import com.android.build.gradle.integration.common.output.AarSubject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import java.io.File

class KotlinMultiplatformGeneratedKotlinSourcesTest {
    @get:Rule
    val rule = GradleRule.from {
        androidKotlinMultiplatformLibrary(":kmpLib", createMinimumProject = false) {
            android {
                namespace = "com.mylibrary.foo"
                compileSdk = DEFAULT_COMPILE_SDK_VERSION
            }

            pluginCallbacks += Callback::class.java
        }
    }

    class Callback: AndroidKotlinMultiplatformLibraryComponentCallback {
        override fun handleExtension(
            project: Project,
            extension: KotlinMultiplatformAndroidComponentsExtension
        ) {
            extension.onVariants { variant ->
                val creationTask = project.tasks.register(
                    "create${variant.name}KotlinGenerator",
                    AddKotlinSources::class.java
                ) {
                    it.outputDirectory.set(
                        File(
                            project.layout.buildDirectory.asFile.get(),
                            "kotlin_generated_sources"
                        )
                    )
                }

                // use addGeneratedSourceDirectory to add generated directories
                variant.sources.kotlin?.addGeneratedSourceDirectory(creationTask) {
                    it.outputDirectory
                }

                val staticKotlinSourcePath = "src/${variant.name}/static"
                val outputFile = File(
                    File(project.projectDir, staticKotlinSourcePath),
                    "com/mylibrary/foo/Bar.kt"
                )
                outputFile.parentFile.mkdirs()
                outputFile.writeText("""
                    package com.mylibrary.foo

                    class Bar {
                        fun message(): String = "a Bar instance"
                    }
                    """)

                // use addStaticSourceDirectory to add static directories
                variant.sources.kotlin?.addStaticSourceDirectory(staticKotlinSourcePath)
            }
        }
    }

    @Test
    fun testGeneratedKotlinSources() {
        val build = rule.build
        build.executor.run(":kmpLib:assembleAndroidMain")

        val action: AarSubject.() -> Unit = {
            mainJar {
                classes().containsExactly(
                    "com/mylibrary/foo/Bar",
                    "com/mylibrary/foo/Foo",
                )
            }
        }

        build.kotlinMultiplatformLibrary(":kmpLib").assertAar(AarSelector.NO_BUILD_TYPE, action)
    }
}

abstract class AddKotlinSources: DefaultTask() {

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun taskAction() {
        val outputFile = File(outputDirectory.asFile.get(), "com/mylibrary/foo/Foo.kt")
        outputFile.parentFile.mkdirs()
        outputFile.writeText("""
        package com.mylibrary.foo

        class Foo {
            fun message(): String = "a Foo instance"
        }
        """)
    }
}
