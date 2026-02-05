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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.plugins.KotlinMultiplatformCallback
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformGeneratedSourcesTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidKotlinMultiplatformLibrary(":library", createMinimumProject = false) {
        android {
          namespace = "com.mylibrary.foo"
          compileSdk = DEFAULT_COMPILE_SDK_VERSION
        }

        pluginCallbacks += Callback::class.java
      }
    }

  class Callback : KotlinMultiplatformCallback {
    override fun handleExtension(project: Project, extension: KotlinMultiplatformExtension) {
      val generateJavaRes = project.tasks.register("generateJavaRes", KMP_GenerateJavaRes::class.java)
      generateJavaRes.configure { it.outputDir.set(project.layout.buildDirectory.dir("generated/javaRes")) }

      extension.apply { sourceSets.androidMain.configure { it.resources.srcDir(generateJavaRes.map { it.outputDir }) } }
    }
  }

  @Test
  fun testGeneratedKotlinSources() {
    val build = rule.build
    build.executor
      .withFailOnWarning(false) // b/455891987
      .run(":library:assembleAndroidMain")

    build.kotlinMultiplatformLibrary(":library").assertAar(AarSelector.NO_BUILD_TYPE) {
      javaResources().resourceAsText("res.txt").isEqualTo("foo")
    }
  }
}

abstract class KMP_GenerateJavaRes : DefaultTask() {
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun taskAction() {
    val d = outputDir.get().file("res.txt").asFile
    d.parentFile.mkdirs()
    d.writeText("foo")
  }
}
