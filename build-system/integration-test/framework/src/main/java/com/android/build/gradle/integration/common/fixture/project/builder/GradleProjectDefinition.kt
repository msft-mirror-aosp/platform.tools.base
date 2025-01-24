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

import com.android.build.gradle.integration.common.dependencies.JarBuilder
import com.android.build.gradle.integration.common.dependencies.JarBuilderImpl
import com.android.build.gradle.integration.common.fixture.dsl.DefaultDslRecorder
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.dsl.DslRecorder
import com.android.build.gradle.integration.common.fixture.dsl.ExtensionAwareDefinition
import com.android.build.gradle.integration.common.fixture.dsl.MethodReturnedFile
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType.PluginTypeWithExtension
import com.android.build.gradle.integration.common.fixture.project.plugins.PluginCallback
import com.google.common.annotations.VisibleForTesting
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Base interface for all project definition, including but not limited to
 * [GenericProjectDefinition] and [AndroidProjectDefinition].
 */
interface GradleProjectDefinition: ExtensionAwareDefinition {
    val path: String

    /**
     * Applies a plugin with an optional version string. If null, the default version is used.
     *
     * For core gradle plugin, the version should always be null.
     *
     * @param type the type of the plugin to apply
     * @param version the version of the plugin.
     * @param applyFirst if true, applies this plugin first, before other plugins
     */
    fun applyPlugin(type: PluginType, version: String? = null, applyFirst: Boolean = false)

    /**
     * Applies a plugin that has an associated extension, with an optional version string.
     * If null, the default version is used.
     *
     * For core gradle plugin, the version should always be null.
     *
     * Optionally, an action can be provided to configure the extension associated with the plugin
     *
     * @param type the type of the plugin to apply
     * @param version the version of the plugin.
     * @param applyFirst if true, applies this plugin first, before other plugins
     * @param action the action to configure the plugin's extension
     */
    fun <T> applyPlugin(
        type: PluginTypeWithExtension<T>,
        version: String? = null,
        applyFirst: Boolean = false,
        action: (T.() -> Unit)? = null)

    /**
     * Replaces an applied plugin, with a provided version
     *
     * This replaces the plugin in the same place as the previous one.
     */
    fun replaceAppliedPlugin(type: PluginType, version: String)

    /**
     * If a plugin was applied with a custom extension, then
     * this call can be called later to update the plugin configuration
     */
    fun <T> reconfigurePlugin(plugin: PluginTypeWithExtension<T>, action: T.() -> Unit)

    var group: String?
    var version: String?

    /** the object that allows to add/update/remove files from the project */
    val files: GradleProjectFiles

    /**
     * Configures dependencies of the project
     */
    fun dependencies(action: DependenciesBuilder.() -> Unit)
    val dependencies: DependenciesBuilder

    /**
     * The list of plugin callbacks for this project
     */
    val pluginCallbacks: MutableList<Class<out PluginCallback>>

    /**
     * Configures the buildscript for this project
     */
    fun buildscript(action: BuildscriptBuilder.() -> Unit)

    /**
     * returns a [File] that encodes the call to `project.file()`.
     *
     * This can be used to provide File instance into the DSL.
     */
    fun projectDotFile(relativePath: String): File
}

/**
 * a builder to configure the buildscript classpath of a project
 *
 * See [GradleProjectDefinition.buildscript]
 */
interface BuildscriptBuilder {

    /**
     * Adds a dependency to the buildscript classpath for this project
     */
    fun classpath(dependency: Any)

    /**
     * Creates a [LocalJarBuilder] to be passed to [classpath] or any other scope
     */
    fun localJar(name: String, action: JarBuilder.() -> Unit) : LocalJarDependency
}

internal data class AppliedPlugin(
    val plugin: PluginType,
    val version: String
)

private data class PluginExtensionData(
    val name: String,
    val dslRecorder: DslRecorder
)

/**
 * Implementation shared between [GenericProjectDefinition] and [AndroidProjectDefinition]
 */
