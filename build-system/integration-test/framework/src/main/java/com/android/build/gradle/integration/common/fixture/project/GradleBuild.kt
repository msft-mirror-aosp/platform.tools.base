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
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import java.nio.file.Path

/**
 * A Gradle Build on disk that can be run.
 *
 * It is also possible to query for subprojects and includedbuilds and add (non-build) files
 * and query for the content of their output folder
 */
interface GradleBuild {
    fun subProject(name: String): GradleProject
    fun subProjectAsAndroid(name: String): AndroidProject
    fun includedBuild(name: String): GradleBuild

    val executor: GradleTaskExecutor

    fun withReversibleModifications(action: (GradleBuild) -> Unit)
}

/**
 * Internal default implementation of [GradleBuild]
 */
internal class GradleBuildImpl(
    val directory: Path,
    private val subProjects: Map<String, GradleProject> = mapOf(),
    private val includedBuilds: Map<String, GradleBuild> = mapOf(),
    private val executorProvider: () -> GradleTaskExecutor
) : GradleBuild {

    override fun subProject(name: String): GradleProject {
        return subProjects[name] ?: throw RuntimeException("Unable to find subproject '$name'")
    }

    override fun subProjectAsAndroid(name: String): AndroidProject {
        val project = subProjects[name]
        if (project is AndroidProjectImpl) return project

        throw RuntimeException("Project with path '$name' is not an Android project.")
    }

    override fun includedBuild(name: String): GradleBuild {
        return includedBuilds[name] ?: throw RuntimeException("Unable to find includedBuild '$name'")
    }

    override fun withReversibleModifications(action: (GradleBuild) -> Unit) {
        TemporaryProjectModification(null).use {
            action(ReversibleGradleBuild(this, it))
        }
    }

    override val executor: GradleTaskExecutor
        get() = executorProvider()
}
