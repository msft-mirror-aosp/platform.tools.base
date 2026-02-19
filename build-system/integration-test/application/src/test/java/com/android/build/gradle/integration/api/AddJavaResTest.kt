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
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.PluginCallback
import com.google.common.truth.Truth
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AddJavaResTest(private val callbackType: Class<out PluginCallback>) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "callbackType_{0}")
    fun params() =
      listOf(
        AddJavaResourcesWithScopedApiCallback::class.java,
        AddJavaResourcesWithSourceApiCallback::class.java,
        AddJavaResourcesWithBothApisCallback::class.java,
      )
  }

  @get:Rule
  val rule =
    GradleRule.from {
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
    val builtProject = rule.build
    val result = builtProject.executor.run(":app:mergeDebugJavaResource")
    Truth.assertThat(result.didWorkTasks).contains(":app:writeDebugJavaResources")

    // additional checks when both APIs are used.
    if (callbackType == AddJavaResourcesWithBothApisCallback::class.java) {
      Truth.assertThat(result.didWorkTasks).contains(":app:writeDebugJavaResourcesWithArtifacts")
      builtProject.executor.run(":app:assembleDebug")
      builtProject.androidApplication(":app").assertApk(ApkSelector.DEBUG) {
        zipEntry("foo.txt").hasCompressionMethod(0)
        zipEntry("bar.txt").hasCompressionMethod(0)
      }
    }
  }
}

abstract class AddJavaResourcesTestWriter : DefaultTask() {

  @get:Input abstract val resName: Property<String>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun execute() {
    outputDir.get().asFile.mkdirs()
    outputDir.file(resName).get().asFile.writeText("foo")
  }

  companion object {
    fun createTask(project: Project, taskName: String = "writeDebugJavaResources"): TaskProvider<AddJavaResourcesTestWriter> =
      project.tasks.register(taskName, AddJavaResourcesTestWriter::class.java).also { it.configure { task -> task.resName.set("foo.txt") } }
  }
}

class AddJavaResourcesWithScopedApiCallback : ApplicationComponentCallback {

  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
      variant.artifacts
        .forScope(ScopedArtifacts.Scope.PROJECT)
        .use(AddJavaResourcesTestWriter.createTask(project))
        .toAppend(ScopedArtifact.JAVA_RES, AddJavaResourcesTestWriter::outputDir)
    }
  }
}

class AddJavaResourcesWithSourceApiCallback : ApplicationComponentCallback {

  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
      variant.sources.resources?.addGeneratedSourceDirectory(
        AddJavaResourcesTestWriter.createTask(project),
        AddJavaResourcesTestWriter::outputDir,
      )
    }
  }
}

class AddJavaResourcesWithBothApisCallback : ApplicationComponentCallback {

  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
      variant.sources.resources?.addGeneratedSourceDirectory(
        AddJavaResourcesTestWriter.createTask(project),
        AddJavaResourcesTestWriter::outputDir,
      )

      variant.artifacts
        .forScope(ScopedArtifacts.Scope.PROJECT)
        .use(
          AddJavaResourcesTestWriter.createTask(project, "writeDebugJavaResourcesWithArtifacts").also {
            it.configure { task -> task.resName.set("bar.txt") }
          }
        )
        .toAppend(ScopedArtifact.JAVA_RES, AddJavaResourcesTestWriter::outputDir)
    }
  }
}
