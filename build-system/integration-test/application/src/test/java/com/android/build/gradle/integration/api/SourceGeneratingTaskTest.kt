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
package com.android.build.gradle.integration.api

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.integration.common.utils.getVariantByName
import com.google.common.truth.Truth
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class SourceGeneratingTaskTest {

  companion object {
    fun generateKotlinFunction(packageName: String) =
      """
                package com.foo.bar.app
                class MyClass {
                    fun someFunctionUsingGeneratedAPIs() {
                        $packageName.MyClass().someFunctionUsingGeneratedAPIs()
                    }
                }
            """
        .trimIndent()
  }

  @get:Rule
  val project =
    GradleRule.from {
      androidApplication {
        applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        files { add("src/main/kotlin/com/foo/bar/app/MyClass.kt", generateKotlinFunction("com.first")) }
        pluginCallbacks += MyAppCallback::class.java
      }
    }

  class MyAppCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants { variant ->
        val taskProvider =
          project.tasks.register("generate${variant.name}Sources", SourceGeneratingTask::class.java) { it.packageName.set("com.first") }
        variant.sources.kotlin!!.addGeneratedSourceDirectory(taskProvider, SourceGeneratingTask::outputDir)
      }
    }
  }

  /** Regression test for b/446220448. */
  @Test
  fun `test generated Kotlin sources are included in the model`() {
    val gradleBuild = project.build
    val androidProject = gradleBuild.modelBuilder.fetchModels().container.getProject().androidProject!!
    val debugArtifact = androidProject.getVariantByName("debug").mainArtifact
    val generatedDir = gradleBuild.androidApplication().generatedDir.toFile()
    assertThat(debugArtifact.generatedSourceFolders)
      .containsExactly(generatedDir.resolve("kotlin/generatedebugSources"), generatedDir.resolve("ap_generated_sources/debug/out"))
  }

  /** Regression test for b/446189433. */
  @Test
  fun ensureSuccessfulCompilation() {
    val gradleBuild = project.build
    val result = gradleBuild.executor.run("assembleDebug")
    Truth.assertThat(result.failedTasks).isEmpty()
  }
}

private abstract class SourceGeneratingTask : DefaultTask() {
  @get:Input abstract val packageName: Property<String>
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val outputFolder = File(outputDir.get().asFile, packageName.get())
    outputFolder.mkdirs()
    File(outputFolder, "someFile.kt")
      .writeText(
        """
                package ${packageName.get()}
                class MyClass {
                    fun someFunctionUsingGeneratedAPIs() {
                        println("Hello world !")
                    }
                }
            """
          .trimIndent()
      )
  }
}
