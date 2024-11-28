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
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.BaseGradleProjectDefinition
import java.nio.file.Path

/**
 * a version of [AndroidProject] that can reverses the changes made during a test.
 *
 * Returned by [ReversibleGradleBuild] when used with [GradleBuild.withReversibleModifications]
 */
internal open class ReversibleAndroidProject<ProjectT: AndroidProject<ProjectDefinitionT>, ProjectDefinitionT : BaseGradleProjectDefinition>(
    parentProject: ProjectT,
    projectModification: TemporaryProjectModification,
) : BaseReversibleGradleProject<ProjectT, ProjectDefinitionT>(
    parentProject,
), AndroidProject<ProjectDefinitionT> {

    override val namespace: String
        get() = parentProject.namespace

    override val files: AndroidProjectFiles =
        ReversibleAndroidProjectFiles(parentProject.namespace, projectModification)

    override fun getIntermediateFile(vararg paths: String?): Path = parentProject.getIntermediateFile(*paths)

    override val intermediatesDir: Path
        get() = parentProject.intermediatesDir

    override val outputsDir: Path
        get() = parentProject.outputsDir
}

internal class ReversibleAndroidProjectFiles(
    override val namespace: String,
    projectModification: TemporaryProjectModification
): ReversibleProjectFiles(projectModification), AndroidProjectFiles {
    override val namespaceAsPath: String
        get() = namespaceAsPath.replace('.', '/')
}


