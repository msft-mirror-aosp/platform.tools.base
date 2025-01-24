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
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import java.nio.file.Path
import kotlin.io.path.writeBytes

/*
 * Support for Generic gradle Projects in the [GradleRule] fixture
 */

/**
 * Represents a Gradle Project that can be configured before being written on disk.
 *
 * This class represents non Android projects that don't have their own custom interfaces
 */
interface GenericProjectDefinition: GradleProjectDefinition {
    /** executes the lambda that adds/updates/removes files from the project */
    fun files(action: GradleProjectFiles.() -> Unit)

    /**
     * Wraps a library binary with a module
     */
    fun wrap(library: ByteArray, fileName: String)
}

/**
 * Default implementation for [GenericProjectDefinition]
 */
internal open class GenericProjectDefinitionImpl(path: String): GradleProjectDefinitionImpl(path),
    GenericProjectDefinition {

    private val wrappedLibraries = mutableListOf<Pair<String, ByteArray>>()

    override fun applyPlugin(type: PluginType, version: String?, applyFirst: Boolean) {
        if (type.isAndroid) {
            throw RuntimeException("Do not use genericProject for Android Plugins")
        }
        super.applyPlugin(type, version, applyFirst)
    }

    override fun <T> applyPlugin(
        type: PluginType.PluginTypeWithExtension<T>,
        version: String?,
        applyFirst: Boolean,
        action: (T.() -> Unit)?
    ) {
        if (type.isAndroid) {
            throw RuntimeException("Do not use genericProject for Android Plugins")
        }
        super.applyPlugin(type, version, applyFirst, action)
    }

    override fun replaceAppliedPlugin(type: PluginType, version: String) {
        if (type.isAndroid) {
            throw RuntimeException("Do not use genericProject for Android Plugins")
        }
        super.replaceAppliedPlugin(type, version)
    }

    override fun files (action: GradleProjectFiles.() -> Unit) {
        action(files)
    }

    override fun wrap(library: ByteArray, fileName: String) {
        wrappedLibraries.add(fileName to library)
    }

    override fun writeExtension(writer: BuildWriter, location: Path) {
        if (wrappedLibraries.isEmpty()) return

        writer.method("configurations.create", "default")

        for ((fileName, libraryBinary) in wrappedLibraries) {
            location.resolve(fileName).writeBytes(libraryBinary)
            writer.method("artifacts.add", listOf("default", writer.rawMethod("file", fileName)), isVarArg = false)
        }
    }
}

/**
 * a subproject part of a [GradleBuild].
 *
 * This class represents non Android projects that don't have their own custom interfaces
 *
 */
interface GenericProject: GradleProject<GenericProjectDefinition>

/**
 * Default implementation of [GenericProject]
 */
internal class GenericProjectImpl(
    location: Path,
    projectDefinition: GenericProjectDefinition,
) : GradleProjectImpl<GenericProjectDefinition>(
    location,
    projectDefinition,
), GenericProject {

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
): ReversibleGradleProject<GenericProject, GenericProjectDefinition>(
    parentProject,
    projectModification
), GenericProject
