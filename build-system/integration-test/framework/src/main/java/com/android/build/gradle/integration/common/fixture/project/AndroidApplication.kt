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
 * Support for Android Application in the [GradleRule] fixture
 */

/**
 * Implementation of [AndroidProjectDefinition]
 */
internal class AndroidApplicationDefinitionImpl(path: String): AndroidProjectDefinitionImpl<ApplicationExtension>(path) {
    init {
        applyPlugin(PluginType.ANDROID_APP)
    }

    override val android: ApplicationExtension =
        DslProxy.createProxy(
            ApplicationExtension::class.java,
            contentHolder,
        ).also {
            initDefaultValues(it)
        }
}

/**
 * Specialized interface for application [AndroidProject] to use in the test
 */
interface AndroidApplicationProject: AndroidProject<ApplicationExtension>, GeneratesApk

/**
 * Implementation of [AndroidProject]
 */
internal class AndroidApplicationImpl(
    location: Path,
    override val projectDefinition: AndroidProjectDefinition<ApplicationExtension>,
    namespace: String,
    private val buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
) : AndroidProjectImpl<ApplicationExtension>(location, projectDefinition, namespace, parentBuild),
    AndroidApplicationProject {

    override fun reconfigure(
        buildFileOnly: Boolean,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ) {
        action(projectDefinition)

        // we need to query the other projects for their plugins
        val allPlugins = parentBuild.computeAllPluginMap()

        projectDefinition as AndroidProjectDefinitionImpl<ApplicationExtension>
        projectDefinition.writeSubProject(location, buildFileOnly, allPlugins, buildWriter)
    }

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): GradleProject =
        ReversibleAndroidApplicationProject(this, projectModification)
}

/**
 * Reversible version of [AndroidApplicationProject]
 */
internal class ReversibleAndroidApplicationProject(
    parentProject: AndroidApplicationProject,
    projectModification: TemporaryProjectModification
) : ReversibleAndroidProject<AndroidApplicationProject, ApplicationExtension>(
    parentProject,
    projectModification
), AndroidApplicationProject {

    override fun <R> withApk(apkSelector: ApkSelector, action: Apk.() -> R): R =
        parentProject.withApk(apkSelector, action)

    override fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit) {
        parentProject.assertApk(apkSelector, action)
    }

    override fun hasApk(apkSelector: ApkSelector): Boolean = parentProject.hasApk(apkSelector)
}

