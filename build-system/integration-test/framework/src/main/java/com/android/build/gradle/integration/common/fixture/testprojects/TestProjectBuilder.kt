/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.testprojects

import com.android.build.gradle.integration.common.dependencies.JarBuilder
import com.android.testutils.MavenRepoGenerator
import java.nio.file.Path

interface DependencyBuilder {
    fun requireCapability(capability: String)
}

interface DependenciesBuilder {

    /**
     * Remove all dependencies
     */
    fun clear()

    /**
     * Remove a dependency, by its scope and its information.
     *
     * This must match exactly how it was added
     */
    fun remove(scope: String, dependency: Any, action: (DependencyBuilder.() -> Unit)? = null)

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

    /**
     * Creates a [LocalJarBuilder] to be passed to [implementation] or any other scope
     */
    fun localJar(name: String, action: JarBuilder.() -> Unit) : LocalJarDependency

    /**
     *  Creates a [LocalFiles] to be passed to the [implementation] or any other scope.
     */
    fun files(path: Path): LocalFiles

    /**
     * Creates a [ProjectDependencyBuilder] to be passed to [implementation] or any other scope
     *
     * @param path the project path
     * @param testFixtures whether the dependency is on the test fixtures of the project.
     */
    fun project(
        path: String,
        testFixtures: Boolean = false,
        configuration: String? = null): ProjectDependencyBuilder

    fun platform(
        path: Any
    ): PlatformDependency

    /**
     * Creates a [ExternalDependencyBuilder] to be passed to [implementation] or any other scope
     *
     * @param coordinate the external library coordinate
     * @param testFixtures whether the dependency is on the test fixtures of the library.
     */
    fun externalLibrary(coordinate: String, testFixtures: Boolean = false): ExternalDependencyBuilder

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

    /**
     * the configuration that is targeted on the publishing project. This is legacy
     * for the case that pre-dates variant-aware publishing.
     */
    val configuration: String?
}

interface PlatformDependency {
    val path: Any
}

interface ExternalDependencyBuilder {
    val coordinate: String
    val testFixtures: Boolean
}
