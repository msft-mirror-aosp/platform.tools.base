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

import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.ModelBuilderV2
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_TEST_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.GlobalDefinitionState
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.GradleSettingsDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.options.GradlePropertiesBuilder
import com.android.build.gradle.internal.ide.level2.JavaLibraryImpl
import java.nio.file.Path

/**
 * A Gradle Build on disk that can be run.
 *
 * It is also possible to query for subprojects and includedbuilds and add (non-build) files
 * and query for the content of their output folder
 */
interface GradleBuild {
    val directory: Path

    /** Queries for a project via its gradle path. The project must exist. */
    fun genericProject(path: String): GenericProject

    /**
     * Returns a project from the path.
     */
    fun subProject(path: String): GradleProject<*>

    /**
     * Queries for an application project via its gradle path.
     * The project must exist and be an Android Application project
     */
    fun androidApplication(path: String = DEFAULT_APP_PATH): AndroidApplicationProject
    /**
     * Queries for a library project via its gradle path.
     * The project must exist and be an Android Library project
     */
    fun androidLibrary(path: String = DEFAULT_LIB_PATH): AndroidLibraryProject
    /**
     * Queries for a feature project via its gradle path.
     * The project must exist and be an Android Dynamic Feature project
     */
    fun androidFeature(path: String = DEFAULT_FEATURE_PATH): AndroidDynamicFeatureProject
    /**
     * Queries for an android test project via its gradle path.
     * The project must exist and be an Android Test project
     */
    fun androidTest(path: String = DEFAULT_TEST_PATH): AndroidTestProject
    /**
     * Queries for a privacy sandbox sdk via its gradle path.
     * The project must exist and be a Privacy Sandbox SDK.
     */
    fun privacySandboxSdk(path: String): PrivacySandboxSdkProject
    /**
     * Queries for an AndroidX privacy sandbox library via its gradle path.
     * The project must exist and be a Privacy Sandbox Library.
     */
    fun androidXPrivacySandboxLibrary(path:String): AndroidLibraryProject
    /**
     * Queries for an AI pack project via its gradle path.
     * The project must exist and be an AI Pack project
     */
    fun aiPack(path: String): AiPackProject
    /**
     * Queries for an Asset pack project via its gradle path.
     * The project must exist and be an Asset Pack project
     */
    fun assetPack(path: String): AssetPackProject
    /**
     * Queries for an Asset pack bundle project via its gradle path.
     * The project must exist and be an Asset Pack project
     */
    fun assetPackBundle(path: String): AssetPackBundleProject
    /**
     * Queries for a Fused Library project via its gradle path.
     * The project must exist and be a Fused Library project
     */
    fun fusedLibrary(path: String): FusedLibraryProject

    /**
     * Queries for a Java Library project via its gradle path.
     * THe project must exist and be a Java Library project.
     */
    fun javaLibrary(path: String): JavaLibraryProject

    /**
     * Queries for a Kotlin multiplatform project via its gradle path.
     * The project must exist and be a Kotlin multiplatform project.
     */
    fun kotlinMultiplatformLibrary(path: String): KotlinMultiplatformProject

    /** Queries for an included build via its name. The build must exist. */
    fun includedBuild(name: String): GradleBuild

    /** The [GradleTaskExecutor] that can be used to run tasks */
    val executor: GradleTaskExecutor
    /** The [ModelBuilderV2] that can be used to query for models */
    val modelBuilder: ModelBuilderV2

    /**
     * Allows reconfiguring the settings
     *
     * This only rewrites the setting file, and does not change anything else
     */
    fun reconfigureSettings(action: GradleSettingsDefinition.() -> Unit)

    /**
     * Allows reconfiguring the Gradle Properties for the build.
     *
     * This only rewrites the gradle.properties file.
     */
    fun reconfigureGradleProperties(action: GradlePropertiesBuilder.() -> Unit)

    /**
     * Allows making modifications that are reverted.
     *
     * Any modifications to the project (using [GenericProject.files]) made from within the action,
     * using the provided instance of [GradleBuild], will be reverted after the action is run.
     */
    fun withReversibleModifications(action: (GradleBuild) -> Unit)

    /**
     * the location of the profile directory if profiling is on, otherwise null.
     *
     * Profiling is turned on via [GradleRuleBuilder.withProfileOutput]
     */
    val profileDirectory: Path?
}

/**
 * Basic implementation of some features of [GradleBuild] to be shared across the 2 different
 * implementations ([GradleBuildImpl] and [ReversibleGradleBuild]
 */
internal abstract class BaseGradleBuildImpl : GradleBuild {

    /**
     * a more complete list of projects to validate project types. This is separate
     * for the case of [ReversibleGradleBuild] where the subProject list is a clone of the
     * parent build.
     */
    internal abstract val subProjectsForValidation: Map<String, GradleProject<*>>

    override fun genericProject(path: String): GenericProject {
        val project = subProject(path)
        if (project is GenericProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not a generic project.
                Possible options are ${getProjectListByType<GenericProjectImpl>()}
            """.trimIndent()
        )
    }

    override fun androidApplication(path: String): AndroidApplicationProject {
        val project = subProject(path)
        if (project is AndroidApplicationProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android application project.
                Possible options are ${getProjectListByType<AndroidApplicationImpl>()}
            """.trimIndent()
        )
    }

    override fun androidLibrary(path: String): AndroidLibraryProject {
        val project = subProject(path)
        if (project is AndroidLibraryProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android library project.
                Possible options are ${getProjectListByType<AndroidLibraryImpl>()}
            """.trimIndent()
        )
    }

    override fun androidFeature(path: String): AndroidDynamicFeatureProject {
        val project = subProject(path)
        if (project is AndroidDynamicFeatureProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android dynamic feature project.
                Possible options are ${getProjectListByType<AndroidFeatureImpl>()}
            """.trimIndent()
        )
    }

