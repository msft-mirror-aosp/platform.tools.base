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

/**
 * A version of [GradleBuild] that can reverse the changes made during a test.
 *
 * when [GradleBuild.withReversibleModifications] is called, the current [GradleBuild] is wrapped
 * with this class and the action interacts with the wrapped version.
 *
 * The implementation of the wrapper simply replaces the implementation of [GradleProjectFiles] with
 * one that records the changes so that they can be reverted.
 *
 */
internal class ReversibleGradleBuild(
    private val parentBuild: GradleBuildImpl,
    private val projectModification: TemporaryProjectModification,
): GradleBuild {

    private val modifiableSubProject = mutableMapOf<String, ReversibleGradleProject>()
    private val wrappedIncludedBuild = mutableMapOf<String, ReversibleGradleBuild>()

    override fun subProject(path: String): GradleProject {
        val project = parentBuild.subProject(path)

        return modifiableSubProject.computeIfAbsent(path) {
            if (project is AndroidProject<*>) {
                ReversibleAndroidProject(project, projectModification.delegate(project))
            } else {
                ReversibleGradleProject(project, projectModification.delegate(project))
            }
        }
    }

    override fun androidProject(path: String): AndroidProject<CommonExtension<*, *, *, *, *, *>> {
        val project = subProject(path)

        // cannot check for exact type due to type erasure, so check for simpler type and cast
        @Suppress("UNCHECKED_CAST")
        if (project is AndroidProjectImpl<*>) return project as AndroidProject<CommonExtension<*,*,*,*,*,*>>

        throw RuntimeException(
            """
                Project with path '$path' is not an Android project.
                Possible androidProjects are ${parentBuild.getProjectListByType<AndroidProject<*>>()}
            """.trimIndent()
        )
    }

    override fun androidApplication(path: String): AndroidProject<ApplicationExtension> {
        throw UnsupportedOperationException("Call androidProject() during modification as reconfiguration is not possible")
    }

    override fun androidLibrary(path: String): AndroidProject<LibraryExtension> {
        throw UnsupportedOperationException("Call androidProject() during modification as reconfiguration is not possible")
    }

    override fun androidFeature(path: String): AndroidProject<DynamicFeatureExtension> {
        throw UnsupportedOperationException("Call androidProject() during modification as reconfiguration is not possible")
    }

    override fun includedBuild(name: String): GradleBuild {
        val b = parentBuild.includedBuild(name) as GradleBuildImpl
        return wrappedIncludedBuild.computeIfAbsent(name) {
            ReversibleGradleBuild(b, projectModification.delegate(null))
        }
    }

    override val executor: GradleTaskExecutor
        get() = parentBuild.executor

    override val modelBuilder: ModelBuilderV2
        get() = parentBuild.modelBuilder

    override fun withReversibleModifications(action: (GradleBuild) -> Unit) {
        throw RuntimeException("Cannot nest withReversibleModifications")
    }
}
