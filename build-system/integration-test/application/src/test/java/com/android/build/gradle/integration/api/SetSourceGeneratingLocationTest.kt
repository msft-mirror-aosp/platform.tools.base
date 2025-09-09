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

package com.android.build.gradle.integration.api

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.PluginCallback
import com.google.common.truth.Truth
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SetSourceGeneratingLocationTest(
    callbackType: Class<out PluginCallback>,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "callbackType_{0}")
        fun params() = listOf(
            AddRelocatedJavaResourcesWithScopedApiCallback::class.java,
            AddRelocatedJavaResourcesWithSourceApiCallback::class.java,
        )
    }

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                namespace = "com.example.api.java_res"
                defaultConfig.applicationId = "com.example.api.java_res"
            }
            pluginCallbacks += callbackType
        }
    }

    @Test
    fun ensureGeneratedJavaResTasksAreRunning() {
        val result = rule.build.executor
            .run(":app:mergeDebugJavaResource")
        Truth.assertThat(result.didWorkTasks).contains(":app:writeDebugJavaResources")
        Truth.assertThat(
                rule.build.androidApplication(":app")
                    .buildDir.resolve("_special_/foo.txt").toFile().exists()).isTrue()
    }
}

abstract class AddJavaResourcesAtLocationWriter: DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun execute() {
        outputDir.get().asFile.mkdirs()
        outputDir.file("foo.txt").get().asFile.writeText("foo")
    }

    companion object {
        fun createTask(
            project: Project,
        ): TaskProvider<AddJavaResourcesAtLocationWriter> =
            project.tasks.register(
                "writeDebugJavaResources",
                AddJavaResourcesAtLocationWriter::class.java
            )
    }
}

class AddRelocatedJavaResourcesWithScopedApiCallback: ApplicationComponentCallback {

    override fun handleExtension(
        project: Project,
        androidComponents: ApplicationAndroidComponentsExtension
    ) {
        androidComponents.onVariants(
            androidComponents.selector().withBuildType("debug")
        ) { variant ->
            val taskProvider = AddJavaResourcesAtLocationWriter.createTask(project)
            variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
                .use(taskProvider)
                .toAppend(
                    ScopedArtifact.JAVA_RES,
                    AddJavaResourcesAtLocationWriter::outputDir
                )
            taskProvider.configure { task ->
                task.outputDir.set(project.layout.buildDirectory.dir("_special_"))
            }
        }
    }
}

class AddRelocatedJavaResourcesWithSourceApiCallback: ApplicationComponentCallback {

    override fun handleExtension(
        project: Project,
        androidComponents: ApplicationAndroidComponentsExtension
    ) {
        androidComponents.onVariants(
            androidComponents.selector().withBuildType("debug")
        ) { variant ->
            val taskProvider = AddJavaResourcesAtLocationWriter.createTask(project)
            variant.sources.resources?.addGeneratedSourceDirectory(
                taskProvider,
                AddJavaResourcesAtLocationWriter::outputDir
            )
            taskProvider.configure { task ->
                task.outputDir.set(project.layout.buildDirectory.dir("_special_"))
            }
        }
    }
}
