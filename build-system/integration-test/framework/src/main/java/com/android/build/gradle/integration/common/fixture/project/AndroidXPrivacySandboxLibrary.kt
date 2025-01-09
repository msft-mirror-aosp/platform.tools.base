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

/*
 * Support for AndroidX Privacy Sandbox Library in the [GradleRule] fixture
 *
 * Under the hood this is an Android Library so the APIs is mostly the same.
 */

/**
 * Implementation of [AndroidProjectDefinition] for AndroidX Privacy Sandbox Library
 *
 * This is mostly a library with just a different plugin
 *
 * @param path the Gradle path of the project
 * @param createMinimumProject whether to initialized default values on required properties
 */
internal class AndroidXPrivacySandboxLibraryDefinitionImpl(
    path: String,
    createMinimumProject: Boolean,
): AndroidProjectDefinitionImpl<LibraryExtension>(path) {
    init {
        applyPlugin(PluginType.ANDROIDX_PRIVACY_SANDBOX_LIBRARY)
    }

    override val android: LibraryExtension =
        DslProxy.createProxy(
            LibraryExtension::class.java,
            contentHolder,
        ).also {
            if (createMinimumProject) {
                initDefaultValues(it)
            }
        }
}
