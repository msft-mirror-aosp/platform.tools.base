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

import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.BaseGradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.BaseGradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.DirectGradleProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import java.io.File
import java.nio.file.Path

/**
 * a subproject part of a [GradleBuild].
 *
 * This class represents non Android projects that don't have their own custom interfaces
 *
 */
interface GradleProject: BaseGradleProject<GradleProjectDefinition> {
    /** the object that allows to add/update/remove files from the project */
    val files: GradleProjectFiles
}

/**
 * Base interface for all projects, including but not limited to
 * [GradleProject] and [AndroidProject].
 */
interface BaseGradleProject<out ProjectDefinitionT : BaseGradleProjectDefinition>: TemporaryProjectModification.FileProvider {
    /** the location on disk of the project */
    val location: Path

    /**
     * Reconfigure the project, and writes the result on disk right away
     *
     * This is useful to make "edits" to the build file during a test.
     *
     * This can also be used to update [GradleProjectFiles], but when only touching project files
     * (and not the build files) consider using [GradleProject.files] directly instead
     *
     * @param buildFileOnly whether to only update the build files, or do a full reset, including files added via [BaseGradleProjectDefinition.files]
     * @param action the action to configure the [BaseGradleProjectDefinition]
     *
     */
    fun reconfigure(buildFileOnly: Boolean = false, action: ProjectDefinitionT.() -> Unit)
}


/**
 * Default implementation of [GradleProject]
 */
internal class GradleProjectImpl(
    location: Path,
    projectDefinition: GradleProjectDefinition,
    buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
) : BaseGradleProjectImpl<GradleProjectDefinition>(
    location,
    projectDefinition,
    buildWriter,
    parentBuild
), GradleProject {

    override val files: GradleProjectFiles = DirectGradleProjectFilesImpl(location)

     override fun getReversibleInstance(projectModification: TemporaryProjectModification): GradleProject =
        ReversibleGradleProject(this, projectModification.delegate(this))
}

/**
 * Base implementation for all [BaseGradleProject]
 */
internal abstract class BaseGradleProjectImpl<ProjectDefinitionT : BaseGradleProjectDefinition>(
    final override val location: Path,
    protected val projectDefinition: ProjectDefinitionT,
    private val buildWriter: () -> BuildWriter,
    protected val parentBuild: GradleBuildDefinitionImpl,
) : BaseGradleProject<ProjectDefinitionT> {

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

        (projectDefinition as BaseGradleProjectDefinitionImpl)
            .writeSubProject(location, buildFileOnly, allPlugins, buildWriter)
    }

    abstract fun getReversibleInstance(projectModification: TemporaryProjectModification): BaseGradleProject<ProjectDefinitionT>
}

