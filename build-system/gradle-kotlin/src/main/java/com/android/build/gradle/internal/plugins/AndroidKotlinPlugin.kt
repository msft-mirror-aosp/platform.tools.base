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

package com.android.build.gradle.internal.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.KotlinBaseApiPlugin

class AndroidKotlinPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.disallowPlugin(
            mainPlugin = ANDROID_BUILT_IN_KOTLIN_PLUGIN_ID,
            incompatiblePlugin = KOTLIN_ANDROID_PLUGIN_ID
        )

        // Apply KotlinBaseApiPlugin
        val kotlinBaseApiPlugin = project.plugins.apply(KotlinBaseApiPlugin::class.java)

        // Add the `kotlin` extension
        val kotlinAndroidExtension = kotlinBaseApiPlugin.createKotlinAndroidExtension()
        project.extensions.add("kotlin", kotlinAndroidExtension)

        // Set default coreLibrariesVersion
        kotlinAndroidExtension.coreLibrariesVersion = kotlinBaseApiPlugin.pluginVersion
    }
}

/**
 * Fails the build if the given [incompatiblePlugin] has been applied.
 *
 * If the [incompatiblePlugin] has not been applied but will be applied later, then this will make
 * the build fail later at the point when the [incompatiblePlugin] has been applied (unless that
 * plugin fails the build before that point -- see b/397373580).
 *
 * [mainPlugin] is the plugin that the [incompatiblePlugin] is incompatible with.
 */
internal fun Project.disallowPlugin(mainPlugin: String, incompatiblePlugin: String) {
    pluginManager.withPlugin(incompatiblePlugin) {
        error(
            """
            The '$incompatiblePlugin' plugin is not compatible with the '$mainPlugin' plugin.
            Remove the '$incompatiblePlugin' plugin from this project's build file: $buildFile.
            """.trimIndent()
        )
    }
}

internal const val ANDROID_BUILT_IN_KOTLIN_PLUGIN_ID = "com.android.experimental.built-in-kotlin"
internal const val KOTLIN_ANDROID_PLUGIN_ID = "org.jetbrains.kotlin.android"
