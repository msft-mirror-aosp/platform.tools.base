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
import java.nio.file.Path

@GradleDefinitionDsl
interface DependencyBuilder {
  fun requireCapability(capability: String)
}

@GradleDefinitionDsl
interface DependenciesBuilder : DependencyConfigurations {

  /** Creates a [LocalJarBuilder] to be passed to [implementation] or any other scope */
  fun localJar(name: String, action: JarBuilder.() -> Unit): LocalJarDependency

  /** Creates a [LocalFiles] to be passed to the [implementation] or any other scope. */
  fun files(path: Path): LocalFiles

  /**
   * Creates a [ProjectDependencyBuilder] to be passed to [implementation] or any other scope
   *
   * @param path the project path
   * @param testFixtures whether the dependency is on the test fixtures of the project.
   */
  fun project(path: String, testFixtures: Boolean = false, configuration: String? = null): ProjectDependencyBuilder

  fun platform(path: Any): PlatformDependency

  /**
   * Creates a [ExternalDependencyBuilder] to be passed to [implementation] or any other scope
   *
   * @param coordinate the external library coordinate
   * @param testFixtures whether the dependency is on the test fixtures of the library.
   */
  fun externalLibrary(coordinate: String, testFixtures: Boolean = false): ExternalDependencyBuilder

  /** Configures dependency constraints of the project */
  fun constraints(action: ConstraintsBuilder.() -> Unit)

  val constraints: ConstraintsBuilder
}

interface LocalJarDependency {
  val name: String
  val content: ByteArray
}

interface LocalFiles {
  val path: Path
}

interface ProjectDependencyBuilder {
  val path: String
  val testFixtures: Boolean

  /** the configuration that is targeted on the publishing project. This is legacy for the case that pre-dates variant-aware publishing. */
  val configuration: String?
}

interface PlatformDependency {
  val path: Any
}

interface ExternalDependencyBuilder {
  val coordinate: String
  val testFixtures: Boolean
}
