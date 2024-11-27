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
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.DirectAndroidProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.truth.ApkSubject
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

    /** Return a File under the intermediates directory from Android plugins.  */
    fun getIntermediateFile(vararg paths: String?): Path

    /** Return the intermediates directory from Android plugins.  */
    val intermediatesDir: Path
    /** Return the output directory from Android plugins.  */
    val outputsDir: Path
}

interface GeneratesApk {
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
     * Returns whether the APK exists.
     *
     * To assert validity, prefer using
     * ```
     * project.assertApk(ApkSelector.DEBUG) {
     *   exists()
     * }
     * ```
     */
    fun hasApk(apkSelector: ApkSelector): Boolean
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

    /**
     * Implementation of apk related function in the base class so it can be shared by
     * subclasses. Only the concerned type expose it in their interfaces.
     */
    open fun <T> withApk(apkSelector: ApkSelector, action: Apk.() -> T): T {
        val path = computeOutputPath(apkSelector)
        if (!path.isRegularFile()) error("APK file does not exist: $path")

        return Apk(path.toFile()).use {
            action(it)
        }
    }

    open fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit) {
        withApk(apkSelector) {
            ApkSubject.assertThat(this).use {
                action(it)
            }
        }
    }

    open fun hasApk(apkSelector: ApkSelector): Boolean =
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

