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

import com.android.SdkConstants
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.PrivacySandboxSdkExtension
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.DirectAndroidProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.truth.AarSubject
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.testutils.apk.Aar
import com.android.testutils.apk.Apk
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * a subproject part of a [GradleBuild], specifically for projects with Android plugins.
 */
interface AndroidProject<T>: GradleProject {

    /**
     * The namespace of the project.
     */
    val namespace: String

    /** the object that allows to add/update/remove files from the project */
    override val files: AndroidProjectFiles

    /**
     * Reconfigure the project, and writes the result on disk right away
     *
     * This is useful to make "edits" to the build file during a test.
     *
     * This can also be used to update [AndroidProjectFiles], but when only touching project files
     * (and not the build files) consider using [AndroidProject.files] instead
     *
     * @param buildFileOnly whether to only update the build files, or do a full reset, including files added via [AndroidProjectFiles]
     * @param action the action to configure the [AndroidProjectDefinition]
     *
     */
    fun reconfigure(buildFileOnly: Boolean = false, action: AndroidProjectDefinition<T>.() -> Unit)

    /**
     * Runs the action with a provided instance of [Apk].
     *
     * It is possible to return a value from the action, but it should not be [Apk] as this
     * may not be safe. [Apk] is a [AutoCloseable] and should be treated as such.
     */
    fun <R> withApk(apkSelector: ApkSelector, action: Apk.() -> R): R
    /**
     * Runs the action with a provided [ApkSubject]
     */
    fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit)

    /**
     * Returns whether or not the APK exists.
     *
     * To assert validity, prefer using
     * ```
     * project.assertApk(ApkSelector.DEBUG) {
     *   exists()
     * }
     * ```
     */
    fun hasApk(apkSelector: ApkSelector): Boolean

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


    /** Return a File under the intermediates directory from Android plugins.  */
    fun getIntermediateFile(vararg paths: String?): Path

    /** Return the intermediates directory from Android plugins.  */
    val intermediatesDir: Path
    /** Return the output directory from Android plugins.  */
    val outputsDir: Path
}

/**
 * Default implementation of [AndroidProject]
 */
internal abstract class AndroidProjectImpl<T>(
    location: Path,
    localProjectDefinition: AndroidProjectDefinition<T>,
    final override val namespace: String,
    parentBuild: GradleBuildDefinitionImpl,
): GradleProjectImpl(location, localProjectDefinition, parentBuild), AndroidProject<T> {

    override abstract val projectDefinition: AndroidProjectDefinition<T>

    override val files: AndroidProjectFiles = DirectAndroidProjectFilesImpl(location, namespace)

    override fun getIntermediateFile(vararg paths: String?): Path {
        return intermediatesDir.resolve(paths.joinToString(separator = "/"))
    }

    override fun <T> withApk(apkSelector: ApkSelector, action: Apk.() -> T): T {
        val path = computeOutputPath(apkSelector)
        if (!path.isRegularFile()) error("APK file does not exist: $path")

        return Apk(path.toFile()).use {
            action(it)
        }
    }

    override fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit) {
        withApk(apkSelector) {
            ApkSubject.assertThat(this).use {
                action(it)
            }
        }
    }

    override fun hasApk(apkSelector: ApkSelector): Boolean =
        computeOutputPath(apkSelector).isRegularFile()

    override val intermediatesDir: Path
        get() = location.resolve("build/${SdkConstants.FD_INTERMEDIATES}")

    override val outputsDir: Path
        get() = location.resolve("build/${SdkConstants.FD_OUTPUTS}")


    protected fun computeOutputPath(outputSelector: OutputSelector): Path {
        val root = if (outputSelector.fromIntermediates) {
            intermediatesDir
        } else {
            outputsDir
        }

        return root.resolve(outputSelector.getPath() + outputSelector.getFileName(location.name))
    }
}

internal class AndroidApplicationImpl(
    location: Path,
    override val projectDefinition: AndroidProjectDefinition<ApplicationExtension>,
    namespace: String,
    private val buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
): AndroidProjectImpl<ApplicationExtension>(location, projectDefinition, namespace, parentBuild) {

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

    override fun <T> withAar(aarSelector: AarSelector, action: Aar.() -> T): T {
        error("AAR unavailable from Android Application project")
    }

    override fun assertAar(aarSelector: AarSelector, action: AarSubject.() -> Unit) {
        error("AAR unavailable from Android Application project")
    }

    override fun hasAar(aarSelector: AarSelector): Boolean {
        error("AAR unavailable from Android Application project")
    }
}

internal class AndroidLibraryImpl(
    location: Path,
    override val projectDefinition: AndroidProjectDefinition<LibraryExtension>,
    namespace: String,
    private val buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
): AndroidProjectImpl<LibraryExtension>(location, projectDefinition, namespace, parentBuild) {

    override fun reconfigure(
        buildFileOnly: Boolean,
        action: AndroidProjectDefinition<LibraryExtension>.() -> Unit
    ) {
        action(projectDefinition)

        // we need to query the other projects for their plugins
        val allPlugins = parentBuild.computeAllPluginMap()

        projectDefinition as AndroidProjectDefinitionImpl<LibraryExtension>
        projectDefinition.writeSubProject(location, buildFileOnly, allPlugins, buildWriter)
    }

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
}

internal class AndroidFeatureImpl(
    location: Path,
    override val projectDefinition: AndroidProjectDefinition<DynamicFeatureExtension>,
    namespace: String,
    private val buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
): AndroidProjectImpl<DynamicFeatureExtension>(location, projectDefinition, namespace, parentBuild) {

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

    override fun <R> withAar(aarSelector: AarSelector, action: Aar.() -> R): R {
        error("AAR unavailable from Android Dynamic Feature project")
    }

    override fun assertAar(aarSelector: AarSelector, action: AarSubject.() -> Unit) {
        error("AAR unavailable from Android  Dynamic Feature project")
    }

    override fun hasAar(aarSelector: AarSelector): Boolean {
        error("AAR unavailable from Android Dynamic Feature project")
    }
}

internal class PrivacySandboxSdkImpl(
    location: Path,
    override val projectDefinition: AndroidProjectDefinition<PrivacySandboxSdkExtension>,
    namespace: String,
    private val buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
): AndroidProjectImpl<PrivacySandboxSdkExtension>(location, projectDefinition, namespace, parentBuild) {

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

    override fun <R> withAar(aarSelector: AarSelector, action: Aar.() -> R): R {
        error("AAR unavailable from Android Privacy Sandbox SDK project")
    }

    override fun assertAar(aarSelector: AarSelector, action: AarSubject.() -> Unit) {
        error("AAR unavailable from Android Privacy Sandbox SDK project")
    }

    override fun hasAar(aarSelector: AarSelector): Boolean {
        error("AAR unavailable from Android Privacy Sandbox SDK project")
    }
}


