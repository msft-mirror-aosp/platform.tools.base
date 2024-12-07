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
import com.android.build.api.dsl.TestExtension
import com.android.build.gradle.integration.common.fixture.project.AiPackDefinition
import com.android.build.gradle.integration.common.fixture.project.AiPackDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.AndroidApplicationDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.AndroidDynamicFeatureDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.AndroidLibraryDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.AndroidTestDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.AssetPackDefinition
import com.android.build.gradle.integration.common.fixture.project.AssetPackDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.GenericProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.GenericProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.PrivacySandboxSdkDefinition
import com.android.build.gradle.integration.common.fixture.project.PrivacySandboxSdkDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.options.GradlePropertiesBuilder
import com.android.build.gradle.integration.common.fixture.project.options.GradlePropertiesDelegate
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.integration.common.fixture.testprojects.BuildFileType
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.testutils.MavenRepoGenerator
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories

internal class GradleBuildDefinitionImpl(buildName: String): GradleBuildDefinition {

    internal val settings = GradleSettingsDefinitionImpl()
    internal val includedBuilds = mutableMapOf<String, GradleBuildDefinitionImpl>()
    internal val rootProject = GenericProjectDefinitionImpl(":")
    internal val subProjects = mutableMapOf<String, GradleProjectDefinitionImpl>()

    private val propertiesDelegate = GradlePropertiesDelegate()

    override var name: String = buildName
        set(value) {
            field = value
            rootFolderName = value
        }
    override var rootFolderName: String = buildName
    override var buildFileType: BuildFileType = BuildFileType.GROOVY

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

    override fun rootProject(action: GenericProjectDefinition.() -> Unit) {
        action(rootProject)
    }

    override fun genericProject(
        path: String,
        action: GenericProjectDefinition.() -> Unit
    ): GenericProjectDefinition {
        val project = if (path == ":") {
            rootProject
        } else {
            subProjects.computeIfAbsent(path) { GenericProjectDefinitionImpl(it) }
        }

        project as? GenericProjectDefinition
            ?: errorOnWrongType(project, path, "Generic Project")

        action(project)

        return project
    }

    override fun androidApplication(
        path: String,
        createMinimumProject: Boolean,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ): AndroidProjectDefinition<ApplicationExtension> {
        if (path == ":") throw RuntimeException("root project cannot be an android project")

        val project = subProjects.computeIfAbsent(path) {
            AndroidApplicationDefinitionImpl(it, createMinimumProject).also {
                if (createMinimumProject) {
                    it.files.setupMinimumManifest()
                }
            }
        }

        project as? AndroidApplicationDefinitionImpl
            ?: errorOnWrongType(project, path, "Android Application")

        action(project)

        return project
    }

    override fun androidJavaApplication(
        path: String,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ): AndroidProjectDefinition<ApplicationExtension> = androidApplication(
        path,
        createMinimumProject = true,
        action
    ).also {
        HelloWorldAndroid.setupJava(it.files)
    }

    override fun androidKotlinApplication(
        path: String,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ): AndroidProjectDefinition<ApplicationExtension> {
        // kotlin plugin must be applied first (or you cannot access the kotlin {} block,
        // so order is important here.
        val app = androidApplication(path, createMinimumProject = true) {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        }
        action(app)

        // always do this last as it needs the final namespace value
        HelloWorldAndroid.setupKotlin(app.files)

        return app
    }

    override fun androidLibrary(
        path: String,
        createMinimumProject: Boolean,
        action: AndroidProjectDefinition<LibraryExtension>.() -> Unit
    ): AndroidProjectDefinition<LibraryExtension> {
        if (path == ":") throw RuntimeException("root project cannot be an android project")

        val project = subProjects.computeIfAbsent(path) {
            AndroidLibraryDefinitionImpl(it, createMinimumProject)
        }

        project as? AndroidLibraryDefinitionImpl
            ?: errorOnWrongType(project, path, "Android Library")

        action(project)

        return project
    }

