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
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.DirectAndroidProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.truth.AabSubject
import com.android.build.gradle.integration.common.truth.AarSubject
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.testutils.apk.Aab
import com.android.testutils.apk.Aar
import com.android.testutils.apk.Apk
import com.android.tools.build.bundletool.model.AppBundle
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * a subproject part of a [GradleBuild], specifically for projects with Android plugins that have
 * namespace, the android extension, and the androidComponent extension
 */
interface AndroidProject<ProjectDefinitionT : GradleProjectDefinition>
    : BaseAndroidProject<ProjectDefinitionT> {

    /**
     * The namespace of the project.
     */
    val namespace: String

    /** the object that allows to add/update/remove files from the project */
    val files: AndroidProjectFiles
}

/**
 * a [GradleProject] that generates apk
 */
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
 * a [GradleProject] that generates aar
 */
interface GeneratesAar {
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
 * a [GradleProject] that generates app bundle (aab)
 */
interface GeneratesAab {
    /**
     * Runs the action with a provided instance of [Aab].
     *
     * It is possible to return a value from the action, but it should not be [Aab] as this
     * may not be safe. [Aab] is a [AutoCloseable] and should be treated as such.
     */
    fun <R> withAab(aabSelector: AabSelector, action: Aab.() -> R): R

    /**
     * Runs the action with a provided [ZipSubject]
     */
    fun assertAab(aabSelector: AabSelector, action: AabSubject.() -> Unit)

    /**
     * Runs the action with the a provided instance of [AppBundle].
     *
     * This gives more access to the content than [Aab].
     */
    fun <R> withAppBundle(aabSelector: AabSelector, action: AppBundle.() -> R): R
}

/**
 * Default implementation of [AndroidProject]
 */
internal abstract class AndroidProjectImpl<ProjectDefinitionT : GradleProjectDefinition>(
    location: Path,
    projectDefinition: ProjectDefinitionT,
    final override val namespace: String,
) : BaseAndroidProjectImpl<ProjectDefinitionT>(
    location,
    projectDefinition,
), AndroidProject<ProjectDefinitionT> {

    override val files: AndroidProjectFiles = DirectAndroidProjectFiles(location, namespace)
}

/**
 * Base class to handle project output.
 */
open class BaseGenerateDelegate(protected val location: Path) {
    protected val intermediatesDir: Path
        get() = location.resolve("build/${SdkConstants.FD_INTERMEDIATES}")

    protected val outputsDir: Path
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

/**
 * Delegate implementation for [GeneratesApk]
 */
class GeneratesApkDelegate(location: Path): BaseGenerateDelegate(location), GeneratesApk {
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
}

/**
 * Implementation of [GeneratesApk] that just delegates to another instance
 */
class GeneratesApkFromParentDelegate(private val parent: GeneratesApk): GeneratesApk {

    override fun <R> withApk(apkSelector: ApkSelector, action: Apk.() -> R): R =
        parent.withApk(apkSelector, action)

    override fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit) {
        parent.assertApk(apkSelector, action)
    }

    override fun hasApk(apkSelector: ApkSelector): Boolean {
        return parent.hasApk(apkSelector)
    }
}

/**
 * Delegate implementation for [GeneratesAar]
 */
class GeneratesAarDelegate(location: Path): BaseGenerateDelegate(location), GeneratesAar {

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

/**
 * Implementation of [GeneratesAar] that just delegates to another instance
 */
class GeneratesAarFromParentDelegate(private val parent: GeneratesAar): GeneratesAar {

    override fun <R> withAar(aarSelector: AarSelector, action: Aar.() -> R): R =
        parent.withAar(aarSelector, action)

    override fun assertAar(aarSelector: AarSelector, action: AarSubject.() -> Unit) {
        parent.assertAar(aarSelector, action)
    }

    override fun hasAar(aarSelector: AarSelector): Boolean = parent.hasAar(aarSelector)
}

/**
 * Delegate implementation for [GeneratesAab]
 */
class GeneratesAabDelegate(location: Path): BaseGenerateDelegate(location), GeneratesAab {

    override fun <R> withAab(aabSelector: AabSelector, action: Aab.() -> R): R {
        val path = computeOutputPath(aabSelector)
        if (!path.isRegularFile()) error("Bundle file does not exist: $path")

        return Aab(path.toFile()).use {
            action(it)
        }
    }

    override fun assertAab(aabSelector: AabSelector, action: AabSubject.() -> Unit) {
        withAab(aabSelector) {
            AabSubject.assertThat(this).use {
                action(it)
            }
        }
    }

    override fun <R> withAppBundle(aabSelector: AabSelector, action: AppBundle.() -> R): R {
        val path = computeOutputPath(aabSelector)
        if (!path.isRegularFile()) error("Bundle file does not exist: $path")

        return ZipFile(path.toFile()).use { zip ->
            action(AppBundle.buildFromZip(zip))
        }
    }
}

/**
 * Implementation of [GeneratesAab] that just delegates to another instance
 */
class GeneratesAabFromParentDelegate(private val parent: GeneratesAab): GeneratesAab {
    override fun <R> withAab(aabSelector: AabSelector, action: Aab.() -> R): R =
        parent.withAab(aabSelector, action)

    override fun assertAab(aabSelector: AabSelector, action: AabSubject.() -> Unit) {
        parent.assertAab(aabSelector, action)
    }

    override fun <R> withAppBundle(aabSelector: AabSelector, action: AppBundle.() -> R): R =
        parent.withAppBundle(aabSelector, action)
}
