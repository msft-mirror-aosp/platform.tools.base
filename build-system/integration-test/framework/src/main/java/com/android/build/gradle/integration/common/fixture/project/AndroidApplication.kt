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
import com.android.build.gradle.integration.common.truth.AabSubject
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.testutils.apk.Aab
import com.android.testutils.apk.Apk
import java.io.File
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/*
 * Support for Android Application in the [GradleRule] fixture
 */

/**
 * Implementation of [AndroidProjectDefinition] for [ApplicationExtension]
 */
internal class AndroidApplicationDefinitionImpl(
    path: String
): AndroidProjectDefinitionImpl<ApplicationExtension>(path) {
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
interface AndroidApplicationProject: AndroidProject<AndroidProjectDefinition<ApplicationExtension>>, GeneratesApk {
    /**
     * Runs the action with a provided instance of [Aab].
     *
     * It is possible to return a value from the action, but it should not be [Aab] as this
     * may not be safe. [Aab] is a [AutoCloseable] and should be treated as such.
     */
    fun <R> withBundle(bundleSelector: BundleSelector, action: Aab.() -> R): R

    /**
     * Runs the action with a provided [ZipSubject]
     */
    fun assertBundle(bundleSelector: BundleSelector, action: AabSubject.() -> Unit)

    fun getBundle(bundleSelector: BundleSelector): File
}

/**
 * Implementation of [AndroidProject]
 */
internal class AndroidApplicationImpl(
    location: Path,
    projectDefinition: AndroidProjectDefinition<ApplicationExtension>,
    namespace: String,
    buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
) : AndroidProjectImpl<AndroidProjectDefinition<ApplicationExtension>>(
    location,
    projectDefinition,
    namespace,
    buildWriter,
    parentBuild
), AndroidApplicationProject {

    override fun <R> withBundle(bundleSelector: BundleSelector, action: Aab.() -> R): R {
        val path = computeOutputPath(bundleSelector)
        if (!path.isRegularFile()) error("Bundle file does not exist: $path")

        return Aab(path.toFile()).use {
            action(it)
        }
    }

    override fun assertBundle(bundleSelector: BundleSelector, action: AabSubject.() -> Unit) {
        withBundle(bundleSelector) {
            AabSubject.assertThat(this).use {
                action(it)
            }
        }
    }

    override fun getBundle(bundleSelector: BundleSelector): File =
        computeOutputPath(bundleSelector).toFile()

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): AndroidApplicationProject =
        ReversibleAndroidApplicationProject(this, projectModification)
}

/**
 * Reversible version of [AndroidApplicationProject]
 */
internal class ReversibleAndroidApplicationProject(
    parentProject: AndroidApplicationProject,
    projectModification: TemporaryProjectModification
) : ReversibleAndroidProject<AndroidApplicationProject, AndroidProjectDefinition<ApplicationExtension>>(
    parentProject,
    projectModification
), AndroidApplicationProject {

    override fun <R> withApk(apkSelector: ApkSelector, action: Apk.() -> R): R =
        parentProject.withApk(apkSelector, action)

    override fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit) {
        parentProject.assertApk(apkSelector, action)
    }

    override fun hasApk(apkSelector: ApkSelector): Boolean = parentProject.hasApk(apkSelector)

    override fun <R> withBundle(bundleSelector: BundleSelector, action: Aab.() -> R): R =
        parentProject.withBundle(bundleSelector, action)

    override fun assertBundle(bundleSelector: BundleSelector, action: AabSubject.() -> Unit) {
        parentProject.assertBundle(bundleSelector, action)
    }

    override fun getBundle(bundleSelector: BundleSelector): File =
        parentProject.getBundle(bundleSelector)
}

