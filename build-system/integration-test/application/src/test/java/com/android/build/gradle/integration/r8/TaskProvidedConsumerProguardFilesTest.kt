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

package com.android.build.gradle.integration.r8

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class TaskProvidedConsumerProguardFilesTest {
  @get:Rule
  val rule =
    GradleRule.configure().from {
      androidLibrary {
        android {}
        pluginCallbacks += MyLibraryCallback::class.java
      }
    }

  class MyLibraryCallback : LibraryComponentCallback {
    override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
      androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        val producerTask =
          project.tasks.register("${variant.name}ProducerTask", ConsumerProguardFileProduceTask::class.java) {
            println("Producer Task configured.")
            it.outputFile.set(project.layout.buildDirectory.file("intermediates/${variant.name}ProducerTask"))
          }
        println("Adding producer task to consumer proguard files")
        variant.consumerProguardFiles.add(producerTask.flatMap { it.outputFile })
      }
    }
  }

  @Test
  fun testTaskBasedProduction() {
    val project = rule.build
    project.executor.run("assembleDebug")

    // check the resulting aar.
    project.androidLibrary(":lib").assertAar(AarSelector.DEBUG) { textFile("proguard.txt").isEqualTo("some proguard statements") }
  }
}

/** Task to generate a consumer proguard file */
abstract class ConsumerProguardFileProduceTask : DefaultTask() {

  @get:OutputFile abstract val outputFile: RegularFileProperty

  @TaskAction
  fun generate() {
    println("ConsumerProguardFileProduceTask called !")
    outputFile.get().asFile.writeText("some proguard statements")
  }
}
