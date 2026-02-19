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

package com.android.build.gradle.integration.kotlin

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.junit.Rule
import org.junit.Test

/**
 * Test to assert that a kotlin sources generated via the new variant api can be consumed using the legacy getSourceFolder() API, which is
 * still in use by kotlin-android plugin
 */
class GeneratedKotlinSourcesAccessViaOldVariant {
  @get:Rule
  val project =
    GradleRule.from {
      gradleProperties {
        add(BooleanOption.USE_NEW_DSL, false)
        add(BooleanOption.BUILT_IN_KOTLIN, false)
      }

      androidApplication {
        android {
          applyPlugin(PluginType.KOTLIN_ANDROID)
          kotlin { compilerOptions.jvmTarget.set(JvmTarget.JVM_11) }
        }
        files {
          add(
            "src/main/kotlin/com/foo/bar/app/MyClass.kt",
            """
            package com.foo.bar.app

            import com.kotlingen.MyKotlinClass

            class MyClass {
                fun someFunctionUsingGeneratedAPIs() {
                    MyKotlinClass().someFunctionUsingGeneratedAPIs()
                }
            }
            """
              .trimIndent(),
          )
        }
        pluginCallbacks += MyAppCallback::class.java
      }
    }

  @Test
  fun testGeneratedFilesExist() {
    val gradleBuild = project.build
    val result = gradleBuild.executor.withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.OFF).run("assembleDebug")
    Truth.assertThat(result.failedTasks).isEmpty()
    gradleBuild.androidApplication(":app").assertApk(ApkSelector.DEBUG) {
      classes().containsAtLeast("com/foo/bar/app/MyClass", "com/kotlingen/MyKotlinClass")
    }
  }

  class MyAppCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants { variant ->
        val kotlinGenTaskProvider =
          project.tasks.register("generate${variant.name}KotlinSources", SourceGeneratingTask::class.java) { task ->
            task.packageName.set("com.kotlingen")
            task.sourceFiles.set(variant.sources.kotlin!!.static)
            task.sourceFiles.addAll(variant.sources.java!!.static)
          }
        variant.sources.kotlin!!.addGeneratedSourceDirectory(kotlinGenTaskProvider, SourceGeneratingTask::outputDir)
      }
    }
  }
}

abstract class SourceGeneratingTask : DefaultTask() {
  @get:Input abstract val packageName: Property<String>
  @get:OutputDirectory abstract val outputDir: DirectoryProperty
  @get:InputFiles abstract val sourceFiles: ListProperty<Directory>

  @TaskAction
  fun generate() {

    val outputFolder = File(outputDir.get().asFile, packageName.get().replace('.', File.separatorChar))
    outputFolder.mkdirs()
    File(outputFolder, "MyKotlinClass.kt")
      .writeText(
        """
                package ${packageName.get()}
                class MyKotlinClass {
                    fun someFunctionUsingGeneratedAPIs() {
                        System.err.println("Hello world !")
                    }
                }
            """
          .trimIndent()
      )
  }
}
