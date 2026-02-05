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

import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.reversible.FileChangeController
import com.android.build.gradle.integration.common.output.ApkSubject
import java.nio.file.Path

/*
 * Support for Android Library in the [GradleRule] fixture
 */

/**
 * Implementation of [AndroidProjectDefinition] for [LibraryExtension]
 *
 * @param path the Gradle path of the project
 * @param createMinimumProject whether to initialized default values on required properties
 */
internal class AndroidLibraryDefinitionImpl(path: String, createMinimumProject: Boolean) :
  AndroidProjectDefinitionImpl<LibraryExtension>(path) {
  init {
    applyPlugin(PluginType.ANDROID_LIB)
  }

  override val android: LibraryExtension =
    DslProxy.createProxy(LibraryExtension::class.java, dslRecorder).also {
      if (createMinimumProject) {
        initDefaultValues(it)
      }
    }
}

/** Specialized interface for library [AndroidProject] to use in the test */
interface AndroidLibraryProject : AndroidProject<AndroidProjectDefinition<LibraryExtension>>, GeneratesApk, GeneratesAar

/** Implementation of [AndroidProject] */
internal class AndroidLibraryImpl(location: Path, projectDefinition: AndroidProjectDefinition<LibraryExtension>, namespace: String) :
  AndroidProjectImpl<AndroidProjectDefinition<LibraryExtension>>(location, projectDefinition, namespace),
  AndroidLibraryProject,
  GeneratesAar by GeneratesAarDelegate(projectDefinition.path, location) {
  private val apkDelegate = GeneratesApkDelegate(projectDefinition.path, location)

  override fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit) {
    if ((apkSelector as ApkSelectorImp).testSuite == null) {
      error("Querying a non test APK from a library project.")
    }
    apkDelegate.assertApk(apkSelector, action)
  }

  override fun getApkLocationForCopy(apkSelector: ApkSelector): Path {
    if ((apkSelector as ApkSelectorImp).testSuite == null) {
      error("Querying a non test APK from a library project.")
    }
    return apkDelegate.getApkLocationForCopy(apkSelector)
  }

  override fun getReversibleInstance(fileChangeController: FileChangeController): AndroidLibraryProject =
    ReversibleAndroidLibraryProject(this, fileChangeController)
}

/** Reversible version of [AndroidLibraryProject] */
internal class ReversibleAndroidLibraryProject(parentProject: AndroidLibraryProject, fileChangeController: FileChangeController) :
  ReversibleAndroidProject<AndroidLibraryProject, AndroidProjectDefinition<LibraryExtension>>(parentProject, fileChangeController),
  AndroidLibraryProject,
  GeneratesApk by GeneratesApkFromParentDelegate(parentProject),
  GeneratesAar by GeneratesAarFromParentDelegate(parentProject)
