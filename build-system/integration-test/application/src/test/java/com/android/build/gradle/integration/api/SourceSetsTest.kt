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

package com.android.build.gradle.integration.api

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.google.common.truth.Truth
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.readText

class SourceSetsTest {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                namespace = "com.example.api.use"
                defaultConfig {
                    applicationId = "com.example.api.use"
                }
            }
            pluginCallback = MyAppCallback::class.java
        }
    }

    class MyAppCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
                val reproTask = project.tasks.register(
                    "${variant.name}ReproTask",
                    ReproducerTask::class.java
                ) {
                    println("ReproTask configured.");
                }

                variant.sources.res?.addGeneratedSourceDirectory(reproTask, ReproducerTask::outputDirectory);
            }
        }
    }

    @Test
    fun sourceRegistrationShouldBeSuccessful() {
        val result = rule.build.executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .run(":app:assembleDebug")
        Truth.assertThat(result.didWorkTasks.contains(":app:debugReproTask")).isTrue()
        Truth.assertThat(result.stdout.findAll("ReproducerTask called !").count())
            .isEqualTo(1)
    }

    class AssetCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                onVariants(selector().all(), {
                    // do nothing, just request it
                    it.sources.assets
                })
            }
        }
    }

    class AssetViaOldApiCallback: LegacyApplicationCallback {
        override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
            extension.applicationVariants.all {
                extension.sourceSets.getByName(it.name).assets.srcDir(
                    project.layout.projectDirectory.dir(
                        java.nio.file.Paths.get(
                            "extra-assets",
                            it.name
                        ).toString()
                    )
                )
            }
        }
    }

    @Test
    fun testOldAndNewVariantApiSourcesAccess() {
        val build = rule.build {
            androidApplication {
                files {
                    add("extra-assets/debug/file.txt",  "some asset")
                }
                // add more plugins
                pluginCallbacks.add(AssetCallback::class.java)
                pluginCallbacks.add(AssetViaOldApiCallback::class.java)
            }
        }

        val app = build.androidApplication()

        val result = build.executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .run("clean", ":app:mergeDebugAssets")
        Truth.assertThat(result.failedTasks).isEmpty()
        // check the file got merged
        Truth.assertThat(
            build.androidApplication().intermediatesDir.resolve("assets/debug/mergeDebugAssets/file.txt").toFile().exists()
        ).isTrue()
    }

    class RegisterTaskViaOldApi: LegacyApplicationCallback {
        override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
            extension.applicationVariants.all { variant ->
                val reproTask = project.tasks.register(
                    "${variant.name}ReproTask",
                    ReproducerTask::class.java
                ) { task ->
                       task.outputDirectory.set(File(project.projectDir, "tmp_test"))
                }

                val variantSourceSet = extension.sourceSets.getByName(variant.name)
                val outputDir = reproTask.flatMap(ReproducerTask::outputDirectory);
                variantSourceSet.res.srcDir(outputDir);
            }
        }
    }

    /**
     * Test that create tasks very early and ensures that a directory registered using the
     * old variant API is part of the sources for the project.
     */
    @Test
    fun testOldVariantApiWithEarlyTaskRealization() {
        val build = rule.build {
            androidApplication {
                // replace previous plugin
                pluginCallback = RegisterTaskViaOldApi::class.java
            }
        }

        build.executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .run(":app:mapDebugSourceSetPaths")

        val content = build.androidApplication().intermediatesDir
            .resolve("source_set_path_map/debug/mapDebugSourceSetPaths/file-map.txt")
            .readText()

        Truth.assertThat(content).contains("tmp_test")
    }
}

/** Task to  generate a placeholder JNI lib */
abstract class ReproducerTask: DefaultTask() {

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        System.out.println("ReproducerTask called !")
    }
}