    override fun androidFeature(
        path: String,
        createMinimumProject: Boolean,
        action: AndroidProjectDefinition<DynamicFeatureExtension>.() -> Unit
    ): AndroidProjectDefinition<DynamicFeatureExtension> {
        if (path == ":") throw RuntimeException("root project cannot be an android project")

        val project = subProjects.computeIfAbsent(path) {
            AndroidDynamicFeatureDefinitionImpl(it, createMinimumProject).also {
                if (createMinimumProject) {
                    it.files.setupMinimumManifest()
                }
            }
        }

        project as? AndroidDynamicFeatureDefinitionImpl
            ?: errorOnWrongType(project, path, "Android Dynamic Feature")

        action(project)

        return project
    }

    override fun androidTest(
        path: String,
        createMinimumProject: Boolean,
        action: AndroidProjectDefinition<TestExtension>.() -> Unit
    ): AndroidProjectDefinition<TestExtension> {
        if (path == ":") throw RuntimeException("root project cannot be an android project")

        val project = subProjects.computeIfAbsent(path) {
            AndroidTestDefinitionImpl(it, createMinimumProject).also {
                if (createMinimumProject) {
                    it.files.setupMinimumManifest()
                }
            }
        }

        project as? AndroidTestDefinitionImpl
            ?: errorOnWrongType(project, path, "Android Test")

        action(project)

        return project
    }

    override fun privacySandboxSdk(
        path: String,
        createMinimumProject: Boolean,
        action: PrivacySandboxSdkDefinition.() -> Unit
    ): PrivacySandboxSdkDefinition {
        if (path == ":") throw RuntimeException("root project cannot be a privacy sandbox sdk")

        val project = subProjects.computeIfAbsent(path) {
            PrivacySandboxSdkDefinitionImpl(it, createMinimumProject)
        }

        project as? PrivacySandboxSdkDefinitionImpl
            ?: errorOnWrongType(project, path, "Android Privacy Sandbox SDK")

        action(project)

        return project
    }

    override fun aiPack(
        path: String,
        action: AiPackDefinition.() -> Unit
    ): AiPackDefinition {
        if (path == ":") throw RuntimeException("root project cannot be an AI pack")

        val project = subProjects.computeIfAbsent(path) {
            AiPackDefinitionImpl(it)
        }

        project as? AiPackDefinition
            ?: errorOnWrongType(project, path, "Android AI Pack")

        action(project)

        return project
    }

    override fun assetPack(
        path: String,
        action: AssetPackDefinition.() -> Unit
    ): AssetPackDefinition {
        if (path == ":") throw RuntimeException("root project cannot be an asset pack")

        val project = subProjects.computeIfAbsent(path) {
            AssetPackDefinitionImpl(it)
        }

        project as? AssetPackDefinition
            ?: errorOnWrongType(project, path, "Asset Pack")

        action(project)

        return project
    }

    override fun gradleProperties(action: GradlePropertiesBuilder.() -> Unit) {
        action(propertiesDelegate)
    }

    private fun errorOnWrongType(
        project: GradleProjectDefinition,
        path: String,
        expectedType: String
    ): Nothing {
        val wrongType = when (project) {
            is AndroidApplicationDefinitionImpl -> "Android Application"
            is AndroidLibraryDefinitionImpl -> "Android Library"
            is AndroidDynamicFeatureDefinitionImpl -> "Android Dynamic Feature"
            is PrivacySandboxSdkDefinitionImpl -> "Android Privacy Sandbox SDK"
            else -> project.javaClass.name
        }

        throw RuntimeException("Attempting to create a module with path '$path' of type '$expectedType', but a module of type '$wrongType' already exists.")
    }

