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
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import java.io.File
import java.nio.file.Path

/**
 * Base interface for all projects, including but not limited to
 * [GenericProject] and [AndroidProject].
 */
interface GradleProject<out ProjectDefinitionT : GradleProjectDefinition>: TemporaryProjectModification.FileProvider {
    /** the location on disk of the project */
    val location: Path

    /**
     * Reconfigure the project, and writes the result on disk right away
     *
     * This is useful to make "edits" to the build file during a test.
     *
     * This can also be used to update [GradleProjectFiles], but when only touching project files
     * (and not the build files) consider using [GenericProject.files] directly instead
     *
     * @param buildFileOnly whether to only update the build files, or do a full reset, including files added via [GradleProjectDefinition.files]
     * @param action the action to configure the [GradleProjectDefinition]
     *
     */
    fun reconfigure(buildFileOnly: Boolean = false, action: ProjectDefinitionT.() -> Unit)
}

/**
 * Base implementation for all [GradleProject]
 */
internal abstract class GradleProjectImpl<ProjectDefinitionT : GradleProjectDefinition>(
    final override val location: Path,
    protected val projectDefinition: ProjectDefinitionT,
    private val buildWriter: () -> BuildWriter,
    protected val parentBuild: GradleBuildDefinitionImpl,
    protected val modelBuilder: () -> ModelBuilderV2,
) : GradleProject<ProjectDefinitionT> {

    override fun file(path: String): File? {
        return location.resolve(path).toFile()
    }

    override fun reconfigure(
        buildFileOnly: Boolean,
        action: ProjectDefinitionT.() -> Unit
    ) {
        action(projectDefinition)

        // we need to query the other projects for their plugins
        val allPlugins = parentBuild.computeAllPluginMap()

        (projectDefinition as GradleProjectDefinitionImpl)
            .writeSubProject(location, buildFileOnly, allPlugins, mapOf(), buildWriter)
    }

    abstract fun getReversibleInstance(projectModification: TemporaryProjectModification): GradleProject<ProjectDefinitionT>
}

