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
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.DirectAndroidProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.project.builder.WriterProvider
import com.android.build.gradle.integration.common.truth.AarSubject
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.testutils.apk.Aar
import com.android.testutils.apk.Apk
import com.android.utils.combineAsCamelCase
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * a subproject part of a [GradleBuild], specifically for projects with Android plugins.
 */
interface AndroidProject<T: CommonExtension<*, *, *, *, *, *>>: GradleProject {
    val namespace: String
    override val files: AndroidProjectFiles

    /**
     * Reconfigure the project, and writes the result on disk right away
     *
     * This is useful to make "edits" to the build file during a test.
     *
     * This can also be used to update [AndroidProjectFiles], but when only touching project files
     * (and not the build files) consider using [AndroidProject.files] instead
     */
    fun reconfigure(buildFileOnly: Boolean = false, action: AndroidProjectDefinition<T>.() -> Unit)

    fun <R> withApk(apkSelector: ApkSelector, action: Apk.() -> R): R
    fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit)
    fun hasApk(apkSelector: ApkSelector): Boolean

    fun <R> withAar(aarSelector: AarSelector, action: Aar.() -> R): R
    fun assertAar(aarSelector: AarSelector, action: AarSubject.() -> Unit)
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
internal abstract class AndroidProjectImpl<T: CommonExtension<*, *, *, *, *, *>>(
    location: Path,
    final override val namespace: String,
): GradleProjectImpl(location), AndroidProject<T> {

    protected abstract val projectDefinition: AndroidProjectDefinition<T>

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

        val path = if (outputSelector.hasDimensionInPath) {
            // path is apk/flavors/buildType/
            // where flavors is flavor1Flavor2Flavor3, etc...
            if (outputSelector.flavors.isEmpty()) {
                "${outputSelector.outputType}/${outputSelector.buildType}"
            } else {
                "${outputSelector.outputType}/${outputSelector.flavors.combineAsCamelCase()}/${outputSelector.buildType}"
            }
        } else {
            outputSelector.outputType
        }

        return root.resolve(path).resolve(outputSelector.getFileName(location.name))
    }
}

internal class AndroidApplicationImpl(
    location: Path,
    override val projectDefinition: AndroidProjectDefinition<ApplicationExtension>,
    namespace: String,
    private val writerProvider: WriterProvider,
): AndroidProjectImpl<ApplicationExtension>(location, namespace) {

    override fun reconfigure(
        buildFileOnly: Boolean,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ) {
        action(projectDefinition)

        projectDefinition as AndroidProjectDefinitionImpl<ApplicationExtension>
        projectDefinition.writeSubProject(location, buildFileOnly, writerProvider)
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
    private val writerProvider: WriterProvider,
): AndroidProjectImpl<LibraryExtension>(location, namespace) {

    override fun reconfigure(
        buildFileOnly: Boolean,
        action: AndroidProjectDefinition<LibraryExtension>.() -> Unit
    ) {
        action(projectDefinition)

        projectDefinition as AndroidProjectDefinitionImpl<LibraryExtension>
        projectDefinition.writeSubProject(location, buildFileOnly, writerProvider)
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
    private val writerProvider: WriterProvider,
): AndroidProjectImpl<DynamicFeatureExtension>(location, namespace) {

    override fun reconfigure(
        buildFileOnly: Boolean,
        action: AndroidProjectDefinition<DynamicFeatureExtension>.() -> Unit
    ) {
        action(projectDefinition)

        projectDefinition as AndroidProjectDefinitionImpl<DynamicFeatureExtension>
        projectDefinition.writeSubProject(location, buildFileOnly, writerProvider)
    }

    override fun <R> withAar(aarSelector: AarSelector, action: Aar.() -> R): R {
        error("AAR unavailable from Android Dynamic Feature project")
    }

    override fun assertAar(aarSelector: AarSelector, action: AarSubject.() -> Unit) {
        error("AAR unavailable from Android Application project")
    }

    override fun hasAar(aarSelector: AarSelector): Boolean {
        error("AAR unavailable from Android Dynamic Feature project")
    }
}

