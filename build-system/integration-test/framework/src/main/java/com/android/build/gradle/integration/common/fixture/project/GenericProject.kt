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
import com.android.build.gradle.integration.common.fixture.project.builder.BaseGradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.DirectGradleProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import java.nio.file.Path

/*
 * Support for Generic gradle Projects in the [GradleRule] fixture
 */

/**
 * Represents a Gradle Project that can be configured before being written on disk.
 *
 * This class represents non Android projects that don't have their own custom interfaces
 */
interface GenericProjectDefinition: BaseGradleProjectDefinition {
    /** executes the lambda that adds/updates/removes files from the project */
    fun files(action: GradleProjectFiles.() -> Unit)
}

/**
 * Default implementation for [GenericProjectDefinition]
 */
internal open class GenericProjectDefinitionImpl(path: String): BaseGradleProjectDefinitionImpl(path),
    GenericProjectDefinition {

    override fun applyPlugin(type: PluginType, version: String?, applyFirst: Boolean) {
        if (type.isAndroid) {
            throw RuntimeException("Do not use genericProject for Android Plugins")
        }
        super.applyPlugin(type, version, applyFirst)
    }

    override fun replaceAppliedPlugin(type: PluginType, version: String) {
        if (type.isAndroid) {
            throw RuntimeException("Do not use genericProject for Android Plugins")
        }
        super.replaceAppliedPlugin(type, version)
    }

    override val files: GradleProjectFiles = GradleProjectFilesImpl()

    override fun files (action: GradleProjectFiles.() -> Unit) {
        action(files)
    }
}

/**
 * a subproject part of a [GradleBuild].
 *
 * This class represents non Android projects that don't have their own custom interfaces
 *
 */
interface GenericProject: BaseGradleProject<GenericProjectDefinition> {
    /** the object that allows to add/update/remove files from the project */
    val files: GradleProjectFiles
}

/**
 * Default implementation of [GenericProject]
 */
internal class GenericProjectImpl(
    location: Path,
    projectDefinition: GenericProjectDefinition,
    buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
) : BaseGradleProjectImpl<GenericProjectDefinition>(
    location,
    projectDefinition,
    buildWriter,
    parentBuild
), GenericProject {


    override val files: GradleProjectFiles = DirectGradleProjectFilesImpl(location)

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): GenericProject =
        ReversibleGenericProject(this, projectModification.delegate(this))
}

/**
 * a version of [GenericProject] that can reverses the changes made during a test.
 *
 * Returned by [ReversibleGradleBuild] when used with [GradleBuild.withReversibleModifications]
 *
 * This is simply a wrapper on a normal [GenericProject] object, that replaces the [GradleProjectFiles]
 * with [ReversibleProjectFiles]
 */
internal open class ReversibleGenericProject(
    parentProject: GenericProject,
    projectModification: TemporaryProjectModification,
): BaseReversibleGradleProject<GenericProject, GenericProjectDefinition>(parentProject), GenericProject {
    override val files: GradleProjectFiles = ReversibleProjectFiles(projectModification)
}
