/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.integration.model

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.builder.model.v2.ide.SyncIssue
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import java.io.File

class SourcesDirectoryModelTest : ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                defaultConfig.minSdk = 14
            }
        }
    }

    @Test
    fun `test adding source directories to IDE model with addGeneratedSourceDirectory and addStaticSourceDirectory`() {
        val build = rule.build {
            androidApplication {
                pluginCallback = AppCallback::class.java
            }
        }

        val result = build.modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels()

        with(result).compareBasicAndroidProject(goldenFile = "SourceDirectories")
        with(result).compareAndroidProject(goldenFile = "SourceDirectories_AndroidProject")
    }

    class AppCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                onVariants(selector().all()) { variant ->
                    // use addGeneratedSourceDirectory for adding generated source directories
                    val assetCreationTask =
                        project.tasks.register(
                            "create${variant.name}Asset",
                            AssetCreatorTask::class.java
                        ) {
                            it.outputDirectory.set(
                                File(
                                    project.layout.buildDirectory.asFile.get(),
                                    "assets"
                                )
                            )
                        }

                    variant.sources.assets?.addGeneratedSourceDirectory(assetCreationTask) {
                        it.outputDirectory
                    }

                    // add java generator task
                    val javaCreationTask = project.tasks.register(
                        "create${variant.name}JavaGenerator",
                        JavaCreatorTask::class.java
                    ) {
                        it.outputDirectory.set(
                            File(
                                project.layout.buildDirectory.asFile.get(),
                                "java_stubs"
                            )
                        )
                    }

                    variant.sources.java?.addGeneratedSourceDirectory(javaCreationTask) {
                        it.outputDirectory
                    }

                    // use addStaticSourceDirectory to add static directories
                    val staticJavaPath = "src/${variant.name}/staticJava"
                    File(project.projectDir, staticJavaPath).mkdirs()

                    variant.sources.java?.addStaticSourceDirectory(staticJavaPath)

                    val staticAssetsPath = "src/${variant.name}/staticAssets"
                    File(project.projectDir, staticAssetsPath).mkdirs()

                    variant.sources.assets?.addStaticSourceDirectory(staticAssetsPath)
                }
            }
        }
    }

    @Test
    fun `test adding generated source directory to IDE model with registerJavaGeneratingTask old API`() {
        val build = rule.build {
            androidApplication {
                pluginCallback = LegacyAppCallback::class.java
            }
        }

        val result = build.modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels()

        with(result).compareBasicAndroidProject(goldenFile = "SourceDirectories2")
        with(result).compareAndroidProject(goldenFile = "SourceDirectories2_AndroidProject")
    }

    class LegacyAppCallback: LegacyApplicationCallback {
        override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
            extension.applicationVariants.all { variant ->
                val outDir = File(project.layout.buildDirectory.asFile.get(), "java_stubs")
                // add java generator task
                val javaCreationTask =
                    project.tasks.register("create${variant.name}JavaGenerator", JavaCreatorTask::class.java) {
                        it.outputDirectory.set(outDir)
                    }
                variant.registerJavaGeneratingTask(javaCreationTask, outDir)
            }
        }
    }
}

abstract class AssetCreatorTask: DefaultTask() {
    @get:OutputFiles
    abstract val outputDirectory: DirectoryProperty
    @TaskAction
    fun taskAction() { /* pretend we write content here */ }
}

abstract class JavaCreatorTask: DefaultTask() {
    @get:OutputFiles
    abstract val outputDirectory: DirectoryProperty
    @TaskAction
    fun taskAction() { /* pretend we write content here */ }
}

