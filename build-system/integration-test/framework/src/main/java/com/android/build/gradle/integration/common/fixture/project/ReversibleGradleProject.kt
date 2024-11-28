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
import com.android.build.gradle.integration.common.fixture.project.builder.BaseGradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import java.io.File
import java.nio.file.Path

/**
 * a version of [GradleProject] that can reverses the changes made during a test.
 *
 * Returned by [ReversibleGradleBuild] when used with [GradleBuild.withReversibleModifications]
 *
 * This is simply a wrapper on a normal [GradleProject] object, that replaces the [GradleProjectFiles]
 * with [ReversibleProjectFiles]
 */
internal open class ReversibleGradleProject(
    parentProject: GradleProject,
    projectModification: TemporaryProjectModification,
): BaseReversibleGradleProject<GradleProject, GradleProjectDefinition>(parentProject), GradleProject {
    override val files: GradleProjectFiles = ReversibleProjectFiles(projectModification)
}

/**
 * Base Class for all reversible projects
 */
abstract class BaseReversibleGradleProject<ProjectT : BaseGradleProject<ProjectDefinitionT>, ProjectDefinitionT : BaseGradleProjectDefinition>(
    protected open val parentProject: ProjectT,
) : BaseGradleProject<ProjectDefinitionT> {

    override val location: Path
        get() = parentProject.location

    override fun reconfigure(buildFileOnly: Boolean, action: ProjectDefinitionT.() -> Unit) {
        throw RuntimeException("Cannot reconfigure inside a reconfiguration")
    }

    override fun file(path: String): File? {
        return parentProject.file(path)
    }
}


internal open class ReversibleProjectFiles(
    private val projectModification: TemporaryProjectModification,
): GradleProjectFiles {
    override fun add(relativePath: String, content: String) {
        projectModification.addFile(relativePath, content)
    }

    override fun update(relativePath: String, action: (String) -> String) {
        projectModification.modifyFile(relativePath, action)
    }

    override fun remove(relativePath: String) {
        projectModification.removeFile(relativePath)
    }
}

