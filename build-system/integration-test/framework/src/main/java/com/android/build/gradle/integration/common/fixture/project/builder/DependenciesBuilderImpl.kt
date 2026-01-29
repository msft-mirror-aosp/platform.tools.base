/*
 * Copyright (C) 2025 The Android Open Source Project
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
import com.android.testutils.MavenRepoGenerator
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

internal class DependenciesBuilderImpl() : DependenciesConfigurationsBuilder by DependencyConfigurationsBuilderImpl(), DependenciesBuilder {

  override fun localJar(name: String, action: JarBuilder.() -> Unit): LocalJarDependency {
    val builder = JarBuilderImpl().also { action(it) }

    return LocalJarDependencyImpl(name, builder.getContent())
  }

  override fun files(path: Path): LocalFiles {
    return LocalFilesImpl(path)
  }

  override fun project(path: String, testFixtures: Boolean, configuration: String?): ProjectDependencyBuilder =
    ProjectDependencyBuilderImpl(path, testFixtures, configuration)

  override fun platform(path: Any): PlatformDependency = PlatformDependencyImpl(path)

  override fun externalLibrary(path: String, testFixtures: Boolean): ExternalDependencyBuilder =
    ExternalDependencyBuilderImpl(path, testFixtures)

  override val constraints: ConstraintsBuilderImpl by lazy(LazyThreadSafetyMode.NONE) { ConstraintsBuilderImpl() }

  override fun constraints(action: ConstraintsBuilder.() -> Unit) {
    action(constraints)
  }

  val externalLibraries: List<MavenRepoGenerator.Library>
    get() = getDependenciesData().map { it.dependency }.filterIsInstance<MavenRepoGenerator.Library>()

  fun write(buildWriter: BuildWriter, projectLocation: Path) {
    if (isEmpty() && constraints.isEmpty()) return

    buildWriter.apply {
      block("dependencies") {
        constraints.write(this, projectLocation)
        for ((configurationName, dependency, capability) in getDependenciesData()) {
          when (dependency) {
            is String -> dependency(configurationName, dependency, capability)
            is ExternalDependencyBuilder -> {
              if (dependency.testFixtures) {
                dependency(configurationName, rawMethod("testFixtures", dependency.coordinate), capability)
              } else {
                dependency(configurationName, dependency.coordinate, capability)
              }
            }

            is ProjectDependencyBuilder -> {
              val dep = getDependencyNotationForProject(dependency)

              dependency(configurationName, dep, capability)
            }

            is PlatformDependency -> {
              val path = (dependency.path as? ProjectDependencyBuilder)?.let { getDependencyNotationForProject(it) } ?: dependency.path

              val dep = rawMethod("platform", path)
              dependency(configurationName, dep, capability)
            }

            is MavenRepoGenerator.Library -> dependency(configurationName, dependency.mavenCoordinate.toString(), capability)

            is LocalJarDependency -> {
              val path = createLocalJar(dependency, projectLocation)
              dependency(configurationName, rawMethod("files", path), capability)
            }

            is LocalFiles -> {
              dependency(configurationName, rawMethod("files", dependency.path.toFile().toFormatted()), capability)
            }

            else -> throw RuntimeException("unsupported dependency type: ${(dependency as Any).javaClass}")
          }
        }
      }

      emptyLine()
    }
  }
}

internal class DependencyBuilderImpl : DependencyBuilder {

  internal var capability: String? = null

  override fun requireCapability(capability: String) {
    this.capability = capability
  }
}

internal fun BuildWriter.getDependencyNotationForProject(dependency: ProjectDependencyBuilder): BuildWriter.RawString {
  val projectNotation =
    dependency.configuration?.let { configName -> rawMethod("project", listOf("path" to dependency.path, "configuration" to configName)) }
      ?: rawMethod("project", dependency.path)

  val dep =
    if (dependency.testFixtures) {
      rawMethod("testFixtures", projectNotation)
    } else {
      projectNotation
    }
  return dep
}

internal fun File.toFormatted(): String {
  return if (this.isAbsolute) {
    toURI().toString()
  } else {
    // in this case, we want to make sure this is using / even on window as the
    // gradle (groovy) API requires this
    toString().replace('\\', '/')
  }
}

internal fun createLocalJar(localJarDependency: LocalJarDependency, projectLocation: Path): String {
  val relativePath = "libs/${localJarDependency.name}"
  val jarPath = projectLocation.resolve(relativePath)
  jarPath.parent.createDirectories()
  jarPath.writeBytes(localJarDependency.content)

  return relativePath
}

internal data class LocalJarDependencyImpl(override val name: String, override val content: ByteArray) : LocalJarDependency

private data class LocalFilesImpl(override val path: Path) : LocalFiles

internal data class ProjectDependencyBuilderImpl(
  override val path: String,
  override val testFixtures: Boolean,
  override val configuration: String? = null,
) : ProjectDependencyBuilder

private data class PlatformDependencyImpl(override val path: Any) : PlatformDependency

private data class ExternalDependencyBuilderImpl(override val coordinate: String, override val testFixtures: Boolean) :
  ExternalDependencyBuilder
