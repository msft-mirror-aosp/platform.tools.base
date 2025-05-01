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

import com.android.testutils.MavenRepoGenerator
import java.nio.file.Path

internal class ConstraintsBuilderImpl
    : DependenciesConfigurationsBuilder by DependencyConfigurationsBuilderImpl(), ConstraintsBuilder {

    override fun runtime(
        dependency: Any,
        action: (DependencyBuilder.() -> Unit)?
    ) {
        add("runtime", dependency, action)
    }

    fun write(buildWriter: BuildWriter, projectLocation: Path) {
        if (isEmpty()) return
        val constraints = getDependenciesData()

        buildWriter.apply {
            block("constraints") {
                for ((scope, dependencyConstraint, capability) in constraints) {
                    when (dependencyConstraint) {
                        is String -> dependency(scope, dependencyConstraint, capability)
                        is ExternalDependencyBuilder -> {
                            if (dependencyConstraint.testFixtures) {
                                dependency(
                                    scope,
                                    rawMethod("testFixtures", dependencyConstraint.coordinate),
                                    capability
                                )
                            } else {
                                dependency(scope, dependencyConstraint.coordinate, capability)
                            }
                        }

                        is ProjectDependencyBuilder -> {
                            val dep = getDependencyNotationForProject(dependencyConstraint)

                            dependency(scope, dep, capability)
                        }

                        is PlatformDependency -> {
                            val path = (dependencyConstraint.path as? ProjectDependencyBuilder)?.let {
                                getDependencyNotationForProject(it)
                            } ?: dependencyConstraint.path
                            error("$path not allowed. platform dependencies can not be used as a constraint.")
                        }

                        is MavenRepoGenerator.Library -> dependency(
                            scope,
                            dependencyConstraint.mavenCoordinate.toString(),
                            capability
                        )

                        is LocalJarDependency -> {
                            val path = createLocalJar(dependencyConstraint, projectLocation)
                            error("$path not allowed. local jar dependencies can not be used as a constraint.")
                        }

                        else -> throw RuntimeException("unsupported dependency type: ${dependencyConstraint.javaClass}")
                    }
                }
            }

            emptyLine()
        }
    }

}
