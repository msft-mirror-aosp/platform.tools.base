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
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import java.nio.file.Path

/**
 * A Gradle Build on disk that can be run.
 *
 * It is also possible to query for subprojects and includedbuilds and add (non-build) files
 * and query for the content of their output folder
 */
interface GradleBuild {

    /** Queries for a project via its gradle path. The project must exist. */
    fun genericProject(path: String): GradleProject

    /**
     * Queries for an application project via its gradle path.
     * The project must exist and be an Android Application project
     */
    fun androidApplication(path: String): AndroidApplicationProject
    /**
     * Queries for a library project via its gradle path.
     * The project must exist and be an Android Library project
     */
    fun androidLibrary(path: String): AndroidLibraryProject
    /**
     * Queries for a feature project via its gradle path.
     * The project must exist and be an Android Dynamic Feature project
     */
    fun androidFeature(path: String): AndroidDynamicFeatureProject
    /**
     * Queries for a privacy sandbox sdk via its gradle path.
     * The project must exist and be a Privacy Sandbox SDK.
     */
    fun privacySandboxSdk(path: String): AndroidPrivacySandboxSdkProject
    /**
     * Queries for an AI pack project via its gradle path.
     * The project must exist and be an AI Pack project
     */
    fun androidAiPack(path: String): AndroidAiPackProject

    /** Queries for an included build via its name. The build must exist. */
    fun includedBuild(name: String): GradleBuild

    /** The [GradleTaskExecutor] that can be used to run tasks */
    val executor: GradleTaskExecutor
    /** The [ModelBuilderV2] that can be used to query for models */
    val modelBuilder: ModelBuilderV2

    /**
     * Allows making modifications that are reverted.
     *
     * Any modifications to the project (using [GradleProject.files]) made from within the action,
     * using the provided instance of [GradleBuild], will be reverted after the action is run.
     */
    fun withReversibleModifications(action: (GradleBuild) -> Unit)
}

/**
 * Basic implementation of some features of [GradleBuild] to be shared across the 2 different
 * implementations ([GradleBuildImpl] and [ReversibleGradleBuild]
 */
internal abstract class BaseGradleBuildImpl : GradleBuild {

    /**
     * Returns a project from the path.
     */
    abstract fun subProject(path: String): BaseGradleProject<*>

    /**
     * a more complete list of projects to validate project types. This is separate
     * for the case of [ReversibleGradleBuild] where the subProject list is a clone of the
     * parent build.
     */
    internal abstract val subProjectsForValidation: Map<String, BaseGradleProject<*>>

    override fun genericProject(path: String): GradleProject {
        val project = subProject(path)
        if (project is GradleProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not a generic project.
                Possible options are ${getProjectListByType<GradleProjectImpl>()}
            """.trimIndent()
        )
    }

    override fun androidApplication(path: String): AndroidApplicationProject {
        val project = subProject(path)
        if (project is AndroidApplicationProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android project.
                Possible options are ${getProjectListByType<AndroidApplicationImpl>()}
            """.trimIndent()
        )
    }

    override fun androidLibrary(path: String): AndroidLibraryProject {
        val project = subProject(path)
        if (project is AndroidLibraryProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android project.
                Possible options are ${getProjectListByType<AndroidLibraryImpl>()}
            """.trimIndent()
        )
    }

    override fun androidFeature(path: String): AndroidDynamicFeatureProject {
        val project = subProject(path)
        if (project is AndroidDynamicFeatureProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android project.
                Possible options are ${getProjectListByType<AndroidFeatureImpl>()}
            """.trimIndent()
        )
    }

    override fun privacySandboxSdk(path: String): AndroidPrivacySandboxSdkProject {
        val project = subProject(path)
        if (project is AndroidPrivacySandboxSdkProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android project.
                Possible options are ${getProjectListByType<AndroidPrivacySandboxSdkImpl>()}
            """.trimIndent()
        )
    }

    override fun androidAiPack(path: String): AndroidAiPackProject {
        val project = subProject(path)
        if (project is AndroidAiPackProject) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android project.
                Possible options are ${getProjectListByType<AndroidAiPackImpl>()}
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
    val directory: Path,
    private val subProjects: Map<String, BaseGradleProject<*>> = mapOf(),
    private val includedBuilds: Map<String, GradleBuild> = mapOf(),
    private val executorProvider: () -> GradleTaskExecutor,
    private val modelBuilderProvider: () -> ModelBuilderV2,
): BaseGradleBuildImpl() {

    override fun subProject(path: String): BaseGradleProject<*> {
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
    override val subProjectsForValidation: Map<String, BaseGradleProject<*>>
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
}