internal abstract class GradleProjectDefinitionImpl(
    override val path: String
): GradleProjectDefinition {

    protected val dslRecorder = DefaultDslRecorder()

    // ordered list of applied plugins with their versions
    internal val plugins = mutableListOf<AppliedPlugin>()
    // map of plugins to custom extensions
    private val pluginExtensions = mutableMapOf<PluginType, PluginExtensionData>()

    private val buildscriptBuilder = BuildscriptBuilderImpl()

    override val files: GradleProjectFiles = DelayedGradleProjectFiles()

    override val pluginCallbacks: MutableList<Class<out PluginCallback>> = mutableListOf()

    override var group: String? = null
    override var version: String? = null

    override fun applyPlugin(type: PluginType, version: String?, applyFirst: Boolean) {
        if (type.isSettings) {
            throw RuntimeException("Cannot apply settings plugin to a project")
        }
        // search for existing one
        plugins.firstOrNull { it.plugin == type }?.let {
            throw RuntimeException("Plugin $type is already applied! (version: ${it.version}")
        }

        val appliedPlugin = AppliedPlugin(type, version ?: type.version ?: INTERNAL_PLUGIN_VERSION)
        if (applyFirst) {
            plugins.add(0, appliedPlugin)
        } else {
            plugins += appliedPlugin
        }
    }

    override fun <T> applyPlugin(
        type: PluginTypeWithExtension<T>,
        version: String?,
        applyFirst: Boolean,
        action: (T.() -> Unit)?
    ) {
        applyPlugin(type as PluginType, version, applyFirst)
        action?.let {
            val dslRecorder = DefaultDslRecorder()
            val proxy = DslProxy.createProxy( type.extensionType, dslRecorder)
            it(proxy)

            pluginExtensions[type] = PluginExtensionData(type.extensionName, dslRecorder)
        }
    }

    override fun replaceAppliedPlugin(type: PluginType, version: String) {
        if (type.isSettings) {
            throw RuntimeException("Cannot apply settings plugin to a project")
        }
        val match = plugins.firstOrNull { it.plugin == type }
            ?: throw RuntimeException("Plugin $type not yet applied")

        val appliedPlugin = AppliedPlugin(type, version)
        val index = plugins.indexOf(match)
        plugins[index] = appliedPlugin
    }

    override fun <T> reconfigurePlugin(plugin: PluginTypeWithExtension<T>, action: T.() -> Unit) {
        val data = pluginExtensions[plugin]
            ?: throw RuntimeException("Cannot reconfigurePlugin plugin $plugin has it has not yet been configured")

        val proxy = DslProxy.createProxy(plugin.extensionType, data.dslRecorder)
        action(proxy)
    }

    internal fun hasPlugin(plugin: PluginType): Boolean = plugins.any { it.plugin == plugin }

    override val dependencies: DependenciesBuilderImpl = DependenciesBuilderImpl()

    override fun dependencies(action: DependenciesBuilder.() -> Unit) {
        action(dependencies)
    }

    override fun buildscript(action: BuildscriptBuilder.() -> Unit) {
        action(buildscriptBuilder)
    }

    override fun projectDotFile(relativePath: String): File {
        return MethodReturnedFile("project.file", relativePath)
    }

    internal fun writeSubProject(
        location: Path,
        allPlugins: Map<PluginType, Set<String>>,
        customPluginMap: Map<String, Set<String>>,
        useOldPluginStyle: Boolean,
        projectRepositories: Collection<Path>,
        buildWriter: BuildWriter,
    ) {
        write(
            location,
            allPlugins,
            customPluginMap,
            isRoot = false,
            useOldPluginStyle,
            projectRepositories,
            buildWriter
        )
    }

    internal fun writeRoot(
        location: Path,
        allPlugins: Map<PluginType, Set<String>>,
        customPluginMap: Map<String, Set<String>>,
        useOldPluginStyle: Boolean,
        projectRepositories: Collection<Path>,
        buildWriter: BuildWriter,
    ) {
        write(
            location,
            allPlugins,
            customPluginMap,
            isRoot = true,
            useOldPluginStyle,
            projectRepositories,
            buildWriter
        )
    }

    @VisibleForTesting
    internal open fun writeExtension(writer: BuildWriter, location: Path) {
        // nothing to do here
    }

    private fun write(
        location: Path,
        allPlugins: Map<PluginType, Set<String>>,
        customPluginMap: Map<String, Set<String>>,
        isRoot: Boolean,
        useOldPluginStyle: Boolean,
        projectRepositories: Collection<Path>,
        buildWriter: BuildWriter,
    ) {
        location.createDirectories()

        buildWriter.apply {
            if (useOldPluginStyle) {
                // in the old plugin style, we will write a buildscript in every project, with
                // all the repositories and all the artifacts containing in the plugins.
                // This should only be used to recreate cases where different projects use different
                // classloaders
                if (plugins.isNotEmpty()) {
                    block("buildscript") {
                        block("repositories") {
                            for (repo in projectRepositories) {
                                mavenSnippet(repo)
                            }
                        }
                        block("dependencies") {
                            // write the plugins dependencies
                            for (plugin in plugins) {
                                plugin.plugin.artifact?.let {
                                    if (plugin.version != INTERNAL_PLUGIN_VERSION) {
                                        dependency("classpath", "$it:${plugin.version}")
                                    }
                                }
                            }
                            writeDependencyBuilderContent(location)

                            if (customPluginMap.isNotEmpty()) {
                                // we need to make a path relative to where the build logic jar will
                                // be. We can compute that based on the number segments in the gradle
                                // path.
                                val count = path.split(":").size

                                val buildLogicPath = buildString {
                                    for (i in 1..<count) {
                                        append("../")
                                    }
                                    append("build-logic.jar")
                                }
                                dependency("classpath", rawMethod("files", buildLogicPath))
                            }
                        }
                    }

                    emptyLine()

                    // write the plugins
                    for (plugin in plugins) {
                        applyPluginByName(plugin.plugin.id)
                    }
                }
            } else {

                val isRootWithCustomPlugin = isRoot && customPluginMap.isNotEmpty()

                val pluginsWithNoMarkers = allPlugins.keys.filter { !it.hasMarker && it.artifact != null }
                val isRootWithNonMarkerPlugin = isRoot && pluginsWithNoMarkers.isNotEmpty()

                if (!buildscriptBuilder.isEmpty || isRootWithCustomPlugin || isRootWithNonMarkerPlugin) {
                    block("buildscript") {
                        block("dependencies") {
                            writeDependencyBuilderContent(location)
                            if (isRootWithCustomPlugin) {
                                dependency("classpath", rawMethod("files", "build-logic.jar"))
                            }
                            if (isRootWithNonMarkerPlugin) {
                                for (plugin in pluginsWithNoMarkers) {
                                    dependency("classpath", "${plugin.artifact}:${plugin.version}")
                                }
                            }
                        }
                    }
                }

                block("plugins") {
                    // write the plugins used by this project
                    for ((plugin, version) in plugins) {
                        // we display the version if:
                        // - this is the root project
                        // - this is not the root project, but there are 2+ versions used in the build
                        // If the version is INTERNAL_PLUGIN_VERSION then we also skip it
                        val versionToWrite =
                            if ((isRoot || allPlugins[plugin]!!.size > 2) && version != INTERNAL_PLUGIN_VERSION)
                                version
                            else null
                        pluginId(plugin.id, versionToWrite)
                    }

                    // write the plugins used by the other projects (only for root project)
                    if (isRoot) {
                        val remainingPlugins = allPlugins - plugins.map { it.plugin }.toSet()

                        // we can exclude plugin with no versions
                        for ((plugin, versions) in remainingPlugins) {
                            // if there are 2+ versions we don't write it there.
                            if (versions.size > 1) continue

                            val version = versions.first()
                            // no need to write core plugins since there's no version to define
                            // (if it's used by this project, it's written above)
                            if (version == INTERNAL_PLUGIN_VERSION) continue

                            pluginId(plugin.id, version, apply = false)
                        }
                    }
                }
            }

            emptyLine()

            val pluginsToApply = customPluginMap[path]
            pluginsToApply?.let {
                // If there is a plugin class, apply them
                it.forEach { plugin ->
                    applyPluginFromClass(plugin)
                }
                emptyLine()
            }

            if (group != null || version != null) {
                group?.let {
                    set("group", it)
                }
                version?.let {
                    set("version", it)
                }

                emptyLine()
            }

            // write the Android extension if it exists
            writeExtension(this, location)

            // write the other custom extensions
            for (extensionData in pluginExtensions.values) {
                block(extensionData.name) {
                    extensionData.dslRecorder.writeContent(this)
                }
                emptyLine()
            }

            dependencies.write(this, location)
        }.also {
            val file = location.resolve(it.buildFileName)
            file.writeText(it.toString())
        }

        // write the rest of the content.
        (files as? DelayedGradleProjectFiles)?.let { files->
            if (!files.isDirect) {
                files.write(location)
                // once the project is written on disk, we want to move the files to a direct
                // mode so that reconfigure can update them.
                // Keeping them delayed would not work as they must be in memory and that would mean
                // having to load all the files when doing a reconfigure.
                files.makeDirect(location)
            }
        }
    }

    private fun BuildWriter.writeDependencyBuilderContent(location: Path) {
        buildscriptBuilder.dependencies.forEach { dependency ->
            when (dependency) {
                is String -> dependency("classpath", dependency)
                is LocalJarDependency -> {
                    val path = createLocalJar(dependency, location)
                    dependency("classpath", rawMethod("files", path))
                }

                else -> throw RuntimeException("Unsupported dependency type: ${dependency.javaClass}")
            }
        }
    }
}

private class BuildscriptBuilderImpl: BuildscriptBuilder {
    val dependencies = mutableListOf<Any>()

    val isEmpty: Boolean
        get() = dependencies.isEmpty()

    override fun classpath(dependency: Any) {
        dependencies.add(dependency)
    }

    override fun localJar(name: String, action: JarBuilder.() -> Unit): LocalJarDependency {
        val builder = JarBuilderImpl().also {
            action(it)
        }

        return LocalJarDependencyImpl(name, builder.getContent())
    }
}

/**
 * Internal version. This is used so that plugins always have a version
 * even if it's the same as Gradle.
 * As we pass plugins and their versions in various maps, this is easier to handle
 * than a null value.
 */
internal const val INTERNAL_PLUGIN_VERSION: String = "__internal_version__"
