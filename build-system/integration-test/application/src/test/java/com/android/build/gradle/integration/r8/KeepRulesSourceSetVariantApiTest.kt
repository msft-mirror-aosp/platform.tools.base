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

package com.android.build.gradle.integration.r8

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class KeepRulesSourceSetVariantApiTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android {
          defaultConfig.minSdk = 24
          buildTypes { named("release") { it.isMinifyEnabled = true } }
        }
        files {
          add(
            "src/main/java/com/example/app/ClassToKeep.kt",
            // language=kotlin
            """
            package com.example.app
            class ClassToKeep {
                fun method() {}
            }
            """
              .trimIndent(),
          )
          add(
            "src/main/java/com/example/app/ClassToShrink.kt",
            // language=kotlin
            """
            package com.example.app
            class ClassToShrink {
                fun method() {}
            }
            """
              .trimIndent(),
          )
        }
      }
    }

  @Test
  fun `test static keepRules files are included`() {
    val build = rule.build { androidApplication { pluginCallbacks += KeepRulesStaticCallback::class.java } }
    build.executor.run(":app:assembleRelease")
    build.androidApplication().assertApk(ApkSelector.RELEASE) { classes().subPackage("com/example/app").containsExactly("ClassToKeep") }
  }

  @Test
  fun `test generated keepRules files are included`() {
    val build = rule.build { androidApplication { pluginCallbacks += GeneratedKeepRulesCallback::class.java } }
    build.executor.run(":app:assembleRelease")
    build.androidApplication().assertApk(ApkSelector.RELEASE) { classes().subPackage("com/example/app").containsExactly("ClassToKeep") }
  }
}

class KeepRulesStaticCallback : GenericCallback {

  override fun handleProject(project: Project) {
    val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
    androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant: Variant ->
      val folder = File(project.projectDir, "app/src/myStaticKeep")
      folder.mkdirs()
      val file = File(folder, "static.keep")
      file.writeText("-keep class com.example.app.ClassToKeep { *; }")

      variant.sources.keepRules?.addStaticSourceDirectory(folder.absolutePath)
    }
  }
}

class GeneratedKeepRulesCallback : GenericCallback {

  override fun handleProject(project: Project) {
    val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
    androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant: Variant ->
      val generateKeepRules =
        project.tasks.register("generateKeepRules${variant.name.replaceFirstChar { it.uppercase() }}", GenerateKeepRulesTask::class.java) {
          task: GenerateKeepRulesTask ->
          task.outputDir.set(project.layout.buildDirectory.dir("myGeneratedKeepRules"))
        }
      variant.sources.keepRules?.addGeneratedSourceDirectory(generateKeepRules, GenerateKeepRulesTask::outputDir)
    }
  }
}

abstract class GenerateKeepRulesTask : DefaultTask() {

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun action() {
    val outputFile = File(outputDir.get().asFile, "generated.keep")
    outputFile.writeText("-keep class com.example.app.ClassToKeep { *; }")
  }
}
