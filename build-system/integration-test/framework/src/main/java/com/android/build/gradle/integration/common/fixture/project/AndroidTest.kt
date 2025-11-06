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
import com.android.build.api.dsl.TestExtension
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.reversible.FileChangeController
import java.nio.file.Path

/*
 * Support for Android Test in the [GradleRule] fixture
 */

/**
 * Implementation of [AndroidProjectDefinition] for [ApplicationExtension]
 */
internal class AndroidTestDefinitionImpl(
    path: String,
    createMinimumProject: Boolean
): AndroidProjectDefinitionImpl<TestExtension>(path) {
    init {
        applyPlugin(PluginType.ANDROID_TEST)
    }

    override val android: TestExtension =
        DslProxy.createProxy(
            TestExtension::class.java,
            dslRecorder,
        ).also {
            if (createMinimumProject) {
                initDefaultValues(it)
            }
        }
}

/**
 * Specialized interface for android test [AndroidProject] to use in the test
 */
interface AndroidTestProject: AndroidProject<AndroidProjectDefinition<TestExtension>>, GeneratesApk

/**
 * Implementation of [AndroidProject]
 */
internal class AndroidTestImpl(
    location: Path,
    projectDefinition: AndroidProjectDefinition<TestExtension>,
    namespace: String,
) : AndroidProjectImpl<AndroidProjectDefinition<TestExtension>>(
    location,
    projectDefinition,
    namespace,
), AndroidTestProject, GeneratesApk by GeneratesApkDelegate(projectDefinition.path, location) {

    override fun getReversibleInstance(fileChangeController: FileChangeController): AndroidTestProject =
        ReversibleAndroidTestProject(this, fileChangeController)
}

/**
 * Reversible version of [AndroidTestProject]
 */
internal class ReversibleAndroidTestProject(
    parentProject: AndroidTestProject,
    fileChangeController: FileChangeController
) : ReversibleAndroidProject<AndroidTestProject, AndroidProjectDefinition<TestExtension>>(
    parentProject,
    fileChangeController
), AndroidTestProject, GeneratesApk by GeneratesApkFromParentDelegate(parentProject)

