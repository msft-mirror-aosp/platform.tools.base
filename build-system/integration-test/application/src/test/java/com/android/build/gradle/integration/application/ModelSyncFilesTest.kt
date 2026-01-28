/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.builder.model.SyncIssue
import com.android.builder.model.v2.ide.Variant
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class ModelSyncFilesTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                namespace = "com.example.hello_world"
            }
          pluginCallbacks += ModelSyncFilesTestCallback::class.java
        }
    }

    @Test
    fun testApplicationIdNotSetByTask() {
        val (variant, _) = getAppVariant("release")
        assertThat(variant.mainArtifact.applicationId).isEqualTo("com.example.hello_world")
    }

    @Test
    fun testAppIdListModelWithCustomizedAppId() {
        val (variant, syncIssues) = getAppVariant("debug")
        assertThat(syncIssues.map { it.message }).containsExactly(APPLICATION_ID_FROM_TASK_UNSUPPORTED)
        assertThat(variant.mainArtifact.applicationId).isEqualTo("")
    }

    private fun getAppVariant(variantName: String): Pair<Variant, Collection<com.android.builder.model.v2.ide.SyncIssue>> {
        val projectModel = rule.build.modelBuilder
                .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
                .fetchModels()
                .container
                .getProject(":app")
        val variant = (projectModel
                .androidProject
                ?.variants
                ?.first { variant -> variant.name == variantName }
                ?: throw RuntimeException("could not find $variantName AndroidProject model"))
        val syncIssues = projectModel.issues?.syncIssues ?: emptySet()
        return variant to syncIssues
    }

    companion object {
        private val APPLICATION_ID_FROM_TASK_UNSUPPORTED = """
            Failed to read applicationId for debug.
            Setting the application ID to the output of a task in the variant api is not supported
            """.trimIndent()
    }
}

abstract class ModelSyncFilesApplicationIdProducerTask: DefaultTask() {

  @get:OutputFile
  abstract val outputFile: RegularFileProperty

  @TaskAction
  fun taskAction() {
    outputFile.get().asFile.writeText("set.from.task.$name")
  }
}

class ModelSyncFilesTestCallback: ApplicationComponentCallback {

  override fun handleExtension(
    project: Project,
    androidComponents: ApplicationAndroidComponentsExtension,
  ) {
      // b/176931684
      // disable androidTest for all variants as it forces the applicationId resolution
      androidComponents.beforeVariants { variantBuilder ->
         variantBuilder.enableAndroidTest = false
      }
      // only register the applicationId in the debug variant, leave release unchanged.
      androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        val appIdProducer = project.tasks.register(variant.name + "AppIdProducerTask", ModelSyncFilesApplicationIdProducerTask::class.java) { task ->
          val outputDir = File(project.layout.buildDirectory.asFile.get(), task.name)
          outputDir.mkdirs()
          task.outputFile.set(File(outputDir, "appId.txt"))

      }
      variant.applicationId.set(appIdProducer.flatMap { task ->
          task.outputFile.map { it.asFile.readText() }
      })
    }
  }
}
