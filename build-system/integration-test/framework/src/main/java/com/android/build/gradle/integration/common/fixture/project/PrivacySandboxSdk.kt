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

import com.android.build.api.dsl.PrivacySandboxSdkExtension
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.DelayedGradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.DirectGradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleDefinitionDsl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import java.nio.file.Path

/*
 * Support for Android Privacy Sandbox SDK in the [GradleRule] fixture
 */

/**
 * Specialized interface for [GradleProjectDefinition]
 */
@GradleDefinitionDsl
interface PrivacySandboxSdkDefinition: GradleProjectDefinition {
    val android: PrivacySandboxSdkExtension
    fun android(action: PrivacySandboxSdkExtension.() -> Unit)

    /** executes the lambda that adds/updates/removes files from the project */
    fun files(action: GradleProjectFiles.() -> Unit)
}

/**
 *  Implementation of [GradleProjectDefinition] for [PrivacySandboxSdkExtension]
 *
 * @param path the Gradle path of the project
 * @param createMinimumProject whether to initialized default values on required properties
 */
internal class PrivacySandboxSdkDefinitionImpl(
    path: String,
    createMinimumProject: Boolean
) : GradleProjectDefinitionImpl(path), PrivacySandboxSdkDefinition {
    init {
        applyPlugin(PluginType.PRIVACY_SANDBOX_SDK)
    }

    override val files: GradleProjectFiles = DelayedGradleProjectFiles()

    override fun files (action: GradleProjectFiles.() -> Unit) {
        action(files)
    }

    override val android: PrivacySandboxSdkExtension =
        DslProxy.createProxy(
            PrivacySandboxSdkExtension::class.java,
            dslRecorder,
        ).also {
            if (createMinimumProject) {
                it.compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
            }
        }

    override fun android(action: PrivacySandboxSdkExtension.() -> Unit) {
        action(android)
    }

    override fun writeExtension(writer: BuildWriter, location: Path) {
        writer.apply {
            block("android") {
                dslRecorder.writeContent(this)
            }
        }
    }
}

/**
 * Specialized interface for privacy sandbox SDK [AndroidProject] to use in the test
 */
interface PrivacySandboxSdkProject: BaseAndroidProject<PrivacySandboxSdkDefinition>

/**
 * Implementation of [PrivacySandboxSdkProject]
 */
internal class PrivacySandboxSdkImpl(
    location: Path,
    projectDefinition: PrivacySandboxSdkDefinition,
) : BaseAndroidProjectImpl<PrivacySandboxSdkDefinition>(
    location,
    projectDefinition,
), PrivacySandboxSdkProject {

    override val files: GradleProjectFiles = DirectGradleProjectFiles(location)

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): PrivacySandboxSdkProject =
        ReversiblePrivacySandboxSdkProject(this, projectModification)
}

/**
 * Reversible version of [PrivacySandboxSdkProject]
 */
internal class ReversiblePrivacySandboxSdkProject(
    parentProject: PrivacySandboxSdkProject,
    projectModification: TemporaryProjectModification
) : BaseReversibleAndroidProjectImpl<PrivacySandboxSdkProject, PrivacySandboxSdkDefinition>(
    parentProject,
    projectModification
), PrivacySandboxSdkProject
