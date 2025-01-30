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

import com.android.build.api.dsl.SettingsExtension
import com.android.build.gradle.integration.common.fixture.dsl.DefaultDslRecorder
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.dsl.ExtensionAwareDefinition
import java.nio.file.Path
import kotlin.io.path.writeText

interface GradleSettingsDefinition: ExtensionAwareDefinition {

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

    val android: SettingsExtension
    /**
     * Configures the android section of the project.
     *
     * This will fail if no android plugins were added.
     */
    fun android(action: SettingsExtension.() -> Unit)

    fun enableFeaturePreview(name: String)

    fun enableLocalCache(location: Path)

    /**
     * Adds a new repository to the settings configuration.
     *
     * The path must be relative to the build folder.
     */
    fun addRepository(location: String)
}

internal class GradleSettingsDefinitionImpl: GradleSettingsDefinition {
    private val featurePreviews = mutableListOf<String>()

    private val plugins = mutableListOf<AppliedPlugin>()
    private val androidDslRecorder = DefaultDslRecorder()

    // cache or the repositories as we need to keep this around for reconfiguration.
    private var repositoriesCache: Collection<Path>? = null

    private var localCacheLocation: Path? = null
    private val extraRepositories = mutableListOf<String>()

    override fun applyPlugin(type: PluginType, version: String?, applyFirst: Boolean) {
        if (!type.isSettings) {
            throw RuntimeException("Cannot apply project plugin to a project")
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

    override fun replaceAppliedPlugin(type: PluginType, version: String) {
        if (!type.isSettings) {
            throw RuntimeException("Cannot apply project plugin to a project")
        }
        val match = plugins.firstOrNull { it.plugin == type }
            ?: throw RuntimeException("Plugin $type not yet applied")

        val appliedPlugin = AppliedPlugin(type, version)
        val index = plugins.indexOf(match)
        plugins[index] = appliedPlugin
    }

    override val android: SettingsExtension by lazy {
        if (!hasAndroid()) {
            throw RuntimeException("Settings must contain ANDROID_SETTINGS to configure the android extension")
        }

        DslProxy.createProxy(SettingsExtension::class.java, androidDslRecorder,)
    }

    override fun android(action: SettingsExtension.() -> Unit) {
        action(android)
    }

    override fun enableFeaturePreview(name: String) {
        featurePreviews += name
    }

    override fun enableLocalCache(location: Path) {
        localCacheLocation = location
    }

    override fun addRepository(location: String) {
        extraRepositories.add(location)
    }

    internal fun write(
        name: String,
        location: Path,
        useOldPluginStyle: Boolean,
        repositories: Collection<Path>?,
        includedBuildNames: Collection<String>,
        subProjectPaths: Collection<String>,
        buildWriter: BuildWriter,
    ) {
        val finalRepositoryList = if (repositories == null) {
            // this is a reconfigure
            repositoriesCache ?: error("No repositories provided but cache is missing")
        } else {
            // cache the external list, not included the ones added via the DSL on settings.
            repositoriesCache = repositories

            repositories
        } + extraRepositories.map { location.resolve(it) }

        buildWriter.apply {
            if (!useOldPluginStyle) {
                block("pluginManagement") {
                    block("repositories") {
                        for (repository in finalRepositoryList) {
                            mavenSnippet(repository)
                        }
                    }
                }
            }

            emptyLine()

            block("plugins") {
                for (plugin in plugins.toSet()) {
                    pluginId(plugin.plugin.id, plugin.version)
                }
            }

            emptyLine()

            block("dependencyResolutionManagement") {
                method("repositoriesMode.set", rawString("RepositoriesMode.FAIL_ON_PROJECT_REPOS"))
                block("repositories") {
                    for (repository in finalRepositoryList) {
                        mavenSnippet(repository)
                    }
                }
            }
            emptyLine()

            set("rootProject.name", name)
            emptyLine()

            if (featurePreviews.isNotEmpty()) {
                for (name in featurePreviews) {
                    method("enableFeaturePreview", name)
                }
                emptyLine()
            }

            localCacheLocation?.let { location ->
                block("buildCache") {
                    block("local") {
                        // have to write the raw method manually, as handling of File in the
                        // writer expects a project.file method to be present
                        set("directory", location)
                    }
                }

                emptyLine()
            }

            if (includedBuildNames.isNotEmpty()) {
                for (build in includedBuildNames) {
                    method("includeBuild", build)
                }
                emptyLine()
            }

            if (hasAndroid()) {
                block("android") {
                    androidDslRecorder.writeContent(this)
                }
                emptyLine()
            }

            for (project in subProjectPaths) {
                method("include", project)
            }
        }.also {
            val file = location.resolve(it.settingsFileName)
            file.writeText(it.toString())
        }
    }

    private fun hasAndroid(): Boolean = plugins.map { it.plugin }.contains(PluginType.ANDROID_SETTINGS)
}

internal fun BuildWriter.mavenSnippet(repo: Path) {
    block("maven") {
        set("url", rawMethod("uri", repo.toUri().toString()))
        block("metadataSources") {
            method("mavenPom", listOf(), false)
            method("artifact", listOf(), false)
        }
    }
}
