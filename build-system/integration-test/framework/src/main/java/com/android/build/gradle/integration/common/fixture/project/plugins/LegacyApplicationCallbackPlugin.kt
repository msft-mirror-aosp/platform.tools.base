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

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A Custom plugin to be used with [LegacyApplicationCallback] in projects created
 * by [GradleRule].
 *
 * Do not extend this. Instead, implement [LegacyApplicationCallback] and register the implementation
 * class to [com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition.pluginCallbacks]
 *
 * This class is automatically decorated to call the callback at runtime.
 */
abstract class LegacyApplicationCallbackPlugin: Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.withType(AppPlugin::class.java) {
            val androidExtension = target.extensions.getByType(ApplicationExtension::class.java)

            // cast it to the implementation because we can
            val androidExtensionImpl = androidExtension as BaseAppModuleExtension
            handleExtension(target, androidExtensionImpl)
        }
    }

    abstract fun handleExtension(
        project: Project,
        extension: BaseAppModuleExtension
    )
}

/**
 * interface to implement to provide custom plugin logic to a [GradleRule] project
 * of type Android Application.
 *
 * This allows using the legacy DSL using internal types rather than the public extension
 */
interface LegacyApplicationCallback: PluginCallback {
    fun handleExtension(
        project: Project,
        extension: BaseAppModuleExtension
    )
}
