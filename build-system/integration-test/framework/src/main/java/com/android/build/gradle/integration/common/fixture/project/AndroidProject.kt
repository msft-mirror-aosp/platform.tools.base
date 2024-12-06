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

import com.android.build.gradle.integration.common.fixture.ModelBuilderV2
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.DirectAndroidProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.testutils.apk.Apk
import java.nio.file.Path
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
internal abstract class AndroidProjectImpl<ProjectDefinitionT : GradleProjectDefinition>(
    location: Path,
    projectDefinition: ProjectDefinitionT,
    final override val namespace: String,
) : BaseAndroidProjectImpl<ProjectDefinitionT>(
    location,
    projectDefinition,
), AndroidProject<ProjectDefinitionT> {

    override val files: AndroidProjectFiles = DirectAndroidProjectFilesImpl(location, namespace)

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

    override fun reconfigure(buildFileOnly: Boolean, action: ProjectDefinitionT.() -> Unit) {
        val previousComponent = (projectDefinition as AndroidProjectDefinition<*>).componentCallback

        super.reconfigure(buildFileOnly, action)

        val newComponent = (projectDefinition as AndroidProjectDefinition<*>).componentCallback

        if (previousComponent != newComponent) {
            throw RuntimeException("Cannot change componentCallback in reconfigure")
        }
    }

    protected fun computeOutputPath(outputSelector: OutputSelector): Path {
        val root = if (outputSelector.fromIntermediates) {
            intermediatesDir
        } else {
            outputsDir
        }

        return root.resolve(outputSelector.getPath() + outputSelector.getFileName(location.name))
    }
}
