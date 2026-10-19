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

package com.android.build.gradle.integration.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.utils.getApkFolderOutput
import com.android.build.gradle.integration.common.utils.getDebugVariant
import com.android.builder.core.BuilderConstants
import java.io.File
import org.gradle.api.Project
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

/* Intended to replace RenamedApkTest from AGP 10.0 as it supports new variant DSL. */
class RenamedApkTest2 {

  @get:Rule
  val project = GradleRule.from {
    androidApplication {
      android {
        namespace = "com.android.tests.basic"
        defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
      }
      dependencies {
        testImplementation("junit:junit:4.12")
        androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
        androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
      }
    }
  }

  class Callback : ApplicationComponentCallback {

    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants { variant -> variant.outputs.forEach { it.outputFileName.set("debug.apk") } }
    }
  }

  class RelativePathCallback : ApplicationComponentCallback {

    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants { variant -> variant.outputs.forEach { it.outputFileName.set("..${File.separator}relative_path.apk") } }
    }
  }

  @Test
  fun checkModelReflectsRenamedApk() {
    val build = project.build { androidApplication { pluginCallbacks += Callback::class.java } }
    build.executor.run("clean", "assembleDebug")
    val projectBuildOutput = build.modelBuilder.ignoreSyncIssues().fetchModels(null, null).container.getProject(":app").androidProject
    val debugVariant = projectBuildOutput!!.getDebugVariant()
    val outputFiles: Collection<String> = debugVariant.getApkFolderOutput()

    val buildDir = build.directory.resolve("app/build/outputs/apk/debug")

    Assert.assertEquals(1, outputFiles.size.toLong())
    val output = outputFiles.iterator().next()

    val variantName = BuilderConstants.DEBUG
    Assert.assertEquals("Output file for $variantName", buildDir.resolve("$variantName.apk").toFile(), File(output))
  }

  @Test
  fun checkRenamedApk() {
    val build = project.build { androidApplication { pluginCallbacks += Callback::class.java } }
    build.executor.run("clean", "assembleDebug")
    val debugApk = build.directory.resolve("app/build/outputs/apk/debug/debug.apk").toFile()
    Assert.assertTrue("Check output file: $debugApk", debugApk.isFile)
  }

  @Test
  fun checkRelativePathsNotPermitted() {
    val build = project.build { androidApplication { pluginCallbacks += RelativePathCallback::class.java } }
    val result = build.executor.expectFailure().run("clean", "assembleDebug")
    result.assertErrorContains("File paths are not supported when setting an output file name: ..${File.separator}relative_path.apk")
  }
}
