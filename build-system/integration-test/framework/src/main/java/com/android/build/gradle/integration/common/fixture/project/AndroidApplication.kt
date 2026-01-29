/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.project

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.reversible.FileChangeController
import com.android.build.gradle.integration.common.utils.getApkLocations
import com.android.build.gradle.integration.common.utils.getVariantByName
import java.io.File
import java.nio.file.Path

/*
 * Support for Android Application in the [GradleRule] fixture
 */

/**
 * Implementation of [AndroidProjectDefinition] for [ApplicationExtension]
 *
 * @param path the Gradle path of the project
 * @param createMinimumProject whether to initialized default values on required properties
 */
internal class AndroidApplicationDefinitionImpl(path: String, createMinimumProject: Boolean) :
  AndroidProjectDefinitionImpl<ApplicationExtension>(path) {
  init {
    applyPlugin(PluginType.ANDROID_APP)
  }

  override val android: ApplicationExtension =
    DslProxy.createProxy(ApplicationExtension::class.java, dslRecorder).also {
      if (createMinimumProject) {
        initDefaultValues(it)
      }
    }
}

/** Specialized interface for application [AndroidProject] to use in the test */
interface AndroidApplicationProject : AndroidProject<AndroidProjectDefinition<ApplicationExtension>>, GeneratesApk, GeneratesAab {

  fun getApkFromBundleTaskName(variantName: String): String

  fun locateApkFolderViaModel(variantName: String): File
}

/** Implementation of [AndroidProject] */
internal class AndroidApplicationImpl(
  location: Path,
  projectDefinition: AndroidProjectDefinition<ApplicationExtension>,
  namespace: String,
) :
  AndroidProjectImpl<AndroidProjectDefinition<ApplicationExtension>>(location, projectDefinition, namespace),
  AndroidApplicationProject,
  GeneratesAab by GeneratesAabDelegate(location),
  GeneratesApk by GeneratesApkDelegate(projectDefinition.path, location) {

  override fun getApkFromBundleTaskName(variantName: String): String {
    val projectPath = projectDefinition.path
    val model =
      build.modelBuilder.fetchModels().container.getProject(projectPath).androidProject
        ?: throw RuntimeException("Failed to get sync model for $projectPath module")

    val variantMainArtifact = model.getVariantByName(variantName).mainArtifact
    return variantMainArtifact.bundleInfo?.apkFromBundleTaskName
      ?: throw RuntimeException("Module $projectPath does not have apkFromBundle task name")
  }

  override fun locateApkFolderViaModel(variantName: String): File {
    val projectPath = projectDefinition.path

    val apkFiles =
      build.modelBuilder.fetchModels().container.getProject(projectPath).androidProject?.getVariantByName(variantName)?.getApkLocations()

    return apkFiles?.getOrNull(0)?.parentFile ?: throw RuntimeException("Failed to get apk folder for $projectPath module")
  }

  override fun getReversibleInstance(fileChangeController: FileChangeController): AndroidApplicationProject =
    ReversibleAndroidApplicationProject(this, fileChangeController)
}

/** Reversible version of [AndroidApplicationProject] */
internal class ReversibleAndroidApplicationProject(parentProject: AndroidApplicationProject, fileChangeController: FileChangeController) :
  ReversibleAndroidProject<AndroidApplicationProject, AndroidProjectDefinition<ApplicationExtension>>(parentProject, fileChangeController),
  AndroidApplicationProject,
  GeneratesApk by GeneratesApkFromParentDelegate(parentProject),
  GeneratesAab by GeneratesAabFromParentDelegate(parentProject) {

  override fun getApkFromBundleTaskName(variantName: String): String = parentProject.getApkFromBundleTaskName(variantName)

  override fun locateApkFolderViaModel(variantName: String): File = parentProject.locateApkFolderViaModel(variantName)
}
