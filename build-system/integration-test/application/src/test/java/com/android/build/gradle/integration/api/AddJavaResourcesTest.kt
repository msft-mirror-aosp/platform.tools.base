/*
 * Copyright (C) 2022 The Android Open Source Project
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
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AddJavaResourcesTest(private val useNewDsl: Boolean, private val disallowProvider: Boolean) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "useNewDsl={0}, disallowProvider={1}")
    fun parameters() = listOf(arrayOf(true, true), arrayOf(true, false), arrayOf(false, true), arrayOf(false, false))
  }

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        pluginCallbacks += if (useNewDsl) AddJavaResourcesCallback::class.java else AddJavaResourceLegacyCallback::class.java
      }
      gradleProperties { add(BooleanOption.USE_NEW_DSL, useNewDsl) }
    }

  /** Regression test for http://b/263469991. */
  @Test
  fun testAddingJavaResourcesOldApi() {
    val build = rule.build.executor.with(BooleanOption.DISALLOW_PROVIDER_IN_ANDROID_SOURCE_SET, disallowProvider)
    if (disallowProvider) {
      build.expectFailure()
    }
    build.run(":app:processDebugJavaRes")
  }
}

abstract class VersionFileWriterTask : DefaultTask() {
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction fun run() {}
}

class AddJavaResourceLegacyCallback : LegacyApplicationCallback {
  override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
    val writeVersionFile =
      project.tasks.register("writeVersionFile", VersionFileWriterTask::class.java) {
        it.outputDirectory.set(project.layout.buildDirectory.dir("foo"))
      }
    extension.applicationVariants.all { variant ->
      if (variant.name == "debug") {
        val outputDir = writeVersionFile.flatMap { it.outputDirectory }
        extension.sourceSets.getByName(variant.name).resources.srcDir(outputDir)
      }
    }
  }
}

class AddJavaResourcesCallback : ApplicationComponentCallback {

  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    val writeVersionFile =
      project.tasks.register("writeVersionFile", VersionFileWriterTask::class.java) {
        it.outputDirectory.set(project.layout.buildDirectory.dir("foo"))
      }
    androidComponents.finalizeDsl {
      val outputDir = writeVersionFile.flatMap { it.outputDirectory }
      it.sourceSets.getByName("debug").resources.srcDir(outputDir)
    }
  }
}
