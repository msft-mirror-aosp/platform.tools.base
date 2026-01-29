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

package com.android.build.gradle.integration.common.fixture.project.plugins

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A Custom plugin to be used with [LibraryComponentCallback] in projects created by [GradleRule].
 *
 * Do not extend this. Instead, implement [LibraryComponentCallback] and register the implementation class to
 * [com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition.pluginCallbacks]
 *
 * This class is automatically decorated to call the callback at runtime.
 */
abstract class LibraryCallbackPlugin : Plugin<Project> {

  override fun apply(target: Project) {
    target.plugins.withType(LibraryPlugin::class.java) {
      val componentsExtension = target.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
      handleExtension(target, componentsExtension)
    }
  }

  abstract fun handleExtension(project: Project, componentsExtension: LibraryAndroidComponentsExtension)
}

/** interface to implement to provide custom plugin logic to a [GradleRule] project of type Android Library */
interface LibraryComponentCallback : PluginCallback {
  fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension)
}
