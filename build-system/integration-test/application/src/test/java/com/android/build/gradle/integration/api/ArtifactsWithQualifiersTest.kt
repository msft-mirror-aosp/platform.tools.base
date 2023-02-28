/*
 * Copyright (C) 2026 The Android Open Source Project
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

import com.android.build.api.artifact.Artifact
import com.android.build.api.artifact.ArtifactKind
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.google.common.truth.Truth
import java.io.FileWriter
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.junit.Rule
import org.junit.Test

class ArtifactsWithQualifiersTest {
  @get:Rule
  val rule =
    GradleRule.configure().from { androidApplication(":app") { pluginCallbacks += ArtifactsWithAttributesTestCallback::class.java } }

  @Test
  fun newBuildApp() {
    var result = rule.build.executor.run("clean", "debugSingleTargetConsumer")
    Truth.assertThat(result.didWorkTasks)
      .containsExactly(
        ":app:debugProducerSuite0Target2",
        ":app:debugProducerSuite1Target2",
        ":app:debugProducerSuite2Target2",
        ":app:debugSingleTargetConsumer",
      )
    result = rule.build.executor.run("clean", "debugSingleSuiteConsumer")
    Truth.assertThat(result.didWorkTasks)
      .containsExactly(
        ":app:debugProducerSuite1Target0",
        ":app:debugProducerSuite1Target1",
        ":app:debugProducerSuite1Target2",
        ":app:debugSingleSuiteConsumer",
        ":app:clean",
      )
    result = rule.build.executor.run("clean", "debugAllConsumer")
    Truth.assertThat(result.didWorkTasks)
      .containsExactly(
        ":app:debugProducerSuite0Target0",
        ":app:debugProducerSuite0Target1",
        ":app:debugProducerSuite0Target2",
        ":app:debugProducerSuite1Target0",
        ":app:debugProducerSuite1Target1",
        ":app:debugProducerSuite1Target2",
        ":app:debugProducerSuite2Target0",
        ":app:debugProducerSuite2Target1",
        ":app:debugProducerSuite2Target2",
        ":app:debugAllConsumer",
        ":app:clean",
      )
  }
}

internal sealed class TestMultipleArtifactType<T : FileSystemLocation>(kind: ArtifactKind<T>, val qualifierKeys: List<String>? = null) :
  Artifact.Multiple<T>(kind, Category.INTERMEDIATES) {
  object TEST_SUITE_RESULT_FILE :
    TestMultipleArtifactType<RegularFile>(FILE, qualifierKeys = listOf("SUITE_ID", "TARGET_ID")), Appendable, WithQualifiers
}

abstract class ArtifactProducerTask : DefaultTask() {
  @get:OutputFile abstract val outputFile: RegularFileProperty

  @get:Input abstract val input: Property<String>

  @TaskAction
  fun taskAction() {
    val writer = FileWriter(outputFile.get().asFile)
    writer.write("some pb content for " + input.get())
    writer.close()
  }
}

abstract class ArtifactConsumerTask : DefaultTask() {
  @get:InputFiles abstract val inputFiles: ListProperty<RegularFile>

  @TaskAction
  fun taskAction() {
    inputFiles.get().forEach { println("Got $it") }
  }
}

class ArtifactsWithAttributesTestCallback : ApplicationComponentCallback {
  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().all()) { variant ->
      for (suiteNumber in 0 until 3) {
        for (targetNumber in 0 until 3) {
          val input = "device$targetNumber"
          val producer: TaskProvider<ArtifactProducerTask> =
            project.tasks.register(variant.name + "ProducerSuite${suiteNumber}Target$targetNumber", ArtifactProducerTask::class.java) {
              it.input.set(input)
            }
          variant.artifacts
            .use(producer)
            .wiredWith { it.outputFile }
            .toAppendTo(
              TestMultipleArtifactType.TEST_SUITE_RESULT_FILE,
              mapOf("SUITE_ID" to "suite$suiteNumber", "TARGET_ID" to "target$targetNumber"),
            )
        }
      }

      project.tasks.register(variant.name + "SingleTargetConsumer", ArtifactConsumerTask::class.java) { task ->
        val artifacts = variant.artifacts.getAllWithAttributes(TestMultipleArtifactType.TEST_SUITE_RESULT_FILE)
        artifacts.forEach {
          // it.attributes is Map<String, String>
          val attributes = it.qualifiers
          val deviceId = attributes["TARGET_ID"]
          if (deviceId == "target2") {
            task.inputFiles.add(it.artifact)
          }
        }
      }

      project.tasks.register(variant.name + "SingleSuiteConsumer", ArtifactConsumerTask::class.java) { task ->
        val artifacts = variant.artifacts.getAllWithAttributes(TestMultipleArtifactType.TEST_SUITE_RESULT_FILE)
        artifacts.forEach {
          // it.attributes is Map<String, String>
          val attributes = it.qualifiers
          val suiteId = attributes["SUITE_ID"]
          if (suiteId == "suite1") {
            task.inputFiles.add(it.artifact)
          }
        }
      }

      project.tasks.register(variant.name + "SingleConsumer", ArtifactConsumerTask::class.java) { task ->
        val artifacts = variant.artifacts.getAllWithAttributes(TestMultipleArtifactType.TEST_SUITE_RESULT_FILE)
        artifacts.forEach {
          // it.attributes is Map<String, String>
          val attributes = it.qualifiers
          val suiteId = attributes["SUITE_ID"]
          val targetId = attributes["TARGET_ID"]
          if (suiteId == "suite1" && targetId == "target2") {
            task.inputFiles.add(it.artifact)
          }
        }
      }

      project.tasks.register(variant.name + "AllConsumer", ArtifactConsumerTask::class.java) { task ->
        variant.artifacts.getAllWithAttributes(TestMultipleArtifactType.TEST_SUITE_RESULT_FILE).forEach { task.inputFiles.add(it.artifact) }
      }
    }
  }
}
