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

import com.android.build.gradle.integration.common.fixture.testprojects.DependenciesBuilder
import com.android.build.gradle.integration.common.fixture.testprojects.DependenciesBuilderImpl
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Base interface for all project definition, including but not limited to
 * [GenericProjectDefinition] and [AndroidProjectDefinition].
 */
interface GradleProjectDefinition {
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
     * Replaces an applied plugin, with a provided version
     *
     * This replaces the plugin in the same place as the previous one.
     */
    fun replaceAppliedPlugin(type: PluginType, version: String)

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
     * Wraps a library binary with a module
     */
    fun wrap(library: ByteArray, fileName: String)
}

/**
 * Implementation shared between [GenericProjectDefinition] and [AndroidProjectDefinition]
 */
internal abstract class GradleProjectDefinitionImpl(
    override val path: String
): GradleProjectDefinition {
    data class AppliedPlugin(
        val plugin: PluginType,
        val version: String
    )

    internal val plugins = mutableListOf<AppliedPlugin>()

    override var group: String? = null
    override var version: String? = null

    override fun applyPlugin(type: PluginType, version: String?, applyFirst: Boolean) {
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

    override fun replaceAppliedPlugin(type: PluginType, version: String) {
        val match = plugins.firstOrNull { it.plugin == type }
            ?: throw RuntimeException("Plugin $type not yet applied")

        val appliedPlugin = AppliedPlugin(type, version)
        val index = plugins.indexOf(match)
        plugins[index] = appliedPlugin
    }

    internal fun hasPlugin(plugin: PluginType): Boolean = plugins.any { it.plugin == plugin }

    override val dependencies: DependenciesBuilderImpl = DependenciesBuilderImpl()

    override fun dependencies(action: DependenciesBuilder.() -> Unit) {
        action(dependencies)
    }

    override fun wrap(library: ByteArray, fileName: String) {
        throw RuntimeException("todo")
    }

    internal fun writeSubProject(
        location: Path,
        buildFileOnly: Boolean = false,
        allPlugins: Map<PluginType, Set<String>>,
        customPluginMap: Map<String, String>,
        buildWriter: () -> BuildWriter,
    ) {
        write(
            location,
            allPlugins,
            customPluginMap,
            isRoot = false,
            buildFileOnly = buildFileOnly,
            buildWriter
        )
    }

    internal fun writeRoot(
        location: Path,
        allPlugins: Map<PluginType, Set<String>>,
        customPluginMap: Map<String, String>,
        buildWriter: () -> BuildWriter,
    ) {
        write(
            location,
            allPlugins,
            customPluginMap,
            isRoot = true,
            buildFileOnly = false,
            buildWriter
        )
    }

    protected open fun writeExtension(writer: BuildWriter) {
        // nothing to do here
    }

    private fun write(
        location: Path,
        allPlugins: Map<PluginType, Set<String>>,
        customPluginMap: Map<String, String>,
        isRoot: Boolean,
        buildFileOnly: Boolean,
        buildWriter: () -> BuildWriter,
    ) {
        location.createDirectories()

        buildWriter().apply {
            if (isRoot && customPluginMap.isNotEmpty()) {
                block("buildscript") {
                    block("dependencies") {
                        method("classpath", rawMethod("files", "build-logic.jar"))
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

                        pluginId(plugin.id, plugin.version, apply = false)
                    }
                }
            }

            customPluginMap[path]?.let {
                // If there is a plugin class, apply it.
                applyPluginFromClass(it)
            }

            group?.let {
                set("group", it)
            }
            version?.let {
                set("version", it)
            }

            // write the Android extension if it exist
            writeExtension(this)

            dependencies.write(this, location)
        }.also {
            val file = location.resolve(it.buildFileName)
            file.writeText(it.toString())
        }

        // write the rest of the content.
        if (!buildFileOnly) {
            (files as GradleProjectFilesImpl).write(location)
        }
    }
}

internal const val INTERNAL_PLUGIN_VERSION: String = "__internal_version__"
