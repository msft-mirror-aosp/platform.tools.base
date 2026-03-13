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

package com.android.build.gradle.integration.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class LintOutputArtifactTest {

  @get:Rule
  val rule =
    GradleRule.configure().fromProject(BasicSpec()) {
      androidApplication(":app") { pluginCallbacks += LintXmlTaskCallback::class.java }
      gradleProperties { add("android.experimental.lint.lintReportAggregation", "true") }
    }

  @Test
  fun listLintXml() {
    val app = rule.build.androidApplication(":app")
    rule.build.executor.run("debugLintXmlTask")
    val outFile = app.resolve("build/debugLintXmlTask/out.txt")
    assertThat(outFile).exists()
    Truth.assertThat(outFile.toFile().readText()).contains("lint-results-debug.xml")
  }

  @Test
  fun listAggregatedLintXml() {
    val app = rule.build.androidApplication(":app")
    rule.build.executor.run("debugAggregatedLintXmlTask")
    val outFile = app.resolve("build/debugAggregatedLintXmlTask/out.txt")
    assertThat(outFile).exists()
    Truth.assertThat(outFile.toFile().readText()).contains("aggregated-lint-results-debug.xml")
  }
}

class LintXmlTaskCallback : ApplicationComponentCallback {
  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().all()) { variant ->
      val name = variant.name
      project.tasks.register(name + "LintXmlTask", LintXmlTask::class.java) { task ->
        task.lintXml.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.LINT_XML_REPORT))
        val outputDir = project.layout.buildDirectory
        task.outputFile.set(outputDir.file(task.name + "/out.txt"))
      }

      project.tasks.register(name + "AggregatedLintXmlTask", LintXmlTask::class.java) { task ->
        task.lintXml.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.AGGREGATED_LINT_XML_REPORT))
        val outputDir = project.layout.buildDirectory
        task.outputFile.set(outputDir.file(task.name + "/out.txt"))
      }
    }
  }
}

abstract class LintXmlTask : DefaultTask() {
  @get:OutputFile abstract val outputFile: RegularFileProperty
  @get:Optional @get:InputFile abstract val lintXml: RegularFileProperty

  @TaskAction
  fun writeLintReportFileName() {
    outputFile.get().asFile.parentFile.mkdirs()
    outputFile.get().asFile.writer().use { fw ->
      if (lintXml.isPresent) {
        fw.write(lintXml.get().asFile.name)
      }
    }
  }
}
