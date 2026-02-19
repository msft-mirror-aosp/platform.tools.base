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

import com.android.build.api.artifact.Artifact
import com.android.build.gradle.integration.common.fixture.project.builder.DirectGradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.reversible.FileChangeController
import com.android.testutils.MavenRepoGenerator
import java.nio.file.Path
import java.util.Locale

/** Base interface for all projects, including but not limited to [GenericProject] and [AndroidProject]. */
interface GradleProject<out ProjectDefinitionT : GradleProjectDefinition> {

  /**
   * Resolves a path relative to the project location.
   *
   * While this can be used to edit files, prefer [files] which provides helper methods, and automatically creates intermediates
   * directories. It is also consistent with [GradleProjectDefinition.files]
   */
  fun resolve(path: String): Path

  /** Resolves a path that leads to the storage of this task output artifact. */
  fun resolve(artifact: Artifact<*>): Path

  /** the object that allows to add/update/remove files from the project */
  val files: GradleProjectFiles

  /** The build folder for the project. This does NOT support build dir relocation */
  val buildDir: Path

  /**
   * Reconfigure the project, and writes the result on disk right away
   *
   * This is useful to make "edits" to the build file during a test.
   *
   * While you can use [GradleProjectDefinition.files] to make edits to other files, this is no different than directly using
   * [GradleProject.files].
   *
   * @param action the action to configure the [GradleProjectDefinition]
   */
  fun reconfigure(action: ProjectDefinitionT.() -> Unit)
}

/** Base implementation for all [GradleProject] */
internal abstract class GradleProjectImpl<ProjectDefinitionT : GradleProjectDefinition>(
  internal val location: Path,
  protected val projectDefinition: ProjectDefinitionT,
) : GradleProject<ProjectDefinitionT> {

  override fun resolve(path: String): Path {
    // let's not allow access to the build file via this API.
    // Build files should be edited via the DSL (via reconfigure)
    if (path == "build.gradle" || path == "build.gradle.kts")
      throw RuntimeException("Unauthorized access to the build file. Use the DSL to edit the file instead")
    return location.resolve(path)
  }

  override fun resolve(artifact: Artifact<*>): Path =
    location.resolve("build/${artifact.category.name.lowercase(Locale.US)}/${artifact.getFolderName()}")

  override val files: GradleProjectFiles = DirectGradleProjectFiles(location)

  /**
   * the build that contains this project.
   *
   * This relationship is bidirectional and therefore cannot be implemented by constructor parameters in both classes. Here this is set
   * after the constructor once the build object has been created. This is not meant to be really mutable.
   */
  internal lateinit var build: GradleBuildImpl

  override val buildDir: Path
    get() = location.resolve("build")

  override fun reconfigure(action: ProjectDefinitionT.() -> Unit) {
    // gather previous data
    val previousPlugins = projectDefinition.pluginCallbacks.toSet()

    val previousDependencies = (projectDefinition as GradleProjectDefinitionImpl).dependencies.externalLibraries.map { it.mavenCoordinate }

    action(projectDefinition)

    // we need to query the other projects for their plugins
    val allPlugins = build.computeAllPluginMap()

    val useOldPluginStyle = build.useOldPluginStyle
    val globalState = build.getGlobalDefinitionState()

    // the custom plugin map is pre-recorded and available from the build (Definition)
    val customPluginMap = globalState.customPluginMap
    val repositories = if (useOldPluginStyle) globalState.repositories else listOf()

    (projectDefinition as GradleProjectDefinitionImpl).writeSubProject(
      location,
      allPlugins,
      customPluginMap,
      useOldPluginStyle,
      repositories,
      build.getNewWriter(),
    )

    // we also need to write new inline dependencies
    val newDependencies = projectDefinition.dependencies.externalLibraries.associateBy { it.mavenCoordinate }.toMutableMap()
    // remove existing dependencies
    previousDependencies.forEach { newDependencies.remove(it) }
    // write the rest to the repo.
    MavenRepoGenerator(newDependencies.values.toList()).generate(build.mavenRepoPath)

    val newPlugins = projectDefinition.pluginCallbacks.toSet()

    if (previousPlugins.size != newPlugins.size || !previousPlugins.containsAll(newPlugins)) {
      throw RuntimeException("Cannot change pluginCallbacks in reconfigure")
    }
  }

  abstract fun getReversibleInstance(fileChangeController: FileChangeController): GradleProject<ProjectDefinitionT>
}
