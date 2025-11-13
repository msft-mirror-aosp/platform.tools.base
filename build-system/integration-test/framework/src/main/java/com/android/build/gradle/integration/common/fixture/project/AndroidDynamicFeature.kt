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

import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.reversible.FileChangeController
import java.nio.file.Path

/*
 * Support for Android Dynamic Feature in the [GradleRule] fixture
 */

/**
 * Implementation of [AndroidProjectDefinition] for [DynamicFeatureExtension]
 *
 * @param path the Gradle path of the project
 * @param createMinimumProject whether to initialized default values on required properties
 */
internal class AndroidDynamicFeatureDefinitionImpl(
    path: String,
    createMinimumProject: Boolean
): AndroidProjectDefinitionImpl<DynamicFeatureExtension>(path) {
    init {
        applyPlugin(PluginType.ANDROID_DYNAMIC_FEATURE)
    }

    override val android: DynamicFeatureExtension =
        DslProxy.createProxy(
            DynamicFeatureExtension::class.java,
            dslRecorder,
        ).also {
            if (createMinimumProject) {
                initDefaultValues(it)
            }
        }
}

/**
 * Specialized interface for dynamic feature [AndroidProject] to use in the test
 */
interface AndroidDynamicFeatureProject: AndroidProject<AndroidProjectDefinition<DynamicFeatureExtension>>

/**
 * Implementation of [AndroidProject]
 */
internal class AndroidFeatureImpl(
    location: Path,
    projectDefinition: AndroidProjectDefinition<DynamicFeatureExtension>,
    namespace: String,
) : AndroidProjectImpl<AndroidProjectDefinition<DynamicFeatureExtension>>(
    location,
    projectDefinition,
    namespace,
), AndroidDynamicFeatureProject {

    override fun getReversibleInstance(fileChangeController: FileChangeController): AndroidDynamicFeatureProject =
        ReversibleAndroidDynamicFeatureProject(this, fileChangeController)
}

/**
 * Reversible version of [AndroidDynamicFeatureProject]
 */
internal class ReversibleAndroidDynamicFeatureProject(
    parentProject: AndroidDynamicFeatureProject,
    fileChangeController: FileChangeController
) : ReversibleAndroidProject<AndroidDynamicFeatureProject, AndroidProjectDefinition<DynamicFeatureExtension>>(
    parentProject,
    fileChangeController
), AndroidDynamicFeatureProject
