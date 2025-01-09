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

import com.android.SdkConstants
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import java.nio.file.Path
import kotlin.io.path.name

/**
 * a subproject part of a [GradleBuild], specifically for projects with Android plugins.
 */
interface BaseAndroidProject<ProjectDefinitionT : GradleProjectDefinition>
    : GradleProject<ProjectDefinitionT> {

    /** Return a File under the intermediates directory from Android plugins.  */
    fun getIntermediatePath(vararg paths: String?): Path

    /** Return the intermediates directory from Android plugins.  */
    val intermediatesDir: Path
    /** Return the generated directory from Android plugins.  */
    val generatedDir: Path
    /** Return the output directory from Android plugins.  */
    val outputsDir: Path
}

internal abstract class BaseAndroidProjectImpl<ProjectDefinitionT : GradleProjectDefinition>(
    location: Path,
    projectDefinition: ProjectDefinitionT,
) : GradleProjectImpl<ProjectDefinitionT>(
    location,
    projectDefinition,
), BaseAndroidProject<ProjectDefinitionT> {

    override val intermediatesDir: Path
        get() = location.resolve("build/${SdkConstants.FD_INTERMEDIATES}")

    override val generatedDir: Path
        get() = location.resolve("build/${SdkConstants.FD_GENERATED}")

    override val outputsDir: Path
        get() = location.resolve("build/${SdkConstants.FD_OUTPUTS}")

    override fun getIntermediatePath(vararg paths: String?): Path {
        return intermediatesDir.resolve(paths.joinToString(separator = "/"))
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

internal abstract class BaseReversibleAndroidProjectImpl<ProjectT : BaseAndroidProject<ProjectDefinitionT>, ProjectDefinitionT : GradleProjectDefinition>(
    parentProject: ProjectT,
    projectModification: TemporaryProjectModification
) : ReversibleGradleProject<ProjectT, ProjectDefinitionT>(
    parentProject,
    projectModification,
), BaseAndroidProject<ProjectDefinitionT> {
    override fun getIntermediatePath(vararg paths: String?): Path = parentProject.getIntermediatePath(*paths)

    override val intermediatesDir: Path
        get() = parentProject.intermediatesDir

    override val generatedDir: Path
        get() = parentProject.generatedDir

    override val outputsDir: Path
        get() = parentProject.outputsDir
}


