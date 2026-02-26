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

package com.android.build.gradle.integration.library

import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.ModelBuilderV2
import com.android.build.gradle.integration.common.fixture.ModelContainerV2
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.TruthHelper
import com.android.build.gradle.integration.common.utils.LibrarySourceProviderHelper
import com.android.builder.model.v2.ide.ProjectType
import java.io.File
import java.io.IOException
import org.junit.Rule
import org.junit.Test

class LibrarySourceModelTest {
  @get:Rule
  val rule =
    GradleRule.configure().from {
      androidLibrary {
        android {
          sourceSets.named("main") {
            it.manifest.srcFile("AndroidManifest.xml")

            it.java.directories.clear()
            it.java.directories += "src"

            it.kotlin.directories.clear()

            it.res.directories.clear()
            it.res.directories += "res"

            it.assets.directories.clear()
            it.assets.directories += "assets"

            it.resources.directories.clear()
            it.resources.directories += "src"

            it.aidl.directories.clear()
            it.aidl.directories += "src"

            it.renderscript.directories.clear()
            it.renderscript.directories += "src"

            it.aarKeepRules.directories.clear()
            it.aarKeepRules.directories += "src"
          }
        }
      }
    }

  private val executor: GradleTaskExecutor
    get() = rule.build.executor

  private val modelV2: ModelBuilderV2
    get() = rule.build.modelBuilder

  @Test
  @Throws(IOException::class)
  fun checkModelReflectsMigratedSourceProviders() {
    executor.run("clean", "assembleDebug")

    val container: ModelContainerV2 = modelV2.fetchModels().container
    val modelInfo = container.getProject()
    val projectDir = File(rule.getMainBuildDirectory().toFile(), "lib")

    TruthHelper.assertThat<ProjectType>(modelInfo.basicAndroidProject!!.projectType).isEqualTo(ProjectType.LIBRARY)

    LibrarySourceProviderHelper("lib", projectDir, "main", modelInfo.basicAndroidProject!!.mainSourceSet!!.sourceProvider)
      .setJavaDir("src")
      .setKotlinDirs()
      .setResourcesDir("src")
      .setAidlDir("src")
      .setRenderscriptDir("src")
      .setResDir("res")
      .setAarKeepRulesDir("src")
      .setAssetsDir("assets")
      .setManifestFile("AndroidManifest.xml")
      .testV2()
  }
}
