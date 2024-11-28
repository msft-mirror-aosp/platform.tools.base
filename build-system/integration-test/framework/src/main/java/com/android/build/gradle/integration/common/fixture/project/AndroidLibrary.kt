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
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.AarSubject
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.testutils.apk.Aar
import com.android.testutils.apk.Apk
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/*
 * Support for Android Library in the [GradleRule] fixture
 */

/**
 * Implementation of [AndroidProjectDefinition] for [LibraryExtension]
 */
internal class AndroidLibraryDefinitionImpl(path: String): AndroidProjectDefinitionImpl<LibraryExtension>(path) {
    init {
        applyPlugin(PluginType.ANDROID_LIB)
    }

    override val android: LibraryExtension =
        DslProxy.createProxy(
            LibraryExtension::class.java,
            contentHolder,
        ).also {
            initDefaultValues(it)
        }
}

/**
 * Specialized interface for library [AndroidProject] to use in the test
 */
interface AndroidLibraryProject: AndroidProject<AndroidProjectDefinition<LibraryExtension>>, GeneratesApk {
    /**
     * Runs the action with a provided instance of [Aar].
     *
     * It is possible to return a value from the action, but it should not be [Aar] as this
     * may not be safe. [Aar] is a [AutoCloseable] and should be treated as such.
     */
    fun <R> withAar(aarSelector: AarSelector, action: Aar.() -> R): R
    /**
     * Runs the action with a provided [AarSubject]
     */
    fun assertAar(aarSelector: AarSelector, action: AarSubject.() -> Unit)
    /**
     * Returns whether or not the AAR exists.
     *
     * To assert validity, prefer using
     * ```
     * project.assertAar(ApkSelector.DEBUG) {
     *   exists()
     * }
     * ```
     */
    fun hasAar(aarSelector: AarSelector): Boolean
}

/**
 * Implementation of [AndroidProject]
 */
internal class AndroidLibraryImpl(
    location: Path,
    projectDefinition: AndroidProjectDefinition<LibraryExtension>,
    namespace: String,
    private val buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
) : AndroidProjectImpl<AndroidProjectDefinition<LibraryExtension>>(
    location,
    projectDefinition,
    namespace,
    buildWriter,
    parentBuild
), AndroidLibraryProject {

    override fun <R> withApk(apkSelector: ApkSelector, action: Apk.() -> R): R{
        if (apkSelector.testName == null) {
            error("Querying a non test APK from a library project.")
        }
        return super.withApk(apkSelector, action)
    }

    override fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit) {
        if (apkSelector.testName == null) {
            error("Querying a non test APK from a library project.")
        }
        super.assertApk(apkSelector, action)
    }

    override fun hasApk(apkSelector: ApkSelector): Boolean {
        if (apkSelector.testName == null) {
            error("Querying a non test APK from a library project.")
        }
        return super.hasApk(apkSelector)
    }

    override fun <R> withAar(aarSelector: AarSelector, action: Aar.() -> R): R {
        val path = computeOutputPath(aarSelector)
        if (!path.isRegularFile()) error("AAR file does not exist: $path")

        return Aar(path.toFile()).use {
            action(it)
        }
    }

    override fun assertAar(aarSelector: AarSelector, action: AarSubject.() -> Unit) {
        val path = computeOutputPath(aarSelector)
        if (!path.isRegularFile()) error("AAR file does not exist: $path")

        AarSubject.assertThat(Aar(path.toFile())).use {
            action(it)
        }
    }

    override fun hasAar(aarSelector: AarSelector): Boolean {
        return computeOutputPath(aarSelector).isRegularFile()
    }

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): AndroidLibraryProject =
        ReversibleAndroidLibraryProject(this, projectModification)
}

/**
 * Reversible version of [AndroidLibraryProject]
 */
internal class ReversibleAndroidLibraryProject(
    parentProject: AndroidLibraryProject,
    projectModification: TemporaryProjectModification
) : ReversibleAndroidProject<AndroidLibraryProject, AndroidProjectDefinition<LibraryExtension>>(
    parentProject,
    projectModification
), AndroidLibraryProject {

    override fun <R> withApk(apkSelector: ApkSelector, action: Apk.() -> R): R =
        parentProject.withApk(apkSelector, action)

    override fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit) {
        parentProject.assertApk(apkSelector, action)
    }

    override fun hasApk(apkSelector: ApkSelector): Boolean = parentProject.hasApk(apkSelector)

    override fun <R> withAar(aarSelector: AarSelector, action: Aar.() -> R): R =
        parentProject.withAar(aarSelector, action)

    override fun assertAar(aarSelector: AarSelector, action: AarSubject.() -> Unit) {
        parentProject.assertAar(aarSelector, action)
    }

    override fun hasAar(aarSelector: AarSelector): Boolean = parentProject.hasAar(aarSelector)
}
