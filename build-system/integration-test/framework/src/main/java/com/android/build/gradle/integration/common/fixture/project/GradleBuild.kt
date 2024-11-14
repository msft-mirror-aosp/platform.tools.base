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
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.api.dsl.LibraryExtension
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
    fun subProject(path: String): GradleProject
    fun androidProject(path: String): AndroidProject<CommonExtension<*,*,*,*,*,*>>
    fun androidApplication(path: String): AndroidProject<ApplicationExtension>
    fun androidLibrary(path: String): AndroidProject<LibraryExtension>
    fun androidFeature(path: String): AndroidProject<DynamicFeatureExtension>
    fun includedBuild(name: String): GradleBuild

    val executor: GradleTaskExecutor
    val modelBuilder: ModelBuilderV2

    fun withReversibleModifications(action: (GradleBuild) -> Unit)
}

/**
 * Internal default implementation of [GradleBuild]
 */
internal class GradleBuildImpl(
    val directory: Path,
    private val subProjects: Map<String, GradleProject> = mapOf(),
    private val includedBuilds: Map<String, GradleBuild> = mapOf(),
    private val executorProvider: () -> GradleTaskExecutor,
    private val modelBuilderProvider: () -> ModelBuilderV2,
) : GradleBuild {

    override fun subProject(path: String): GradleProject {
        return subProjects[path]
            ?: throw RuntimeException(
                """
                    Unable to find subproject '$path'.
                    Possible subProjects are ${subProjects.keys.joinToString()}
                """.trimIndent()
            )
    }

    override fun androidProject(path: String): AndroidProject<CommonExtension<*,*,*,*,*,*>> {
        val project = subProjects[path]

        // cannot check for exact type due to type erasure, so check for simpler type and cast
        @Suppress("UNCHECKED_CAST")
        if (project is AndroidProjectImpl<*>) return project as AndroidProject<CommonExtension<*,*,*,*,*,*>>

        throw RuntimeException(
            """
                Project with path '$path' is not an Android project.
                Possible androidProjects are ${getProjectListByType<AndroidProject<*>>()}
            """.trimIndent()
        )
    }

    override fun androidApplication(path: String): AndroidProject<ApplicationExtension> {
        val project = subProjects[path]
        if (project is AndroidApplicationImpl) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android project.
                Possible androidApplications are ${getProjectListByType<AndroidApplicationImpl>()}
            """.trimIndent()
        )
    }

    override fun androidLibrary(path: String): AndroidProject<LibraryExtension> {
        val project = subProjects[path]
        if (project is AndroidLibraryImpl) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android project.
                Possible androidLibraries are ${getProjectListByType<AndroidLibraryImpl>()}
            """.trimIndent()
        )
    }

    override fun androidFeature(path: String): AndroidProject<DynamicFeatureExtension> {
        val project = subProjects[path]
        if (project is AndroidFeatureImpl) return project

        throw RuntimeException(
            """
                Project with path '$path' is not an Android project.
                Possible androidFeatures are ${getProjectListByType<AndroidFeatureImpl>()}
            """.trimIndent()
        )
    }

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

    internal inline fun <reified T> getProjectListByType(): String {
        return subProjects.filter { it.key.javaClass == T::class.java }.map { it.key }.joinToString()
    }
}
