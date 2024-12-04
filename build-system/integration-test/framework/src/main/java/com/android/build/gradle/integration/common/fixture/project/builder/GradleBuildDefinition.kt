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
import com.android.build.gradle.integration.common.fixture.project.AiPackDefinition
import com.android.build.gradle.integration.common.fixture.project.AiPackDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.AndroidApplicationDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.AndroidDynamicFeatureDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.AndroidLibraryDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.AssetPackDefinition
import com.android.build.gradle.integration.common.fixture.project.AssetPackDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.GenericProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.GenericProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.PrivacySandboxSdkDefinition
import com.android.build.gradle.integration.common.fixture.project.PrivacySandboxSdkDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.plugins.AndroidComponentCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
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
    fun rootProject(action: GenericProjectDefinition.() -> Unit)

    /**
     * Configures a subProject, creating it if needed.
     */
    fun genericProject(path: String, action: GenericProjectDefinition.() -> Unit): GenericProjectDefinition

    /**
     * Configures a subProject with the Android Application plugin, creating it if needed.
     *
     * if the project is already created, `createMinimumProject` has no effect
     *
     * @param path the Gradle path of the project
     * @param createMinimumProject whether to create a minimum project (namespace, compileSdk, manifest)
     */
    fun androidApplication(
        path: String = DEFAULT_APP_PATH,
        createMinimumProject: Boolean = true,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ): AndroidProjectDefinition<ApplicationExtension>

    /**
     * Configures a subProject with the Android Application plugin, creating it if needed
     *
     * This also creates some basic content: activity (java), manifest, layout
     */
    fun androidJavaApplication(
        path: String = DEFAULT_APP_PATH,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ): AndroidProjectDefinition<ApplicationExtension>

    /**
     * Configures a subProject with the Android Application plugin, creating it if needed
     *
     * This also creates some basic content: activity (kotlin, manifest, layout
     */
    fun androidKotlinApplication(
        path: String = DEFAULT_APP_PATH,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ): AndroidProjectDefinition<ApplicationExtension>

    /**
     * Configures a subProject with the Android Library plugin, creating it if needed.
     *
     * if the project is already created, `createMinimumProject` has no effect
     *
     * @param path the Gradle path of the project
     * @param createMinimumProject whether to create a minimum project (namespace, compileSdk, manifest)
     */
    fun androidLibrary(
        path: String = DEFAULT_LIB_PATH,
        createMinimumProject: Boolean = true,
        action: AndroidProjectDefinition<LibraryExtension>.() -> Unit
    ): AndroidProjectDefinition<LibraryExtension>

    /**
     * Configures a subProject with the Android Dynamic Feature plugin, creating it if needed.
     *
     * if the project is already created, `createMinimumProject` has no effect
     *
     * @param path the Gradle path of the project
     * @param createMinimumProject whether to create a minimum project (namespace, compileSdk, manifest)
     */
    fun androidFeature(
        path: String = DEFAULT_FEATURE_PATH,
        createMinimumProject: Boolean = true,
        action: AndroidProjectDefinition<DynamicFeatureExtension>.() -> Unit
    ): AndroidProjectDefinition<DynamicFeatureExtension>

    /**
     * Configures a subProject with the Android Privacy Sandbox SDK plugin, creating it if needed.
     *
     * if the project is already created, `createMinimumProject` has no effect
     *
     * @param path the Gradle path of the project
     * @param createMinimumProject whether to create a minimum project (namespace, compileSdk, manifest)
     */
    fun privacySandboxSdk(
        path: String,
        createMinimumProject: Boolean = true,
        action: PrivacySandboxSdkDefinition.() -> Unit
    ): PrivacySandboxSdkDefinition

    /**
     * Configures a subProject with the Android AI Pack plugin, creating it if needed.
     */
    fun aiPack(
        path: String,
        action: AiPackDefinition.() -> Unit
    ): AiPackDefinition

    /**
     * Configures a subProject with the Android Asset Pack plugin, creating it if needed.
     */
    fun assetPack(
        path: String,
        action: AssetPackDefinition.() -> Unit
    ): AssetPackDefinition

    /**
     * Configures a maven repositories with custom artifacts
     */
    fun mavenRepository(action: MavenRepository.() -> Unit)
}

internal class GradleBuildDefinitionImpl(override val name: String): GradleBuildDefinition {

    internal val settings = GradleSettingsDefinitionImpl()
    internal val includedBuilds = mutableMapOf<String, GradleBuildDefinitionImpl>()
    internal val rootProject = GenericProjectDefinitionImpl(":")
    internal val subProjects = mutableMapOf<String, GradleProjectDefinitionImpl>()

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
        if (path == ":") return rootProject

        val project = subProjects.computeIfAbsent(path) {
            GenericProjectDefinitionImpl(it)
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

    override fun mavenRepository(action: MavenRepository.() -> Unit) {
        throw RuntimeException("todo")
    }

    internal fun write(
        location: Path,
        repositories: Collection<Path>,
        buildWriter: () -> BuildWriter,
    ) {
        location.createDirectories()

        // gather all the custom binary plugin callbacks, and return whether we need to
        // include build logic in the settings file
        val customPluginMap = handleCustomBuildLogic(location)

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
        rootProject.writeRoot(location, allPlugins, customPluginMap, buildWriter)
        subProjects.values.forEach {
            it.writeSubProject(
                location.resolveGradlePath(it.path),
                buildFileOnly = false,
                allPlugins,
                customPluginMap,
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
            project.plugins.forEach { entry ->
                val set = allPlugins.computeIfAbsent(entry.plugin) {
                    mutableSetOf()
                } as MutableSet<String>

                set.add(entry.version)
            }
        }
        return allPlugins
    }

    /**
     * This method handles project with custom plugins applied to them via [AndroidComponentCallback]
     */
    private fun handleCustomBuildLogic(location: Path): Map<String, String> {
        // gather all the custom callbacks. This returns a map from each callback class
        // to a list of all projects using this callback.
        val callbackMap = subProjects.asSequence()
            .map { it.value }
            .filterIsInstance(AndroidProjectDefinition::class.java)
            .filter { it.componentCallback != null }
            .map { definition ->
                definition.componentCallback?.let {
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
