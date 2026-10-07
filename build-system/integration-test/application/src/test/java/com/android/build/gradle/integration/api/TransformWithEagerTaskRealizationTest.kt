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

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import com.google.common.truth.Truth.assertThat
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

/**
 * Regression tests for b/532206373.
 *
 * Variant API transforms are registered before AGP registers its own producers. If a build configures tasks eagerly (`tasks.all {}`, or a
 * plugin calling `TaskProvider.get()`), the transform tasks are configured before the producer chain is complete. Their output locations
 * must still reflect the complete chain: every producer writes its own file, and the final one uses the AGP naming context.
 */
class TransformWithEagerTaskRealizationTest {

  @get:Rule
  val rule =
    GradleRule.configure().fromProject(BasicSpec()) {
      androidApplication(":app") { android { buildTypes { named("debug") { it.isMinifyEnabled = true } } } }
    }

  @Test
  fun twoMappingTransformsGetDistinctLocations() {
    val build = rule.build { androidApplication(":app") { pluginCallbacks += TwoMappingTransformsCallback::class.java } }

    build.executor.run(":app:appendSecondMappingSection")

    val mapping = build.androidApplication(":app").resolve("build/outputs/mapping/debug/mapping.txt").toFile().readLines()
    assertThat(mapping.any { it.startsWith("# compiler: R8") }).isTrue()
    assertThat(mapping).containsAtLeast("# $SECTION_PREFIX First", "# $SECTION_PREFIX Second").inOrder()
  }

  @Test
  fun bundleTransformKeepsBundleName() {
    val build = rule.build { androidApplication(":app") { pluginCallbacks += BundleTransformCallback::class.java } }

    build.executor.run(":app:bundleDebug")

    val bundles = build.androidApplication(":app").resolve("build/outputs/bundle/debug").toFile().listFiles()!!.map { it.name }
    assertThat(bundles).hasSize(1)
    assertThat(bundles.single()).endsWith("-debug.aab")
  }

  class TwoMappingTransformsCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        listOf("First", "Second").forEach { name ->
          val task =
            project.tasks.register("append${name}MappingSection", AppendSectionTask::class.java) {
              it.section.set("# $SECTION_PREFIX $name")
            }
          variant.artifacts
            .use(task)
            .wiredWithFiles(AppendSectionTask::inputFile, AppendSectionTask::outputFile)
            .toTransform(SingleArtifact.OBFUSCATION_MAPPING_FILE)
        }
      }
      // Configure every task as soon as it is registered.
      project.tasks.all {}
    }
  }

  class BundleTransformCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        val task = project.tasks.register("copyBundle", AppendSectionTask::class.java) { it.section.set("") }
        variant.artifacts
          .use(task)
          .wiredWithFiles(AppendSectionTask::inputFile, AppendSectionTask::outputFile)
          .toTransform(SingleArtifact.BUNDLE)
      }
      // Configure every task as soon as it is registered.
      project.tasks.all {}
    }
  }

  companion object {
    const val SECTION_PREFIX = "Third-party mapping section"
  }
}

/** Copies its input to its output and appends [section]. Fails if input and output are the same file. */
abstract class AppendSectionTask : DefaultTask() {
  @get:InputFile abstract val inputFile: RegularFileProperty

  @get:OutputFile abstract val outputFile: RegularFileProperty

  @get:Input abstract val section: Property<String>

  @TaskAction
  fun run() {
    val inFile = inputFile.get().asFile
    val outFile = outputFile.get().asFile
    if (inFile.canonicalPath == outFile.canonicalPath) {
      throw GradleException("Transform input and output are the same file: $inFile")
    }
    outFile.writeBytes(inFile.readBytes())
    if (section.get().isNotEmpty()) {
      outFile.appendText("\n${section.get()}\n")
    }
  }
}
