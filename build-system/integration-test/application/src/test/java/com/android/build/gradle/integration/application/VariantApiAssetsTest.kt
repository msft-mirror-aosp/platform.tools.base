/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.integration.application

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

/** Tests for the Variant API Assets interactions using the modern GradleRule fixture. */
class VariantApiAssetsTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidLibrary(":lib") { files.add("src/main/assets/lib_asset.txt", "I am from the library") }
      androidApplication(":app") {
        pluginCallbacks += AddAssetTransformCallback::class.java
        dependencies { implementation(project(":lib")) }
      }
    }

  /**
   * Verifies that [SingleArtifact.ASSETS] can be transformed.
   *
   * Specifically checks that assets from library dependencies (AARs) are included in the transformation input (Regression test for
   * b/477562205).
   */
  @Test
  fun transformAssets() {
    val build = rule.build
    val app = build.androidApplication(":app")

    build.executor.run(":app:assembleDebug")

    app.assertApk(ApkSelector.DEBUG) { assets().resourceAsText("lib_asset.txt").contains("I am from the library - TRANSFORMED") }
  }
}

abstract class AssetTransformTask : DefaultTask() {
  @get:InputDirectory abstract val inputDir: DirectoryProperty

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @get:Inject abstract val fileSystemOperations: FileSystemOperations

  @TaskAction
  fun taskAction() {
    fileSystemOperations.copy {
      it.from(inputDir)
      it.into(outputDir)
    }

    val libAsset = File(outputDir.get().asFile, "lib_asset.txt")
    if (libAsset.exists()) {
      libAsset.appendText(" - TRANSFORMED")
    } else {
      throw RuntimeException("Library asset not found in merged input!")
    }
  }
}

class AddAssetTransformCallback : ApplicationComponentCallback {
  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().all()) { variant ->
      val assetTask = project.tasks.register("${variant.name}AssetTransform", AssetTransformTask::class.java)

      variant.artifacts.use(assetTask).wiredWithDirectories({ it.inputDir }, { it.outputDir }).toTransform(SingleArtifact.ASSETS)
    }
  }
}
