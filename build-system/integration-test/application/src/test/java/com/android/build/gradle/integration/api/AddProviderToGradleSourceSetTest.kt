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

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.options.BooleanOption
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

class AddProviderToGradleSourceSetTest {
  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android {
          namespace = "com.example.api.java_res"
          defaultConfig.applicationId = "com.example.api.java_res"

          pluginCallbacks += AddJavaResourcesWithSourceSetCallback::class.java
        }
      }
    }

  @Test
  fun disallowProvidersInSourceSet() {
    val result = rule.build.executor.expectFailure().run("tasks")
    result.assertErrorContains("You cannot add Provider instances to the Android SourceSet API.")
  }

  @Test
  fun allowProvidersInSourceSet() {
    val builtProject = rule.build
    builtProject.executor.with(BooleanOption.DISALLOW_PROVIDER_IN_ANDROID_SOURCE_SET, false).run("tasks")
  }
}

abstract class AddJavaResourcesTestWriterForSourceSet : DefaultTask() {

  @get:Input abstract val resName: Property<String>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun execute() {
    outputDir.get().asFile.mkdirs()
    outputDir.file(resName).get().asFile.writeText("foo")
  }

  companion object {
    fun createTask(project: Project, taskName: String = "writeDebugJavaResources"): TaskProvider<AddJavaResourcesTestWriterForSourceSet> =
      project.tasks.register(taskName, AddJavaResourcesTestWriterForSourceSet::class.java).also {
        it.configure { task -> task.resName.set("foo.txt") }
      }
  }
}

class AddJavaResourcesWithSourceSetCallback : GenericCallback {

  override fun handleProject(project: Project) {
    val genTask = AddJavaResourcesTestWriterForSourceSet.createTask(project)
    project.extensions.getByType(ApplicationExtension::class.java).sourceSets.getByName("main").resources.srcDir(genTask)
  }
}
