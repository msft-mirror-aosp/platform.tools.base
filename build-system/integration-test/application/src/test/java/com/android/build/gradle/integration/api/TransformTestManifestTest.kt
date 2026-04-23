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
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.google.common.truth.Truth.assertThat
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class TransformTestManifestTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidLibrary {
        android { testOptions { unitTests { androidResources { isIncludeAndroidResources = true } } } }
        pluginCallbacks += TransformTestManifestCallback::class.java
      }
    }

  @Test
  fun testTransformMergedManifestForAndroidTest() {
    val build = rule.build
    val result = build.executor.run(":lib:assembleDebugAndroidTest", ":lib:assembleDebugUnitTest")

    // android test
    assertThat(result.didWorkTasks).contains(":lib:debugAndroidTestManifestUpdater")
    assertThat(result.didWorkTasks).contains(":lib:mergeDebugAndroidTestManifest")
    assertThat(result.didWorkTasks).contains(":lib:processDebugAndroidTestManifest")

    var manifest =
      build
        .subProject(":lib")
        .resolve(InternalArtifactType.PACKAGED_MANIFESTS)
        .resolve("debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml")
        .toFile()
    assertThat(manifest.exists()).isTrue()
    assertThat(manifest.readText()).contains("<!-- Updated Manifest -->")

    // unit test
    assertThat(result.didWorkTasks).contains(":lib:debugUnitTestManifestUpdater")
    assertThat(result.didWorkTasks).contains(":lib:mergeDebugUnitTestManifest")
    assertThat(result.didWorkTasks).contains(":lib:processDebugUnitTestManifest")

    manifest =
      build
        .subProject(":lib")
        .resolve(InternalArtifactType.PACKAGED_MANIFESTS)
        .resolve("debugUnitTest/processDebugUnitTestManifest/AndroidManifest.xml")
        .toFile()
    assertThat(manifest.exists()).isTrue()
    assertThat(manifest.readText()).contains("<!-- Updated Manifest -->")
  }
}

abstract class ManifestUpdaterTask : DefaultTask() {

  @get:InputFile abstract val mergedManifest: RegularFileProperty

  @get:OutputFile abstract val updatedManifest: RegularFileProperty

  @TaskAction
  fun taskAction() {
    val manifestFile = mergedManifest.get().asFile
    val updatedFile = updatedManifest.get().asFile
    var content = manifestFile.readText()
    content = content.replace("</manifest>", "<!-- Updated Manifest --></manifest>")
    updatedFile.writeText(content)
    println("Updated merged manifest")
  }
}

class TransformTestManifestCallback : LibraryComponentCallback {

  override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
      variant.nestedComponents.forEach { component ->
        if (component.name == "debugAndroidTest" || component.name == "debugUnitTest") {
          val manifestUpdater = project.tasks.register(component.name + "ManifestUpdater", ManifestUpdaterTask::class.java)
          component.artifacts
            .use(manifestUpdater)
            .wiredWithFiles(ManifestUpdaterTask::mergedManifest, ManifestUpdaterTask::updatedManifest)
            .toTransform(SingleArtifact.MERGED_MANIFEST)
        }
      }
    }
  }
}
