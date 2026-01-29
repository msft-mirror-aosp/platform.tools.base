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

package com.android.build.gradle.integration.common.fixture.project

import com.android.build.gradle.integration.common.fixture.project.builder.GradleDefinitionDsl
import com.android.build.gradle.integration.common.fixture.project.builder.ProjectDependencyBuilder
import com.android.build.gradle.integration.common.fixture.project.builder.ProjectDependencyBuilderImpl

/** Builder for defining multiple dependency substitution rules. */
@GradleDefinitionDsl
interface DependencySubstitutionsBuilder {
  fun substitute(coordinates: Substitution): Substitution

  fun module(moduleCoordinates: String): Substitution

  fun project(projectPath: String, testFixtures: Boolean = false): Substitution
}

/** Builder for defining a single substitution rule. Only supports and exposes "using" for now. */
@GradleDefinitionDsl
sealed interface Substitution {
  var using: Substitution

  fun using(coordinates: Substitution)
}

sealed class SubstitutionImpl : Substitution {
  data class Project(val projectDependency: ProjectDependencyBuilder) : SubstitutionImpl()

  data class Module(val moduleCoordinates: String) : SubstitutionImpl()

  override lateinit var using: Substitution

  override fun using(coordinates: Substitution) {
    using = coordinates
  }
}

internal class DependencySubstitutionsBuilderImpl : DependencySubstitutionsBuilder {
  val substitutions = mutableListOf<Substitution>()

  override fun substitute(coordinates: Substitution): Substitution {
    substitutions.add(coordinates)
    return coordinates
  }

  override fun project(projectPath: String, testFixtures: Boolean) =
    SubstitutionImpl.Project(ProjectDependencyBuilderImpl(projectPath, testFixtures))

  override fun module(moduleCoordinates: String) = SubstitutionImpl.Module(moduleCoordinates)
}
