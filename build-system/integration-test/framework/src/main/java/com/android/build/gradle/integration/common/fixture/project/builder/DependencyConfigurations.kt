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

/** For holding and managing dependency information.
 *
 * Used by blocks that define dependencies, e.g., dependencies and constants.
 */
interface DependencyConfigurations {
    /**
     * Remove all dependencies.
     */
    fun clear()

    /**
     * Remove a dependency, by its scope and its information.
     *
     * This must match exactly how it was added
     */
    fun remove(scope: String, dependency: Any, action: (DependencyBuilder.() -> Unit)? = null): Boolean

    /**
     * Remove a dependency, by its scope and its information.
     *
     * This must match exactly how it was added
     */
    fun remove(scopeToDependency: Pair<String, Any>): Boolean

    /**
     * adds a dependency to the [configurationName] configuration.
     *
     * See [implementation] for details
     */
    fun add(
        configurationName: String,
        dependency: Any,
        action: (DependencyBuilder.() -> Unit)? = null
    )

    /**
     * adds a dependency to the [configurationName] configuration.
     *
     * See [implementation] for details
     */
    fun add(
        configurationNameToDependency: Pair<String, Any>,
        action: (DependencyBuilder.() -> Unit)? = null
    )

    /** If there are dependencies present. */
    fun isEmpty(): Boolean

    /**
     * adds a dependency in the implementation scope.
     *
     * The instance being passed as a parameter must be:
     * - a String (should not be quoted) or result of [externalLibrary]: for maven coordinates.
     * - result of [project] for sub-project dependency
     * - result of [localJar] for on-the-fly created local jars
     * - a [MavenRepoGenerator.Library] for on-the-fly created external AARs.
     */
    fun implementation(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /**
     * adds a dependency in the api scope.
     *
     * See [implementation] for details
     */
    fun api(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /** Adds a dependency to the compileOnly configuration. See [implementation] for details. */
    fun compileOnly(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /** Adds a dependency to the compileOnlyApi configuration. See [implementation] for details. */
    fun compileOnlyApi(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /** Adds a dependency to the runtimeOnly configuration. See [implementation] for details. */
    fun runtimeOnly(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /**
     * adds a dependency in the testImplementation scope.
     *
     * See [implementation] for details
     */
    fun testImplementation(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /** Adds a dependency to the testRuntimeOnly configuration. See [implementation] for details. */
    fun testRuntimeOnly(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    fun testFixturesImplementation(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)
    /**
     * adds a dependency in the androidTestImplementation scope.
     *
     * See [implementation] for details
     */
    fun androidTestImplementation(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /**
     * adds a dependency
     */
    fun include(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /**
     * Adds a dependency (to privacy sandbox sdk) declaring dependent sdk modules should be 'installed'.
     */
    fun requiredSdk(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /**
     * Adds a dependency to (to privacy sandbox sdk) declaring its dependent sdks are optional.
     */
    fun optionalSdk(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /**
     * adds a dependency in the lintPublish scope.
     *
     * See [implementation] for details
     */
    fun lintPublish(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /**
     * adds a dependency in the lintCheck scope.
     *
     * See [implementation] for details
     */
    fun lintChecks(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /**
     * adds a dependency in the screenshotTest scope.
     *
     * See [implementation] for details
     */
    fun screenshotTestImplementation(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /**
     * Adds a dependency in the coreLibraryDesugaring scope.
     */
    fun coreLibraryDesugaring(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

    /** Adds a dependency that using KSP's configuration. */
    fun ksp(dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)
}

internal interface DependenciesConfigurationsBuilder: DependencyConfigurations {
    /**
     * Get the list of all added dependencies for a declared configuration.
     */
    fun getDependenciesData(configuration: String): List<DependencyData>

    /**
     * Get the list of all added dependencies with their declared configuration.
     */
    fun getDependenciesData(): List<DependencyData>
}

internal data class DependencyData(
    val configurationName: String,
    val dependency: Any,
    val capability: String? = null
)

internal class DependencyConfigurationsBuilderImpl() : DependenciesConfigurationsBuilder {

    private val dependencies: MutableList<DependencyData> = mutableListOf()

    override fun getDependenciesData(configuration: String): List<DependencyData> {
        return dependencies.filter { it.configurationName == configuration }
    }

    override fun getDependenciesData(): List<DependencyData> {
        return dependencies.toList()
    }

    override fun remove(
        scope: String,
        dependency: Any,
        action: (DependencyBuilder.() -> Unit)?
    ): Boolean {
        val dependencyData = action?.let {
            val builder = DependencyBuilderImpl()
            action(builder)
            DependencyData(scope, dependency, builder.capability)
        } ?: DependencyData(scope, dependency)

        if (!dependencies.remove(dependencyData)) {
            throw RuntimeException("Could not find dependency scope: $scope, dependency: $dependency")
        }
        return true
    }

    override fun remove(scopeToDependency: Pair<String, Any>): Boolean {
        return remove(scopeToDependency.first, scopeToDependency.second, null)
    }

    override fun add(
        configurationName: String,
        dependency: Any,
        action: (DependencyBuilder.() -> Unit)?
    ) {
        val dependencyData = action?.let {
            val builder = DependencyBuilderImpl()
            action(builder)
            DependencyData(configurationName, dependency, builder.capability)
        } ?: DependencyData(configurationName, dependency)

        dependencies.add(dependencyData)
    }

    override fun add(
        configurationNameToDependency: Pair<String, Any>,
        action: (DependencyBuilder.() -> Unit)?
    ) {
        add(configurationNameToDependency.first, configurationNameToDependency.second, null)
    }

    override fun clear() {
        dependencies.clear()
    }

    override fun isEmpty(): Boolean {
        return dependencies.isEmpty()
    }

    override fun implementation(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("implementation", dependency, action)
    }

    override fun api(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("api", dependency, action)
    }

    override fun compileOnly(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("compileOnly", dependency, action)
    }

    override fun compileOnlyApi(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("compileOnlyApi", dependency, action)
    }

    override fun runtimeOnly(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("runtimeOnly", dependency, action)
    }

    override fun testImplementation(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("testImplementation", dependency, action)
    }

    override fun testRuntimeOnly(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("testRuntimeOnly", dependency, action)
    }

    override fun testFixturesImplementation(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("testFixturesImplementation", dependency, action)
    }

    override fun androidTestImplementation(
        dependency: Any,
        action: (DependencyBuilder.() -> Unit)?
    ) {
        add("androidTestImplementation", dependency, action)
    }

    override fun include(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("include", dependency, action)
    }

    override fun requiredSdk(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("requiredSdk", dependency, action)
    }

    override fun optionalSdk(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("optionalSdk", dependency, action)
    }

    override fun lintPublish(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("lintPublish", dependency, action)
    }

    override fun lintChecks(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("lintChecks", dependency, action)
    }

    override fun screenshotTestImplementation(
        dependency: Any,
        action: (DependencyBuilder.() -> Unit)?
    ) {
        add("screenshotTestImplementation", dependency, action)
    }

    override fun coreLibraryDesugaring(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("coreLibraryDesugaring", dependency, action)
    }

    override fun ksp(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        add("ksp", dependency, action)
    }
}
