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

package com.android.build.gradle.integration.fixture

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * A test that validates injecting custom plugins into a test project via the
 * [GradleRule] fixture.
 */
class AppPluginTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            pluginCallbacks += AppCallback::class.java

            files {
                add("src/main/assets/FileToTransform.txt", "initial content")
            }
        }
    }

    @Test
    fun testReleaseVariantIsDisabled() {
        val build = rule.build

        val result = build.executor.expectFailure().run(":app:assembleRelease")
        ScannerSubject.assertThat(result.stderr)
            .contains("Cannot locate tasks that match ':app:assembleRelease' as task 'assembleRelease' not found in project ':app'.")
    }

    @Test
    fun testCustomTransform() {
        val build = rule.build

        build.executor.run(":app:assembleDebug")

        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            containsFileWithContent("assets/FileToTransform.txt", "transformed content")
        }
    }

    class AppCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                beforeVariants(selector().withBuildType("release")) { variant ->
                    variant.enable = false
                }
                onVariants { variant ->
                    // only debug should be here now
                    val taskProvider = project.tasks.register(
                        "transformAssets",
                        AppPluginTestTransformAssetsTask::class.java
                    )

                    // TransformAssetsTask will change the assets directory
                    variant.artifacts.use(taskProvider)
                        .wiredWithDirectories(
                            AppPluginTestTransformAssetsTask::inputDir,
                            AppPluginTestTransformAssetsTask::outputDir
                        ).toTransform(SingleArtifact.ASSETS)
                }
            }
        }
    }
}

abstract class AppPluginTestTransformAssetsTask: DefaultTask() {

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun taskAction() {
        // We must copy the contents of the input directory to the output directory before our transformation
        inputDir.get().asFile.copyRecursively(outputDir.get().asFile)

        // Transform an existing file by updating its contents
        val fileToTransform = File(outputDir.get().asFile, "FileToTransform.txt")
        val transformedContent = fileToTransform.readText()
            .replace("initial", "transformed")
        fileToTransform.writeText(transformedContent)
    }
}
