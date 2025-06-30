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

package com.android.build.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

@Suppress("unused")
class AndroidKotlinPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Nothing to do here.
        // This plugin only serves as an indication of whether the user wants to have built-in
        // Kotlin support.
        // The handling of built-in Kotlin support is done in the main Android Gradle plugin
        // (see BuiltInKotlinServicesKt.initBuiltInKotlinSupportIfRequired).
    }
}