    internal fun write(
        location: Path,
        repositories: Collection<Path>?,
    ) {
        location.createDirectories()

        // gather all the custom binary plugin callbacks, and return whether we need to
        // include build logic in the settings file
        val customPluginMap = handleCustomBuildLogic(location)

        // gather all the plugins and all their versions so that the settings file can declare them as needed.
        val allPlugins = computeAllPluginMap()

        writeSetting(location, repositories, buildFileType.getNewWriter())

        // write all the projects
        rootProject.writeRoot(location, allPlugins, customPluginMap, buildFileType.getNewWriter())
        subProjects.values.forEach {
            it.writeSubProject(
                location.resolveGradlePath(it.path),
                buildFileOnly = false,
                allPlugins,
                customPluginMap,
                buildFileType.getNewWriter()
            )
        }

        // and the included builds
        includedBuilds.values.forEach {
            it.write(location.resolve(it.name), repositories)
        }
    }

    internal fun writeSetting(
        location: Path,
        repositories: Collection<Path>?,
        buildWriter: BuildWriter
    ) {
        settings.write(
            name = name,
            location = location,
            repositories = repositories,
            includedBuildNames = includedBuilds.values.map { it.name},
            subProjectPaths = subProjects.values.map { it.path },
            buildWriter = buildWriter,
        )
    }

    /**
     * Recursively write the local proper for this build and all included builds.
     *
     * This calls the provided action with the location of this build, and do the same for included builds
     *
     * @param parentFolder the root folder this build is in. this does not include the folder for the build itself.
     * @param writeAction the action that write the prop file, once provided with the folder of the build
     */
    internal fun createAncillaryBuildFiles(parentFolder: Path, writeAction: (Path, List<String>) -> Unit) {
        val buildFolder = parentFolder.resolve(rootFolderName)
        writeAction(buildFolder, propertiesDelegate.properties)

        includedBuilds.values.forEach {
            it.createAncillaryBuildFiles(buildFolder, writeAction)
        }
    }

    internal fun computeAllPluginMap(): Map<PluginType, Set<String>> {
        val allPlugins = mutableMapOf<PluginType, Set<String>>()
        (subProjects.values + rootProject).forEach { project ->
            project.plugins.forEach { entry ->
                val set = allPlugins.computeIfAbsent(entry.plugin) {
                    mutableSetOf()
                } as MutableSet<String>

                set.add(entry.version)
            }
        }
        return allPlugins
    }

    internal fun gatherInlineLibraries(): List<MavenRepoGenerator.Library> =
        includedBuilds.values.flatMap { it.gatherInlineLibraries() } +
                subProjects.values.flatMap { it.dependencies.externalLibraries }

    /**
     * This method handles project with custom plugins applied to them via [AndroidComponentCallback]
     */
    private fun handleCustomBuildLogic(location: Path): Map<String, String> {
        // gather all the custom callbacks. This returns a map from each callback class
        // to a list of all projects using this callback.
        val callbackMap = subProjects.values.asSequence()
            .map { definition ->
                definition.pluginCallback?.let {
                    it to definition.path
                }
            }
            .filterNotNull()
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        if (callbackMap.isEmpty()) return mapOf()

        // result to be used by the projects to apply their plugins.
        // The map is from the project path to the plugin class name.
        val pluginClassMap = mutableMapOf<String, String>()

        val handler = CustomBuildLogicHandler(location.resolve("build-logic.jar"))
        handler.use {
            // include all the plugin callbacks
            for ((callbackClass, paths) in callbackMap) {
                val pluginClassName = it.addCallback(callbackClass)

                // record this association, using the paths as keys since it'll be used
                // by each subproject
                paths.forEach { path ->
                    pluginClassMap[path] = pluginClassName
                }
            }
        }

        return pluginClassMap
    }
}

private fun Path.resolveGradlePath(path: String): Path {
    val relativePath = if (path.startsWith(':')) path.substring(1) else path

    return resolve(relativePath.replace(':', File.separatorChar))
}
