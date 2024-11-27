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

import com.android.testutils.MavenRepoGenerator.Library

/**
 * Allows configuring a custom repository with test libraries.
 */
interface MavenRepository {
    /**
     * Adds a manually created library
     */
    fun library(library: Library)

    /**
     * Creates a JAR with the provided coordinates.
     *
     * This returns an [JarBuilder] which can be used to configure the Jar.
     */
    fun jar(
        mavenCoordinate: String,
    ): JarWithDependenciesBuilder

    /**
     * Creates an AAR.
     *
     * This returns an [AarBuilder] which can be used to configure the AAR.
     *
     * @param groupId the groupId of the artifact and is used for the library namespace
     * @param artifactId the artifactId. If null, the last segment of groupId is used
     * @param version the version. Defaults to 1.0
     */
    fun aar(
        groupId: String,
        artifactId: String? = null,
        version: String = "1.0",
    ): AarBuilder
}

interface AarBuilder {

    /**
     * Configures the main jar with the provided lambda
     */
    fun withMainJar(action: JarBuilder.() -> Unit): AarBuilder

    /**
     * Sets the main jar content to be a collection of empty classes.
     * Shortcut to use instead of [withMainJar]
     */
    fun setMainEmptyClasses(classBinaryNames: Collection<String>): AarBuilder
    /**
     * Sets the main jar content to be a collection of empty classes.
     * Shortcut to use instead of [withMainJar]
     */
    fun setMainEmptyClasses(vararg classBinaryNames: String): AarBuilder
    /**
     * Sets the main jar content to be the provided byte array
     * Shortcut to use instead of [withMainJar]
     */
    fun setMainJar(jar: ByteArray): AarBuilder
    /**
     * Sets the jar content to be the provided classes
     * Shortcut to use instead of [withMainJar]
     */
    fun setMainClasses(classes: Collection<Class<*>>): AarBuilder

    /**
     * adds a secondary jar
     * @param the name of the jar to be put under `libs`. The name does not include the jar extension
     */
    fun addSecondaryJar(name: String, action: JarBuilder.() -> Unit): AarBuilder

    /**
     * Configures the API jar with the provided lambda
     */
    fun withApiJar(action: JarBuilder.() -> Unit): AarBuilder

    /**
     * Configures the content of the manifest file.
     */
    fun withManifest(content: String): AarBuilder

    /**
     * Adds android resources to the AAR.
     *
     * The key is the path of the file, under the `res` folder.
     */
    fun addResources(resMap: Map<String, String>): AarBuilder
    /**
     * Adds an android resources to the AAR.
     *
     * @param path the pat of the file, under the `res` folder.
     * @param content the text content of the resources
     */
    fun addResource(path: String, content: String): AarBuilder
    /**
     * Adds an android resources to the AAR.
     *
     * @param path the pat of the file, under the `res` folder.
     * @param content the content of the resources
     */
    fun addResource(path: String, content: ByteArray): AarBuilder

    /**
     * Configures the lint jar with the provided lambda
     */
    fun withLintJar(action: JarBuilder.() -> Unit): AarBuilder

    /**
     * Configures the test fixture AAR with the provided lambda.
     *
     * The test fixture is a full AAR with its own configuration and dependencies.
     */
    fun withFixtures(action: AarBuilder.() -> Unit): AarBuilder

    /**
     * Sets the dependencies of the AAR
     */
    fun withDependencies(list: List<String>): AarBuilder
}

interface JarBuilder {
    /**
     * Sets the jar content to be a collection of empty classes
     */
    fun setEmptyClasses(classBinaryNames: Collection<String>): JarBuilder
    /**
     * Sets the jar content to be a collection of empty classes
     */
    fun setEmptyClasses(vararg classBinaryNames: String): JarBuilder

    /**
     * Sets the jar content to be the provided byte array
     */
    fun setJar(jar: ByteArray): JarBuilder
    /**
     * Sets the jar content to be the provided classes
     */
    fun setClasses(classes: Collection<Class<*>>): JarBuilder
}

interface JarWithDependenciesBuilder: JarBuilder {

    /**
     * Sets the dependencies of the Jar
     */
    fun withDependencies(list: List<String>): JarBuilder
}
