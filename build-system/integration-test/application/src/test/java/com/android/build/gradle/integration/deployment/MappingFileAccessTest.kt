/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.build.gradle.integration.deployment

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.internal.api.InstallableVariantImpl
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class MappingFileAccessTest {

  @get:Rule
  val rule =
    GradleRule.fromProject(BasicSpec()) {
      androidApplication(":app") {
        android { buildTypes { named("release") { it.isMinifyEnabled = true } } }

        pluginCallbacks += AppVariantCallback::class.java
      }
      gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
    }

  class AppVariantCallback : LegacyApplicationCallback {
    override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
      extension.applicationVariants.all { variant ->
        if (variant.buildType.name == "release") {
          variant as InstallableVariantImpl
          val mappingFile = variant.getFinalArtifact(com.android.build.api.artifact.SingleArtifact.OBFUSCATION_MAPPING_FILE)
          println("Creating mapping task for " + variant.name)
          val mappingTask =
            project.tasks.register("hello" + variant.name.capitalize(), MappingFileUserTask::class.java) { it.mappingFile.set(mappingFile) }
          variant.register(mappingTask.get())
        } else {
          println("Not creating mapping task for " + variant.name)
        }
      }
    }
  }

  @Test
  fun assembleTest() {
    val buildResult = rule.build.executor.run("clean", "assemble")
    assertThat(buildResult.tasks).contains(":app:helloRelease")
    assertThat(buildResult.tasks).doesNotContain(":app:helloDebug")

    buildResult.stdout.use { ScannerSubject.assertThat(it).contains("helloRelease task mapping file exists is true") }
  }

  @Test
  fun bundleTest() {
    val buildResult = rule.build.executor.run("clean", "bundle")
    assertThat(buildResult.tasks).contains(":app:helloRelease")
    assertThat(buildResult.tasks).doesNotContain(":app:helloDebug")

    buildResult.stdout.use { ScannerSubject.assertThat(it).contains("helloRelease task mapping file exists is true") }
  }

  @Test
  fun useMappingFileSpecificApi() {
    val build = rule.build { androidApplication(":app") { pluginCallbacks += MappingFileSpecificApiCallback::class.java } }

    build.executor.run("mappingFileRelease").apply {
      assertTask(":app:mappingFileRelease").didWork()
      assertTask(":app:minifyReleaseWithR8").didWork()
    }
  }

  class MappingFileSpecificApiCallback : LegacyApplicationCallback {
    override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
      extension.applicationVariants.all { variant ->
        if (variant.buildType.isMinifyEnabled) {
          project.tasks.register("mappingFile" + variant.name.capitalize(), MappingFileUserTask::class.java) {
            it.mappingFile.set(variant.mappingFileProvider)
          }
        }
      }
    }
  }
}

abstract class MappingFileUserTask : DefaultTask() {

  @get:InputFiles abstract val mappingFile: Property<FileCollection>

  @TaskAction
  fun taskAction() {
    val file = mappingFile.get().singleFile
    println("MappingFileTask $file")
    println("$name task mapping file exists is ${file.exists()}")
  }
}