    override fun androidTest(path: String): AndroidTestProject {
        val project = subProject(path)
        if (project is AndroidTestProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android test project.
                Possible options are ${getProjectListByType<AndroidTestImpl>()}
            """.trimIndent()
        )
    }

    override fun privacySandboxSdk(path: String): PrivacySandboxSdkProject {
        val project = subProject(path)
        if (project is PrivacySandboxSdkProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not a privacy sandbox SDK project.
                Possible options are ${getProjectListByType<PrivacySandboxSdkImpl>()}
            """.trimIndent()
        )
    }

    override fun androidXPrivacySandboxLibrary(path: String): AndroidLibraryProject {
        val project = subProject(path)
        if (project is AndroidLibraryProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Androids Privacy Sandbox Library or Android Library project.
                Possible options are ${getProjectListByType<AndroidLibraryImpl>()}
            """.trimIndent()
        )
    }

    override fun aiPack(path: String): AiPackProject {
        val project = subProject(path)
        if (project is AiPackProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an AI Pack project.
                Possible options are ${getProjectListByType<AiPackImpl>()}
            """.trimIndent()
        )
    }

    override fun assetPack(path: String): AssetPackProject {
        val project = subProject(path)
        if (project is AssetPackProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Asset Pack project.
                Possible options are ${getProjectListByType<AssetPackImpl>()}
            """.trimIndent()
        )
    }

    override fun assetPackBundle(path: String): AssetPackBundleProject {
        val project = subProject(path)
        if (project is AssetPackBundleProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Asset Pack Bundle project.
                Possible options are ${getProjectListByType<AssetPackBundleImpl>()}
            """.trimIndent()
        )
    }

    override fun fusedLibrary(path: String): FusedLibraryProject {
        val project = subProject(path)
        if (project is FusedLibraryProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not a Fused Library project.
                Possible options are ${getProjectListByType<FusedLibraryImpl>()}
            """.trimIndent()
        )
    }

    override fun javaLibrary(path: String): JavaLibraryProject {
        val project = subProject(path)
        if (project is JavaLibraryProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not a Java Library project.
                Possible options are ${getProjectListByType<JavaLibraryImpl>()}
            """.trimIndent()
        )
    }

    override fun kotlinMultiplatformLibrary(path: String): KotlinMultiplatformProject {
        val project = subProject(path)
        if (project is KotlinMultiplatformProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not a Kotlin Multiplatform Project with Android.
                Possible options are ${getProjectListByType<KotlinMultiplatformProjectImpl>()}
            """.trimIndent()
        )
    }

    internal inline fun <reified T> getProjectListByType(): String {
        return subProjectsForValidation.filter { it.key.javaClass == T::class.java }.map { it.key }.joinToString()
    }
}


/**
 * Internal default implementation of [GradleBuild]
 */
internal class GradleBuildImpl(
    override val directory: Path,
    private val subProjects: Map<String, GradleProject<*>> = mapOf(),
    private val includedBuilds: Map<String, GradleBuild> = mapOf(),
    private val definition: GradleBuildDefinitionImpl,
    private val executorProvider: () -> GradleTaskExecutor,
    private val modelBuilderProvider: () -> ModelBuilderV2,
    internal val mavenRepoPath: Path,
    override val profileDirectory: Path?
): BaseGradleBuildImpl() {

    override fun subProject(path: String): GradleProject<*> {
        return subProjects[path]
            ?: throw RuntimeException(
                """
                    Unable to find subproject '$path'.
                    Possible subProjects are ${subProjectsForValidation.keys.joinToString()}
                """.trimIndent()
            )
    }

    /**
     * For this implementation, both lists are the same.
     */
    override val subProjectsForValidation: Map<String, GradleProject<*>>
        get() = subProjects

    override fun includedBuild(name: String): GradleBuild {
        return includedBuilds[name]
            ?: throw RuntimeException(
                """
                    Unable to find includedBuild '$name'.
                    Possible includedBuilds are: ${includedBuilds.keys.joinToString()}
                """.trimIndent()
            )
    }

    override fun reconfigureSettings(action: GradleSettingsDefinition.() -> Unit) {
        action(definition.settings)
        definition.writeSetting(directory)
    }

    override fun reconfigureGradleProperties(action: GradlePropertiesBuilder.() -> Unit) {
        action(definition.propertiesDelegate)
        definition.writeProperties(directory)
    }

    internal fun computeAllPluginMap(): Map<PluginType, Set<String>> =
        definition.computeAllPluginMap()

    internal fun getGlobalDefinitionState(): GlobalDefinitionState =
        definition.globalDefinitionState

    internal val useOldPluginStyle: Boolean
        get() = definition.useOldPluginStyleForSeparateClassloaders

    /**
     * Runs the provided action with this build. At the end of the action, all file changes made
     * via [GradleProjectFiles] are reversed so that the build is the same as before this method
     */
    override fun withReversibleModifications(action: (GradleBuild) -> Unit) {
        TemporaryProjectModification(null).use {
            action(ReversibleGradleBuild(this, it))
        }
    }

    override val executor: GradleTaskExecutor
        get() = executorProvider()

    override val modelBuilder: ModelBuilderV2
        get() = modelBuilderProvider()

    internal fun getNewWriter(): BuildWriter = definition.buildFileType.getNewWriter()
}
