/*
 * Copyright (C) 2023 The Android Open Source Project
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
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.google.common.truth.Truth
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class LifecycleTasksTest {

  @get:Rule val rule = GradleRule.from { androidApplication { pluginCallbacks += MyAppCallback::class.java } }

  class MyAppCallback : ApplicationComponentCallback {

    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      val customPreBuildProvider =
        project.tasks.register("customPreBuild", LifecycleDependencyCustomTask::class.java) { it.outputDirectory.set(File("build/output")) }

      val customPreInstallationProvider =
        project.tasks.register("customPreInstallationTask", LifecycleDependencyCustomTask::class.java) {
          it.outputDirectory.set(File("build/output2"))
        }

      androidComponents.onVariants { variant ->
        variant.lifecycleTasks.registerPreBuild(customPreBuildProvider)
        variant.lifecycleTasks.registerPreInstallation(customPreInstallationProvider)
      }
    }
  }

  @Test
  fun testAddingPreBuildDependent() {
    val buildResult = rule.build.executor.run("preDebugBuild")
    Truth.assertThat(buildResult.didWorkTasks).contains(":app:customPreBuild")
    ScannerSubject.assertThat(buildResult.stdout).contains("customPreBuild ran !")
  }

  @Test
  fun testAddingPreInstallationDependenct() {
    val buildResult = rule.build.executor.withArgument("--dry-run").run("installDebug")
    Truth.assertThat(buildResult.stdout.findAll(":app:customPreInstallationTask SKIPPED").findFirst().isPresent).isTrue()
  }
}

abstract class LifecycleDependencyCustomTask : DefaultTask() {
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun run() {
    print("$name ran !")
  }
}
