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
import com.android.build.gradle.integration.common.fixture.project.AssetPackBundleDefinition
import com.android.build.gradle.integration.common.fixture.project.AssetPackBundleDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.AssetPackDefinition
import com.android.build.gradle.integration.common.fixture.project.AssetPackDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.FusedLibraryDefinition
import com.android.build.gradle.integration.common.fixture.project.FusedLibraryDefinitionImpl
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
import kotlin.io.path.writeText

internal class GradleBuildDefinitionImpl(
    override val name: String,
    internal val rootFolderName: String,
): GradleBuildDefinition {

    internal val settings = GradleSettingsDefinitionImpl()
    internal val includedBuilds = mutableMapOf<String, GradleBuildDefinitionImpl>()
    internal val rootProject = GenericProjectDefinitionImpl(":")
    internal val subProjects = mutableMapOf<String, GradleProjectDefinitionImpl>()

    internal val propertiesDelegate = GradlePropertiesDelegate()

    override var buildFileType: BuildFileType = BuildFileType.GROOVY
    override var useOldPluginStyleForSeparateClassloaders: Boolean = false

    internal lateinit var globalDefinitionState: GlobalDefinitionState

    override fun settings(action: GradleSettingsDefinition.() -> Unit) {
        action(settings)
    }

    override fun includedBuild(
        name: String,
        action: GradleBuildDefinition.() -> Unit
    ): GradleBuildDefinition {
        val build = includedBuilds.computeIfAbsent(name) {
            // for included builds, name and rootFolderName is always the same.
            GradleBuildDefinitionImpl(it, it)
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

    override fun assetPackBundle(
        path: String,
        createMinimumProject: Boolean,
        action: AssetPackBundleDefinition.() -> Unit
    ): AssetPackBundleDefinition {
        if (path == ":") throw RuntimeException("root project cannot be an asset pack bundle")

        val project = subProjects.computeIfAbsent(path) {
            AssetPackBundleDefinitionImpl(it, createMinimumProject)
        }

        project as? AssetPackBundleDefinition
            ?: errorOnWrongType(project, path, "Asset Pack Bundle")

        action(project)

        return project
    }

    override fun fusedLibrary(
        path: String,
        createMinimumProject: Boolean,
        action: FusedLibraryDefinition.() -> Unit
    ): FusedLibraryDefinition {
        if (path == ":") throw RuntimeException("root project cannot be a fused library")

        val project = subProjects.computeIfAbsent(path) {
            FusedLibraryDefinitionImpl(it, createMinimumProject)
        }

        project as? FusedLibraryDefinition
            ?: errorOnWrongType(project, path, "Fused Library")

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
        globalDefinitionState: GlobalDefinitionState,
    ) {
        location.createDirectories()
        this.globalDefinitionState = globalDefinitionState

        // gather all the custom binary plugin callbacks, and return whether we need to
        // include build logic in the settings file
        val customPluginMap = handleCustomBuildLogic(location)

        // gather all the plugins and all their versions so that the settings file can declare them as needed.
        // this is not needed if we use the old plugin style
        val allPlugins = if (useOldPluginStyleForSeparateClassloaders) mapOf() else computeAllPluginMap()

        // only give the repositories to the project if they need it.
        val repoForProjects = if (useOldPluginStyleForSeparateClassloaders) globalDefinitionState.repositories else listOf()

        writeSetting(location)

        // write all the projects
        rootProject.writeRoot(
            location,
            allPlugins,
            customPluginMap,
            useOldPluginStyleForSeparateClassloaders,
            repoForProjects,
            buildFileType.getNewWriter())

        subProjects.values.forEach {
            it.writeSubProject(
                location.resolveGradlePath(it.path),
                allPlugins,
                customPluginMap,
                useOldPluginStyleForSeparateClassloaders,
                repoForProjects,
                buildFileType.getNewWriter()
            )
        }

        writeProperties(location)

        // and the included builds
        includedBuilds.values.forEach {
            val newLocation = location.resolve(it.name)
            // computes a new GlobalDefinitionState for this build
            val globalState = GlobalDefinitionStateImpl(
                globalDefinitionState.additionalProperties,
                globalDefinitionState.repositories,
                it.handleCustomBuildLogic(newLocation)
            )

            it.write(newLocation,  globalState)
        }
    }

    internal fun writeSetting(location: Path) {
        settings.write(
            name = name,
            location = location,
            useOldPluginStyle = useOldPluginStyleForSeparateClassloaders,
            repositories = globalDefinitionState.repositories,
            includedBuildNames = includedBuilds.values.map { it.name},
            subProjectPaths = subProjects.values.map { it.path },
            buildWriter = buildFileType.getNewWriter(),
        )
    }

    internal fun writeProperties(location: Path) {

        val properties = globalDefinitionState.additionalProperties + propertiesDelegate.properties

        val gradlePropPath = location.resolve("gradle.properties")
        gradlePropPath.writeText(
            properties.joinToString(separator = System.lineSeparator(), prefix = System.lineSeparator(), postfix = System.lineSeparator())
        )
    }

    /**
     * Recursively write the local properties for this build and all included builds.
     *
     * This calls the provided action with the location of this build, and do the same for included builds
     *
     * @param parentFolder the root folder this build is in. this does not include the folder for the build itself.
     * @param writeAction the action that write the prop file, once provided with the folder of the build
     */
    internal fun createAncillaryBuildFiles(parentFolder: Path, writeAction: (Path) -> Unit) {
        val buildFolder = parentFolder.resolve(rootFolderName)
        writeAction(buildFolder)

        includedBuilds.values.forEach {
            it.createAncillaryBuildFiles(buildFolder, writeAction)
        }
    }

    /**
     * Computes the Plugin Version Map.
     *
     * This includes all the versions for all the plugins applied throughout the build.
     *
     * This allows each build file to know how the plugins must be applied. For instance if 2
     * projects have the same plugin in different versions then the version is specified in the
     * project build files. Otherwise, it's specified in the root project (with apply false).
     */
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
     * This method handles project with custom plugins applied to them via
     * [com.android.build.gradle.integration.common.fixture.project.plugins.PluginCallback]
     */
    internal fun handleCustomBuildLogic(location: Path): Map<String, Set<String>> {
        // gather all the custom callbacks. This returns a map from each callback class
        // to a list of all projects using this callback.
        val callbackMap = subProjects.values.asSequence()
            .flatMap { definition ->
                definition.pluginCallbacks.map {
                    it to definition.path
                }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        if (callbackMap.isEmpty()) return mapOf()

        // result to be used by the projects to apply their plugins.
        // The map is from the project path to the plugin class names.
        val pluginClassMap = mutableMapOf<String, Set<String>>()

        val handler = CustomBuildLogicHandler(location.resolve("build-logic.jar"))
        handler.use {
            // include all the plugin callbacks
            for ((callbackClass, paths) in callbackMap) {
                val pluginClassName = it.addCallback(callbackClass)

                // record this association, using the paths as keys since it'll be used
                // by each subproject
                paths.forEach { path ->
                    val nameList = pluginClassMap.computeIfAbsent(path) {
                        mutableSetOf()
                    } as MutableSet<String>
                    nameList.add(pluginClassName)
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
