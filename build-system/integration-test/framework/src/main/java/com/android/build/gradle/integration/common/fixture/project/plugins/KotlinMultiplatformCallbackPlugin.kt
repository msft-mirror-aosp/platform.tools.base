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

package com.android.build.gradle.integration.common.fixture.project.plugins

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A Custom plugin to be used with [KotlinMultiplatformCallback] in projects created
 * by [GradleRule].
 *
 * Do not extend this. Instead, implement [KotlinMultiplatformCallback] and register the implementation
 * class to [com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition.pluginCallbacks]
 *
 * This class is automatically decorated to call the callback at runtime.
 */
abstract class KotlinMultiplatformCallbackPlugin: Plugin<Project> {

    override fun apply(target: Project) {
        target.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val extension = target.extensions.getByType(KotlinMultiplatformExtension::class.java)
            handleExtension(target, extension)
        }
    }

    abstract fun handleExtension(
        project: Project,
        extension: KotlinMultiplatformExtension
    )
}


/**
 * interface to implement to provide custom plugin logic to a [GradleRule] project
 * of type Kotlin Multiplatform
 */
interface KotlinMultiplatformCallback: PluginCallback {
    fun handleExtension(
        project: Project,
        extension: KotlinMultiplatformExtension
    )
}
