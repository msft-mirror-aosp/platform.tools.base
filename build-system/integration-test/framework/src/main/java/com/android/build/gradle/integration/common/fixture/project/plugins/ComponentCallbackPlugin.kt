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

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.BasePlugin
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A Custom plugin to be used with [GenericComponentCallback] in projects created by [GradleRule].
 *
 * Do not extend this. Instead, implement [GenericComponentCallback] and register the implementation class to
 * [com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition.pluginCallbacks]
 *
 * This class is automatically decorated to call the callback at runtime.
 */
abstract class GenericComponentCallbackPlugin : Plugin<Project> {

  override fun apply(target: Project) {
    target.plugins.withType(BasePlugin::class.java) {
      val componentsExtension = target.extensions.getByType(AndroidComponentsExtension::class.java)
      handleExtension(target, componentsExtension)
    }
  }

  abstract fun handleExtension(project: Project, componentsExtension: AndroidComponentsExtension<*, *, *>)
}

/** interface to implement to provide custom plugin logic to a [GradleRule] project */
interface GenericComponentCallback : PluginCallback {
  fun handleExtension(project: Project, androidComponents: AndroidComponentsExtension<*, *, *>)
}
