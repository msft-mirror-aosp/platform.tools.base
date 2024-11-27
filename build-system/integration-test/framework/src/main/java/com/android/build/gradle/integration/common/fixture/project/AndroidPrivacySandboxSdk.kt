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
import com.android.build.api.dsl.PrivacySandboxSdkExtension
import com.android.build.gradle.integration.common.fixture.GradleTestProject
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
 * Support for Android Privacy Sandbox SDK in the [GradleRule] fixture
 */

/**
 *  Implementation of [AndroidProjectDefinition] for Privacy Sandbox SDK
 */
internal class PrivacySandboxSdkDefinitionImpl(path: String): AndroidProjectDefinitionImpl<PrivacySandboxSdkExtension>(path) {
    init {
        applyPlugin(PluginType.PRIVACY_SANDBOX_SDK)
    }

    override val namespace: String
        get() = android.namespace ?: throw RuntimeException("Namespace has not been set yet!")

    override val android: PrivacySandboxSdkExtension =
        DslProxy.createProxy(
            PrivacySandboxSdkExtension::class.java,
            contentHolder,
        ).also {
            initDefaultValues(it)
        }


    override fun initDefaultValues(extension: PrivacySandboxSdkExtension) {
        val pkgName = if (path == ":") {
            "pkg.name"
        } else {
            "pkg.name${path.replace(':', '.')}"
        }
        extension.namespace = pkgName
        extension.compileSdk = GradleTestProject.DEFAULT_COMPILE_SDK_VERSION.toInt()
    }
}

/**
 * Specialized interface for application [AndroidProject] to use in the test
 */
interface AndroidPrivacySandboxSdkProject: AndroidProject<PrivacySandboxSdkExtension>

/**
 * Implementation of [AndroidProject]
 */
internal class AndroidPrivacySandboxSdkImpl(
    location: Path,
    override val projectDefinition: AndroidProjectDefinition<PrivacySandboxSdkExtension>,
    namespace: String,
    private val buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
) : AndroidProjectImpl<PrivacySandboxSdkExtension>(
    location,
    projectDefinition,
    namespace,
    parentBuild
), AndroidPrivacySandboxSdkProject {

    override fun reconfigure(
        buildFileOnly: Boolean,
        action: AndroidProjectDefinition<PrivacySandboxSdkExtension>.() -> Unit
    ) {
        action(projectDefinition)

        // we need to query the other projects for their plugins
        val allPlugins = parentBuild.computeAllPluginMap()

        projectDefinition as AndroidProjectDefinitionImpl<PrivacySandboxSdkExtension>
        projectDefinition.writeSubProject(location, buildFileOnly, allPlugins, buildWriter)
    }

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): GradleProject =
        ReversibleAndroidPrivacySandboxSdkProject(this, projectModification)
}

/**
 * Reversible version of [AndroidPrivacySandboxSdkProject]
 */
internal class ReversibleAndroidPrivacySandboxSdkProject(
    parentProject: AndroidPrivacySandboxSdkProject,
    projectModification: TemporaryProjectModification
) : ReversibleAndroidProject<AndroidPrivacySandboxSdkProject, PrivacySandboxSdkExtension>(
    parentProject,
    projectModification
), AndroidPrivacySandboxSdkProject

