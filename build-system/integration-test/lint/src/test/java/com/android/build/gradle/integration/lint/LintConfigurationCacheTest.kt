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

package com.android.build.gradle.integration.lint

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyLibraryCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.android.build.gradle.options.BooleanOption
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LintConfigurationCacheTest(private val mode: Mode) {

  enum class Mode {
    NEW_DSL,
    OLD_DSL,
  }

  companion object {
    @JvmStatic @Parameterized.Parameters(name = "{0}") fun data() = listOf(Mode.NEW_DSL, Mode.OLD_DSL)
  }

  @get:Rule
  val rule =
    GradleRule.from {
      if (mode == Mode.OLD_DSL) {
        gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
      }
      androidLibrary {
        android { namespace = "com.example.lib" }
        pluginCallbacks +=
          if (mode == Mode.NEW_DSL) {
            MyCallback::class.java
          } else {
            MyOldDslCallback::class.java
          }
      }
    }

  /** Regression test for b/285320724. */
  @Test
  fun testLintConfigurationCache() {
    rule.build.executor.run("generateDebugLintModel")
    rule.build.executor.run("generateDebugLintModel").assertConfigurationCacheHit()
  }
}

abstract class GenerateSrcs : DefaultTask() {
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun run() {
    val outputFile = outputDir.file("Foo.java").get().asFile
    outputFile.parentFile.mkdirs()
    outputFile.writeText("public class Foo {}")
  }
}

class MyCallback : LibraryComponentCallback {
  override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().withName("debug")) { variant ->
      val generateSrcs =
        project.tasks.register("generateSrcs", GenerateSrcs::class.java) {
          it.outputDir.set(project.layout.buildDirectory.dir("generated/source/kapt/debug"))
        }
      variant.sources.java?.addGeneratedSourceDirectory(generateSrcs, GenerateSrcs::outputDir)
    }
  }
}

class MyOldDslCallback : LegacyLibraryCallback {
  override fun handleExtension(project: Project, extension: LibraryExtension) {
    extension.libraryVariants.all { variant ->
      if (variant.name == "debug") {
        val cTree: ConfigurableFileTree = project.fileTree(File(project.layout.buildDirectory.asFile.get(), "generated/source/kapt/debug"))
        cTree.builtBy(project.tasks.findByName("generateSrcs"))
        cTree.include("**/*.java")
        variant.registerExternalAptJavaOutput(cTree)
      }
    }

    project.tasks.register("generateSrcs") { task ->
      val myOutputDir = File(project.layout.buildDirectory.asFile.get(), "generated/source/kapt/debug")
      task.doFirst {
        myOutputDir.deleteRecursively()
        myOutputDir.mkdirs()
        File(myOutputDir, "Foo.java").writeText("public class Foo {}")
      }
    }
  }
}
