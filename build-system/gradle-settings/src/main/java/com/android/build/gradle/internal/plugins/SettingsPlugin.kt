/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.internal.plugins

import com.android.build.api.dsl.SettingsExtension
import com.android.build.gradle.internal.dsl.KotlinGradlePluginVersion
import com.android.build.gradle.internal.dsl.SettingsExtensionImpl
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.initialization.Settings
import org.gradle.api.initialization.dsl.ScriptHandler
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.plugin.management.PluginResolveDetails

class SettingsPlugin @Inject constructor(private val objectFactory: ObjectFactory) : Plugin<Settings> {

  private val logger = Logging.getLogger(SettingsPlugin::class.java)
  private val loggedWarnings = ConcurrentHashMap.newKeySet<String>()

  override fun apply(settings: Settings) {
    val settingsExtension =
      settings.extensions.create(SettingsExtension::class.java, "android", SettingsExtensionImpl::class.java, objectFactory)
        as SettingsExtensionImpl

    settings.gradle.settingsEvaluated { settingsExtension.lockKotlinGradlePluginVersion() }
    settings.pluginManagement.resolutionStrategy.eachPlugin { plugin ->
      settingsExtension.kotlinGradlePluginVersion?.let { alignKotlinGradlePlugin(plugin, it) }
    }

    // as Project objects cannot query for the Settings object (and its extensions), we
    // deposit the extension instance into each project using the extra Properties.
    settings.gradle.beforeProject { project ->
      project.extensions.extraProperties["_android_settings"] = settingsExtension
      settingsExtension.kotlinGradlePluginVersion?.let { alignKotlinGradlePluginDependencies(project, it) }
    }
  }

  /**
   * Makes a Kotlin Gradle plugin applied with `plugins { id("org.jetbrains.kotlin...") }` use [version], whether it has a version or not.
   */
  private fun alignKotlinGradlePlugin(plugin: PluginResolveDetails, version: String) {
    val id = plugin.requested.id.id
    if (id !in KOTLIN_GRADLE_PLUGIN_IDS && !id.startsWith("org.jetbrains.kotlin.plugin.")) return

    // The target has the requested version, unless `pluginManagement { plugins {} }` or an earlier rule has set another one.
    val requestedVersion = plugin.target.version
    if (requestedVersion != null && requestedVersion != version) {
      warnOverridden("Plugin '$id' version '$requestedVersion'", version)
    }
    plugin.useVersion(version)
  }

  /** Makes Kotlin Gradle plugin dependencies on the buildscript classpath of [project], such as the one of AGP, use [version]. */
  private fun alignKotlinGradlePluginDependencies(project: Project, version: String) {
    val buildscript = project.buildscript
    val classpath = buildscript.configurations.getByName(ScriptHandler.CLASSPATH_CONFIGURATION)
    classpath.withDependencies { dependencies ->
      // The BOM is only needed if there are modules to align, and can only be resolved from a Maven or Ivy repository.
      if (dependencies.any { it is ModuleDependency } && buildscript.repositories.any { it !is FlatDirectoryArtifactRepository }) {
        dependencies.add(buildscript.dependencies.enforcedPlatform("$KOTLIN_GROUP:kotlin-gradle-plugins-bom:$version"))
      }
    }

    // Gradle upgrades older versions anyway, but whoever requested a newer version may not work with this one.
    val kotlinVersion = checkNotNull(KotlinGradlePluginVersion.parse(version))
    classpath.incoming.afterResolve { resolved ->
      val graph = resolved.resolutionResult
      for (dependency in graph.allDependencies) {
        val requested = dependency.requested as? ModuleComponentSelector ?: continue
        val selected = (dependency as? ResolvedDependencyResult)?.selected?.moduleVersion ?: continue
        if (selected.group != KOTLIN_GROUP || selected.version != version || requested.version == version) continue
        // Another pre-release of the same release is not known to be older, so it gets a warning too.
        val requestedVersion = KotlinGradlePluginVersion.parse(requested.version) ?: continue
        if (requestedVersion < kotlinVersion) continue

        val requester = if (dependency.from == graph.root) project.displayName else "'${dependency.from.id.displayName}'"
        warnOverridden("Dependency '${requested.displayName}' of $requester", version)
      }
    }
  }

  private fun warnOverridden(what: String, version: String) {
    val message = "$what was overridden to '$version' by android.kotlinGradlePluginVersion in settings."
    if (loggedWarnings.add(message)) {
      logger.warn(message)
    }
  }
}

private const val KOTLIN_GROUP = "org.jetbrains.kotlin"

/** Plugins that are released with the Kotlin Gradle plugin, in addition to the compiler plugins (`org.jetbrains.kotlin.plugin.*`). */
private val KOTLIN_GRADLE_PLUGIN_IDS =
  setOf(
    "org.jetbrains.kotlin.android",
    "org.jetbrains.kotlin.js",
    "org.jetbrains.kotlin.jvm",
    "org.jetbrains.kotlin.kapt",
    "org.jetbrains.kotlin.multiplatform",
    "org.jetbrains.kotlin.native.cocoapods",
  )
