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

package com.android.build.gradle.integration.compress

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class CompressAssetsTaskTest {

  @get:Rule
  val rule =
    GradleRule.from {
      buildFileType = BuildFileType.KTS
      androidApplication {
        android {
          compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          namespace = "com.example"
        }
        pluginCallbacks += MyAppCallback::class.java
      }
      gradleProperties { add("org.gradle.jvmargs", "-Xmx1G -XX:MaxMetaspaceSize=1G") }
    }

  class MyAppCallback : ApplicationComponentCallback {

    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        val prepareAssetsTask = project.tasks.register("${variant.name}PrepareAssets", CompressTestPrepareAssetsTask::class.java)
        variant.sources.assets?.addGeneratedSourceDirectory(prepareAssetsTask, CompressTestPrepareAssetsTask::outputDirectory)
      }
    }
  }

  // regression for b/405676717
  @Test
  fun outOfMemory() {
    rule.build.executor.run(":app:compressDebugAssets")
  }
}

abstract class CompressTestPrepareAssetsTask : DefaultTask() {

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun generate() {
    val assetFile = outputDirectory.get().file("asset.data").asFile
    assetFile.delete()
    val random = java.util.Random(123)
    val bytes = ByteArray(1_000_000)
    // generating 1gb of data
    assetFile.outputStream().use { outputStream ->
      repeat(1000) {
        random.nextBytes(bytes)
        outputStream.write(bytes)
      }
    }
  }
}
