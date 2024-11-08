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

/**
 * A version of [GradleBuild] that can reverse the changes made during a test.
 *
 * Use with [GradleBuild.withReversibleModifications]
 */
internal class ReversibleGradleBuild(
    private val parentBuild: GradleBuild,
    private val projectModification: TemporaryProjectModification,
): GradleBuild {

    private val modifiableSubProject = mutableMapOf<String, ReversibleGradleProject>()
    private val wrappedIncludedBuild = mutableMapOf<String, ReversibleGradleBuild>()

    override fun subProject(name: String): GradleProject {
        val project = parentBuild.subProject(name)

        return modifiableSubProject.computeIfAbsent(name) {
            if (project is AndroidProject) {
                ReversibleAndroidProject(project, projectModification.delegate(project))
            } else {
                ReversibleGradleProject(project, projectModification.delegate(project))
            }
        }
    }

    override fun subProjectAsAndroid(name: String): AndroidProject {
        val project = subProject(name)
        if (project is AndroidProject) return project

        throw RuntimeException("Project with path '$name' is not an Android project.")
    }

    override fun includedBuild(name: String): GradleBuild {
        val b = includedBuild(name)
        return wrappedIncludedBuild.computeIfAbsent(name) {
            ReversibleGradleBuild(b, projectModification.delegate(null))
        }
    }

    override val executor: GradleTaskExecutor
        get() = parentBuild.executor

    override fun withReversibleModifications(action: (GradleBuild) -> Unit) {
        throw RuntimeException("Cannot nest withReversibleModifications")
    }
}
