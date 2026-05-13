/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.build.gradle.integration.attribution

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.options.StringOption
import com.android.build.gradle.tasks.MergeResources
import com.android.buildanalyzer.common.AndroidGradlePluginAttributionData
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

abstract class SampleTask : DefaultTask() {
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction fun run() {}
}

class BuildAttributionDataTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @get:Rule
  var rule =
    GradleRule.configure().from {
      androidApplication {
        android { HelloWorldAndroid.setupJava(files) }
        pluginCallbacks += Callback::class.java
      }
    }

  class Callback : GenericCallback {
    override fun handleProject(project: Project) {
      val sample1 =
        project.tasks.register("sample1", SampleTask::class.java) {
          it.outputDirectory.set(project.layout.buildDirectory.dir("outputs/shared_output"))
        }
      val sample2 =
        project.tasks.register("sample2", SampleTask::class.java) {
          it.outputDirectory.set(project.layout.buildDirectory.dir("outputs/shared_output"))
          it.dependsOn += sample1
        }
      project.tasks.withType(MergeResources::class.java).configureEach {
        it.dependsOn += sample1
        it.dependsOn += sample2
      }
    }
  }

  @Test
  fun testBuildAttributionReport() {
    val project = rule.build

    val attributionFileLocation = temporaryFolder.newFolder()

    project.executor
      .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
      .with(StringOption.IDE_ATTRIBUTION_FILE_LOCATION, attributionFileLocation.absolutePath)
      .run("mergeDebugResources")

    val originalAttributionData = AndroidGradlePluginAttributionData.load(attributionFileLocation)!!

    assertThat(originalAttributionData.taskNameToTaskInfoMap).isNotEmpty()
    assertThat(originalAttributionData.tasksSharingOutput).isNotEmpty()

    // delete the report and re-run

    FileUtils.deleteDirectoryContents(attributionFileLocation)

    project.executor
      .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
      .with(StringOption.IDE_ATTRIBUTION_FILE_LOCATION, attributionFileLocation.absolutePath)
      .run("mergeDebugResources")

    val newAttributionData = AndroidGradlePluginAttributionData.load(attributionFileLocation)!!

    assertThat(newAttributionData.taskNameToTaskInfoMap).containsExactlyEntriesIn(originalAttributionData.taskNameToTaskInfoMap)
    assertThat(newAttributionData.tasksSharingOutput).containsExactlyEntriesIn(originalAttributionData.tasksSharingOutput)
  }
}
