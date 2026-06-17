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

import com.android.build.api.dsl.ApplicationDeclarativeDefinition
import com.android.build.api.dsl.LibraryDeclarativeDefinition
import com.android.build.gradle.internal.plugins.AppPlugin
import com.android.build.gradle.internal.plugins.LibraryPlugin
import org.gradle.api.Incubating
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.features.annotations.RegistersProjectFeatures

@Suppress("UnstableApiUsage")
@Incubating
@RegistersProjectFeatures(AppPlugin::class, LibraryPlugin::class)
class AndroidEcosystemPlugin : Plugin<Settings> {
  override fun apply(target: Settings) {
    target.defaults.add("androidApp", ApplicationDeclarativeDefinition::class.java) {}
    target.defaults.add("androidLibrary", LibraryDeclarativeDefinition::class.java) {}
  }
}
