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
import com.android.build.gradle.integration.common.fixture.ModelBuilderV2
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.AabSubject
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.build.gradle.integration.common.utils.getApkLocations
import com.android.build.gradle.integration.common.utils.getVariantByName
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
 *
 * @param path the Gradle path of the project
 * @param createMinimumProject whether to initialized default values on required properties
 */
internal class AndroidApplicationDefinitionImpl(
    path: String,
    createMinimumProject: Boolean,
): AndroidProjectDefinitionImpl<ApplicationExtension>(path) {
    init {
        applyPlugin(PluginType.ANDROID_APP)
    }

    override val android: ApplicationExtension =
        DslProxy.createProxy(
            ApplicationExtension::class.java,
            contentHolder,
        ).also {
            if (createMinimumProject) {
                initDefaultValues(it)
            }
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

    fun getApkFromBundleTaskName(variantName: String): String
    fun locateApkFolderViaModel(variantName: String): File
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
    modelBuilder: () -> ModelBuilderV2,
    ) : AndroidProjectImpl<AndroidProjectDefinition<ApplicationExtension>>(
    location,
    projectDefinition,
    namespace,
    buildWriter,
    parentBuild,
    modelBuilder
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

    override fun getApkFromBundleTaskName(variantName: String): String {
        val projectPath = projectDefinition.path
        val model = modelBuilder().fetchModels().container.getProject(projectPath).androidProject
            ?: throw RuntimeException("Failed to get sync model for $projectPath module")

        val variantMainArtifact = model.getVariantByName(variantName).mainArtifact
        return variantMainArtifact.bundleInfo?.apkFromBundleTaskName
            ?: throw RuntimeException("Module $projectPath does not have apkFromBundle task name")
    }

    override fun locateApkFolderViaModel(variantName: String): File {
        val projectPath = projectDefinition.path

        val apkFiles = modelBuilder().fetchModels().container.getProject(projectPath).androidProject
            ?.getVariantByName(variantName)
            ?.getApkLocations()

        return apkFiles?.getOrNull(0)?.parentFile
            ?: throw RuntimeException("Failed to get apk folder for $projectPath module")
    }

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

    override fun getApkFromBundleTaskName(variantName: String): String =
        parentProject.getApkFromBundleTaskName(variantName)

    override fun locateApkFolderViaModel(variantName: String): File =
        parentProject.locateApkFolderViaModel(variantName)
}

