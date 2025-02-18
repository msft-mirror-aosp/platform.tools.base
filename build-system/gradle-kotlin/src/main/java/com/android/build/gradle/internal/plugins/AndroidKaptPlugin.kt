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

class AndroidKaptPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.requirePlugin(
            mainPlugin = ANDROID_BUILT_IN_KAPT_PLUGIN_ID,
            requiredPlugin = ANDROID_BUILT_IN_KOTLIN_PLUGIN_ID
        )
        project.disallowPlugin(
            mainPlugin = ANDROID_BUILT_IN_KAPT_PLUGIN_ID,
            incompatiblePlugin = KOTLIN_KAPT_PLUGIN_ID
        )

        // Apply KotlinBaseApiPlugin
        val kotlinBaseApiPlugin = project.plugins.apply(KotlinBaseApiPlugin::class.java)

        // Add the `kapt` extension
        project.extensions.add("kapt", kotlinBaseApiPlugin.kaptExtension)
    }
}

/**
 * Fails the build if the given [requiredPlugin] has not been applied after this project's
 * evaluation.
 *
 * [mainPlugin] is the plugin that requires the [requiredPlugin].
 */
private fun Project.requirePlugin(mainPlugin: String, requiredPlugin: String) {
    afterEvaluate {
        check(pluginManager.hasPlugin(requiredPlugin)) {
            """
            The '$mainPlugin' plugin requires the '$requiredPlugin' plugin to be applied.
            Apply the '$requiredPlugin' plugin in this project's build file: $buildFile.
            """.trimIndent()
        }
    }
}

internal const val ANDROID_BUILT_IN_KAPT_PLUGIN_ID = "com.android.legacy-kapt"
internal const val KOTLIN_KAPT_PLUGIN_ID = "org.jetbrains.kotlin.kapt"
