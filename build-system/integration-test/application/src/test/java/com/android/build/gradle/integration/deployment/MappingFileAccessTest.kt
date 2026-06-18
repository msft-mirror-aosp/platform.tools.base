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

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MappingFileAccessTest(private val useNewDsl: Boolean) {

  companion object {
    @Parameterized.Parameters(name = "useNewDsl={0}") @JvmStatic fun parameters() = listOf(true, false)
  }

  @get:Rule
  val rule =
    GradleRule.configure().fromProject(BasicSpec()) {
      gradleProperties { add(BooleanOption.USE_NEW_DSL, useNewDsl) }
      androidApplication(":app") {
        android { buildTypes { named("release") { it.isMinifyEnabled = true } } }

        if (useNewDsl) {
          pluginCallbacks += AppVariantCallback::class.java
        } else {
          pluginCallbacks += LegacyAppVariantCallback::class.java
        }
      }
    }

  class AppVariantCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants { variant ->
        if (variant.name == "release") {
          val mappingTask = project.tasks.register("hello" + variant.name.capitalize(), MappingFileUserTask::class.java)
          variant.artifacts.use(mappingTask).wiredWith(MappingFileUserTask::mappingFile).toListenTo(SingleArtifact.OBFUSCATION_MAPPING_FILE)

          project.tasks.matching { it.name == "assemble" + variant.name.capitalize() }.configureEach { it.dependsOn(mappingTask) }
          project.tasks.matching { it.name == "bundle" + variant.name.capitalize() }.configureEach { it.dependsOn(mappingTask) }
        } else {
          println("Not creating mapping task for " + variant.name)
        }
      }
    }
  }

  class LegacyAppVariantCallback : LegacyApplicationCallback {
    override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
      extension.applicationVariants.all { variant ->
        if (variant.name == "release") {
          val mappingTask = project.tasks.register("hello" + variant.name.capitalize(), MappingFileUserTask::class.java)
          mappingTask.configure {
            it.mappingFile.set(project.layout.file(variant.mappingFileProvider.map { it.single() }))
            it.dependsOn(variant.mappingFileProvider)
          }
          variant.assembleProvider.configure { it.dependsOn(mappingTask) }
          project.tasks.matching { it.name == "bundle" + variant.name.capitalize() }.configureEach { it.dependsOn(mappingTask) }
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
    val build =
      rule.build {
        androidApplication(":app") {
          if (useNewDsl) {
            pluginCallbacks += MappingFileSpecificApiCallback::class.java
          } else {
            pluginCallbacks += LegacyMappingFileSpecificApiCallback::class.java
          }
        }
      }

    build.executor.run("mappingFileRelease").apply {
      assertTask(":app:mappingFileRelease").didWork()
      assertTask(":app:minifyReleaseWithR8").didWork()
    }
  }

  class MappingFileSpecificApiCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants { variant ->
        if (variant.isMinifyEnabled) {
          project.tasks.register("mappingFile" + variant.name.capitalize(), MappingFileUserTask::class.java) {
            it.mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
          }
        }
      }
    }
  }

  class LegacyMappingFileSpecificApiCallback : LegacyApplicationCallback {
    override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
      extension.applicationVariants.all { variant ->
        if (variant.buildType.isMinifyEnabled) {
          project.tasks.register("mappingFile" + variant.name.capitalize(), MappingFileUserTask::class.java) {
            it.mappingFile.set(project.layout.file(variant.mappingFileProvider.map { it.single() }))
            it.dependsOn(variant.mappingFileProvider)
          }
        }
      }
    }
  }
}

abstract class MappingFileUserTask : DefaultTask() {

  @get:InputFile abstract val mappingFile: org.gradle.api.file.RegularFileProperty

  @TaskAction
  fun taskAction() {
    val file = mappingFile.get().asFile
    println("MappingFileTask $file")
    println("$name task mapping file exists is ${file.exists()}")
  }
}
