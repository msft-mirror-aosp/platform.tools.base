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

package com.android.build.gradle.integration.common.fixture.project.builder

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.integration.common.fixture.testprojects.GradlePropertiesBuilder
import com.android.build.gradle.integration.common.fixture.testprojects.GradlePropertiesBuilderImpl
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Represents a Gradle Build that can be configured before being written on disk
 */
interface GradleBuildDefinition {
    val name: String

    fun settings(action: GradleSettingsDefinition.() -> Unit)

    fun includedBuild(name: String, action: GradleBuildDefinition.() -> Unit): GradleBuildDefinition

    /**
     * Configures the root project. This cannot be an Android Project.
     */
    fun rootProject(action: GradleProjectDefinition.() -> Unit)

    /**
     * Configures a subProject, creating it if needed.
     */
    fun subProject(path: String, action: GradleProjectDefinition.() -> Unit): GradleProjectDefinition

    /**
     * Configures a subProject with the Android Application plugin, creating it if needed.
     */
    fun androidApplication(
        path: String,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ): AndroidProjectDefinition<ApplicationExtension>

    /**
     * Configures a subProject with the Android Library plugin, creating it if needed.
     */
    fun androidLibrary(
        path: String,
        action: AndroidProjectDefinition<LibraryExtension>.() -> Unit
    ): AndroidProjectDefinition<LibraryExtension>

    /**
     * Configures a subProject with the Android Dynamic Feature plugin, creating it if needed.
     */
    fun androidFeature(path: String, action: AndroidProjectDefinition<DynamicFeatureExtension>.() -> Unit): AndroidProjectDefinition<DynamicFeatureExtension>

    fun gradleProperties(action: GradlePropertiesBuilder.() -> Unit)

    fun mavenRepository(action: MavenRepository.() -> Unit)
}

internal class GradleBuildDefinitionImpl(override val name: String): GradleBuildDefinition {

    internal val settings = GradleSettingsDefinitionImpl()
    internal val includedBuilds = mutableMapOf<String, GradleBuildDefinitionImpl>()
    internal val rootProject = GradleProjectDefinitionImpl(":")
    internal val subProjects = mutableMapOf<String, BaseGradleProjectDefinitionImpl>()

    private val gradlePropertiesBuilder = GradlePropertiesBuilderImpl()

    override fun settings(action: GradleSettingsDefinition.() -> Unit) {
        action(settings)
    }

    override fun includedBuild(
        name: String,
        action: GradleBuildDefinition.() -> Unit
    ): GradleBuildDefinition {
        val build = includedBuilds.computeIfAbsent(name) {
            GradleBuildDefinitionImpl(it)
        }
        action(build)

        return build
    }

    override fun rootProject(action: GradleProjectDefinition.() -> Unit) {
        action(rootProject)
    }

    override fun subProject(
        path: String,
        action: GradleProjectDefinition.() -> Unit
    ): GradleProjectDefinition {
        if (path == ":") return rootProject

        val project = subProjects.computeIfAbsent(path) {
            GradleProjectDefinitionImpl(it)
        }

        val configurableProject = when (project) {
            is AndroidApplicationDefinitionImpl -> project.asGradleProject()
            is GradleProjectDefinitionImpl -> project
            else -> throw RuntimeException("Unexpected type of BaseGradleProjectDefinitionIpl: ${project.javaClass.name}")
        }

        action(configurableProject)

        return configurableProject
    }

    override fun androidApplication(
        path: String,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ): AndroidProjectDefinition<ApplicationExtension> {
        if (path == ":") throw RuntimeException("root project cannot be an android project")

        val project = subProjects.computeIfAbsent(path) {
            AndroidApplicationDefinitionImpl(it)
        }

        // Attempting to create a module $path of type Application, but a module $path of type XXX already exists.

        @Suppress("UNCHECKED_CAST")
        project as? AndroidProjectDefinition<ApplicationExtension>
            ?: errorOnWrongType(project, path, "Android Application")
        action(project)

        return project
    }

    override fun androidLibrary(
        path: String,
        action: AndroidProjectDefinition<LibraryExtension>.() -> Unit
    ): AndroidProjectDefinition<LibraryExtension> {
        if (path == ":") throw RuntimeException("root project cannot be an android project")

        val project = subProjects.computeIfAbsent(path) {
            AndroidLibraryDefinitionImpl(it)
        }

        @Suppress("UNCHECKED_CAST")
        project as? AndroidProjectDefinition<LibraryExtension>
            ?: errorOnWrongType(project, path, "Android Library")

        action(project)

        return project
    }

    override fun androidFeature(
        path: String,
        action: AndroidProjectDefinition<DynamicFeatureExtension>.() -> Unit
    ): AndroidProjectDefinition<DynamicFeatureExtension> {
        if (path == ":") throw RuntimeException("root project cannot be an android project")

        val project = subProjects.computeIfAbsent(path) {
            AndroidDynamicFeatureDefinitionImpl(it)
        }

        @Suppress("UNCHECKED_CAST")
        project as? AndroidProjectDefinition<DynamicFeatureExtension>
            ?: errorOnWrongType(project, path, "Android Dynamic Feature")

        action(project)

        return project
    }

    private fun errorOnWrongType(
        project: BaseGradleProjectDefinition,
        path: String,
        expectedType: String
    ): Nothing {
        val wrongType = when (project) {
            is AndroidApplicationDefinitionImpl -> "Android Application"
            is AndroidLibraryDefinitionImpl -> "Android Library"
            is AndroidDynamicFeatureDefinitionImpl -> "Android Dynamic Feature"
            else -> project.javaClass.name
        }

        throw RuntimeException("Attempting to create a module with path '$path' of type '$expectedType', but a module of type '$wrongType' already exists.")
    }

    override fun gradleProperties(action: GradlePropertiesBuilder.() -> Unit) {
        action(gradlePropertiesBuilder)
    }

    override fun mavenRepository(action: MavenRepository.() -> Unit) {
        throw RuntimeException("todo")
    }

    internal fun write(
        location: Path,
        repositories: Collection<Path>,
        buildWriter: () -> BuildWriter,
    ) {
        location.createDirectories()

        // gather all the plugins and all their versions so that the settings file can declare them as needed.
        val allPlugins = computeAllPluginMap()

        // write settings with the list of plugins
        settings.write(
            location = location,
            repositories = repositories,
            includedBuildNames = includedBuilds.values.map { it.name},
            subProjectPaths = subProjects.values.map { it.path },
            buildWriter = buildWriter,
        )

        // write all the projects
        rootProject.writeRoot(location, allPlugins, buildWriter)
        subProjects.values.forEach {
            it.writeSubProject(
                location.resolveGradlePath(it.path),
                buildFileOnly = false,
                allPlugins,
                buildWriter
            )
        }

        // and the included builds
        includedBuilds.values.forEach {
            it.write(location.resolve(it.name), repositories, buildWriter)
        }
    }

    internal fun computeAllPluginMap(): Map<PluginType, Set<String>> {
        val allPlugins = mutableMapOf<PluginType, Set<String>>()
        (subProjects.values + rootProject).forEach { project ->
            project.plugins.entries.forEach { entry ->
                val set = allPlugins.computeIfAbsent(entry.key) {
                    mutableSetOf()
                } as MutableSet<String>

                set.add(entry.value)
            }
        }
        return allPlugins
    }
}

private fun Path.resolveGradlePath(path: String): Path {
    val relativePath = if (path.startsWith(':')) path.substring(1) else path

    return resolve(relativePath.replace(':', File.separatorChar))
}
