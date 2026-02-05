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

package com.android.build.gradle.integration.lint

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.Component
import com.android.build.api.variant.HasAndroidTest
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.testutils.truth.PathSubject.assertThat
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

/** Integration test running lint on generated resources */
class LintGeneratedResourcesTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android {
          namespace = "com.example.app"
          lint {
            abortOnError = false
            textOutput = projectDotFile("lint-results.txt")
            checkGeneratedSources = true
          }
        }
        files {
          add(
            "res-template.xml",
            """<resources>
    <!-- xml comment -->
    <string
        name="foo">Foo</string>
</resources>""",
          )
        }
        pluginCallbacks += ConfigureGeneratedRes::class.java
      }
    }

  /** Test that changes to generated resources cause the lint tasks to re-run as expected. */
  @Test
  fun testNotUpToDate() {
    val executor = rule.build.executor
    executor.run("clean", ":app:lintDebug").apply {
      assertTask(":app:lintReportDebug").didWork()
      assertTask(":app:lintAnalyzeDebug").didWork()
    }
    val appProject = rule.build.androidApplication()
    val lintReport = appProject.resolve("lint-results.txt")
    assertThat(lintReport).exists()
    assertThat(lintReport).doesNotContain("generated.xml:3: Error: Found byte-order-mark in the middle of a file [ByteOrderMark]")

    // Add a byteOrderMark to the generated resources
    val byteOrderMark = "\ufeff"
    TestFileUtils.searchAndReplace(appProject.resolve("res-template.xml").toFile(), "xml comment", byteOrderMark)

    executor.run(":app:lintDebug").apply {
      assertTask(":app:lintReportDebug").didWork()
      assertTask(":app:lintAnalyzeDebug").didWork()
    }
    assertThat(lintReport).exists()
    assertThat(lintReport).contains("generated.xml:3: Error: Found byte-order-mark in the middle of a file [ByteOrderMark]")
  }

  /** Regression test for b/337776938 */
  @Test
  fun testDependencyOnGeneratedResForAndroidTest() {
    rule.build.executor.run("clean", ":app:lintDebug").assertTask(":app:generateResForDebugAndroidTest").didWork()
  }
}

abstract class GenerateRes : DefaultTask() {
  @get:InputFile abstract val templateFile: RegularFileProperty

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun taskAction() {
    val outDir = outputDir.get().asFile
    val valuesDir = File(outDir, "values")
    valuesDir.mkdirs()
    val outFile = File(valuesDir, "generated.xml")
    val content = templateFile.get().asFile.readText()
    outFile.writeText(
      """<?xml version="1.0" encoding="utf-8"?>
$content"""
    )
  }
}

class ConfigureGeneratedRes : GenericCallback {
  override fun handleProject(project: Project) {
    val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

    androidComponents.onVariants { variant ->
      configureComponent(project, variant)
      (variant as? HasAndroidTest)?.androidTest?.let { configureComponent(project, it) }
    }
  }

  private fun configureComponent(project: Project, component: Component) {
    val template = project.layout.projectDirectory.file("res-template.xml")
    val taskName = "generateResFor${component.name.replaceFirstChar { it.uppercase() }}"
    val task = project.tasks.register(taskName, GenerateRes::class.java) { t -> t.templateFile.set(template) }
    component.sources.res?.addGeneratedSourceDirectory(task, GenerateRes::outputDir)
  }
}
