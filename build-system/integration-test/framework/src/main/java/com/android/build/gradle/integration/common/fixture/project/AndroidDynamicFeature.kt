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

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.testutils.apk.Apk
import java.nio.file.Path

/*
 * Support for Android Dynamic Feature in the [GradleRule] fixture
 */

/**
 * An Android Feature Project
 */
internal class AndroidDynamicFeatureDefinitionImpl(
    path: String
): AndroidProjectDefinitionImpl<DynamicFeatureExtension>(path) {
    init {
        applyPlugin(PluginType.ANDROID_DYNAMIC_FEATURE)
    }

    override val android: DynamicFeatureExtension =
        DslProxy.createProxy(
            DynamicFeatureExtension::class.java,
            contentHolder,
        ).also {
            initDefaultValues(it)
        }
}

/**
 * Specialized interface for dynamic feature [AndroidProject] to use in the test
 */
interface AndroidDynamicFeatureProject: AndroidProject<DynamicFeatureExtension>

/**
 * Implementation of [AndroidProject]
 */
internal class AndroidFeatureImpl(
    location: Path,
    override val projectDefinition: AndroidProjectDefinition<DynamicFeatureExtension>,
    namespace: String,
    private val buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
) : AndroidProjectImpl<DynamicFeatureExtension>(
    location,
    projectDefinition,
    namespace,
    parentBuild
), AndroidDynamicFeatureProject {

    override fun reconfigure(
        buildFileOnly: Boolean,
        action: AndroidProjectDefinition<DynamicFeatureExtension>.() -> Unit
    ) {
        action(projectDefinition)

        // we need to query the other projects for their plugins
        val allPlugins = parentBuild.computeAllPluginMap()

        projectDefinition as AndroidProjectDefinitionImpl<DynamicFeatureExtension>
        projectDefinition.writeSubProject(location, buildFileOnly, allPlugins, buildWriter)
    }

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): GradleProject =
        ReversibleAndroidDynamicFeatureProject(this, projectModification)
}

/**
 * Reversible version of [AndroidDynamicFeatureProject]
 */
internal class ReversibleAndroidDynamicFeatureProject(
    parentProject: AndroidDynamicFeatureProject,
    projectModification: TemporaryProjectModification
) : ReversibleAndroidProject<AndroidDynamicFeatureProject, DynamicFeatureExtension>(
    parentProject,
    projectModification
), AndroidDynamicFeatureProject {
}

