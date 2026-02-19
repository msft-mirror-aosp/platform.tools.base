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

package com.android.build.gradle.integration.common.dependencies

import com.android.build.gradle.internal.tasks.AarMetadataTask.Companion.AAR_METADATA_ENTRY_PATH
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.MavenRepoGenerator.Library
import com.android.testutils.TestInputsGenerator.jarWithEmptyEntries
import com.android.testutils.generateAarWithContent
import java.nio.charset.Charset

/** A builder to create an AAR file. */
interface AarBuilder {

  /** Configures the main jar with the provided lambda */
  fun withMainJar(action: JarBuilder.() -> Unit): AarBuilder

  /** Sets the main jar content to be a collection of empty classes. Shortcut to use instead of [withMainJar] */
  fun setMainEmptyClasses(classBinaryNames: Collection<String>): AarBuilder

  /** Sets the main jar content to be a collection of empty classes. Shortcut to use instead of [withMainJar] */
  fun setMainEmptyClasses(vararg classBinaryNames: String): AarBuilder

  /** Sets the jar content to be the provided classes Shortcut to use instead of [withMainJar] */
  fun setMainClasses(classes: Collection<Class<*>>): AarBuilder

  /**
   * adds a secondary jar
   *
   * @param the name of the jar to be put under `libs`. The name does not include the jar extension
   */
  fun addSecondaryJar(name: String, action: JarBuilder.() -> Unit): AarBuilder

  /** Configures the API jar with the provided lambda */
  fun withApiJar(action: JarBuilder.() -> Unit): AarBuilder

  /** Configures the content of the manifest file. */
  fun withManifest(content: String): AarBuilder

  /** Configures the content of the proguard.txt file. */
  fun withProguardRules(content: String): AarBuilder

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
   * Adds android asset to the AAR.
   *
   * The key is the path of the file, under the `res` folder.
   */
  fun addAssets(assetMap: Map<String, String>): AarBuilder

  /**
   * Adds an android asset to the AAR.
   *
   * @param path the pat of the file, under the `res` folder.
   * @param content the text content of the resources
   */
  fun addAsset(path: String, content: String): AarBuilder

  /**
   * Adds an android asset to the AAR.
   *
   * @param path the pat of the file, under the `res` folder.
   * @param content the content of the resources
   */
  fun addAsset(path: String, content: ByteArray): AarBuilder

  /** Configures the lint jar with the provided lambda */
  fun withLintJar(action: JarBuilder.() -> Unit): AarBuilder

  /**
   * Configures the test fixture AAR with the provided lambda.
   *
   * The test fixture is a full AAR with its own configuration and dependencies.
   */
  fun withFixtures(action: AarBuilder.() -> Unit): AarBuilder

  /** Sets the dependencies of the AAR */
  fun withDependencies(list: List<String>): AarBuilder

  /**
   * Populates an aar-metadata.properties file to the AAR.
   *
   * If the aar-metadata.properties file is already defined, the contents will be overridden.
   *
   * @param properties a map of string properties to their value
   */
  fun withAarMetadataProperties(properties: Map<String, String>): AarBuilder
}

internal class AarBuilderImpl(private val groupId: String, artifactId: String?, private val version: String) : AarBuilder {
  private val artifactId: String = artifactId ?: groupId.split('.').last()

  private var mainJar: ByteArray? = null
  private val secondaryJars = mutableMapOf<String, ByteArray>()
  private var apiJar: ByteArray? = null
  private var lintJar: ByteArray? = null
  private var manifest: String? = null
  private var proguard: String? = null
  private val resources = mutableMapOf<String, ByteArray>()
  private val extraFiles = mutableMapOf<String, ByteArray>()
  private var fixtures: LibraryData? = null
  private val dependencies = mutableListOf<String>()

  internal fun toLibrary(): Library =
    MavenRepoGenerator.libraryWithFixtures(
      "$groupId:$artifactId:$version",
      "aar",
      mainLibrary =
        toLibraryData().let {
          {
            artifact = it.content
            dependencies += it.dependencies
          }
        },
      fixtureLibrary =
        fixtures?.let {
          {
            artifact = it.content
            dependencies += it.dependencies
          }
        },
    )

  internal fun toLibraryData(): LibraryData {
    return LibraryData(
      generateAarWithContent(
        groupId, // not actually used since we also pass a manifest string
        mainJar ?: jarWithEmptyEntries(listOf()),
        secondaryJars,
        resources,
        apiJar,
        lintJar,
        manifest ?: """<manifest package="$groupId"></manifest>""",
        proguard,
        extraFiles,
      ),
      dependencies,
    )
  }

  override fun withMainJar(action: JarBuilder.() -> Unit): AarBuilder {
    val builder = JarBuilderImpl()
    action(builder)
    mainJar = builder.getContent()
    return this
  }

  override fun setMainEmptyClasses(classBinaryNames: Collection<String>): AarBuilder {
    mainJar = JarBuilderImpl().also { it.addEmptyClasses(classBinaryNames) }.getContent()
    return this
  }

  override fun setMainEmptyClasses(vararg classBinaryNames: String): AarBuilder {
    mainJar = JarBuilderImpl().also { it.addEmptyClasses(classBinaryNames.toList()) }.getContent()
    return this
  }

  override fun setMainClasses(classes: Collection<Class<*>>): AarBuilder {
    mainJar = JarBuilderImpl().also { it.addClasses(classes) }.getContent()
    return this
  }

  override fun addSecondaryJar(name: String, action: JarBuilder.() -> Unit): AarBuilder {
    val builder = JarBuilderImpl()
    action(builder)
    secondaryJars[name] = builder.getContent()
    return this
  }

  override fun withApiJar(action: JarBuilder.() -> Unit): AarBuilder {
    val builder = JarBuilderImpl()
    action(builder)
    apiJar = builder.getContent()
    return this
  }

  override fun withManifest(content: String): AarBuilder {
    manifest = content
    return this
  }

  override fun withProguardRules(content: String): AarBuilder {
    proguard = content
    return this
  }

  override fun addResources(resMap: Map<String, String>): AarBuilder {
    for ((path, content) in resMap) {
      resources[path] = content.toByteArray(Charset.defaultCharset())
    }
    return this
  }

  override fun addResource(path: String, content: String): AarBuilder {
    resources[path] = content.toByteArray(Charset.defaultCharset())
    return this
  }

  override fun addResource(path: String, content: ByteArray): AarBuilder {
    resources[path] = content
    return this
  }

  override fun addAssets(assetMap: Map<String, String>): AarBuilder {
    for ((path, content) in assetMap) {
      extraFiles["assets/$path"] = content.toByteArray(Charset.defaultCharset())
    }
    return this
  }

  override fun addAsset(path: String, content: String): AarBuilder {
    extraFiles["assets/$path"] = content.toByteArray(Charset.defaultCharset())
    return this
  }

  override fun addAsset(path: String, content: ByteArray): AarBuilder {
    extraFiles["assets/$path"] = content
    return this
  }

  override fun withLintJar(action: JarBuilder.() -> Unit): AarBuilder {
    val builder = JarBuilderImpl()
    action(builder)
    lintJar = builder.getContent()
    return this
  }

  override fun withFixtures(action: AarBuilder.() -> Unit): AarBuilder {
    val builder = AarBuilderImpl("$groupId.fixtures", null, version)
    action(builder)
    fixtures = builder.toLibraryData()
    return this
  }

  override fun withDependencies(list: List<String>): AarBuilder {
    dependencies += list
    return this
  }

  override fun withAarMetadataProperties(properties: Map<String, String>): AarBuilder {
    val content = properties.entries.joinToString(separator = "\n") { (key, value) -> "$key=$value" }
    extraFiles[AAR_METADATA_ENTRY_PATH] = content.toByteArray()
    return this
  }
}

// IJ complains that we should implement equals(), etc... due to the array.
// Based on how/where this class is used, this is not necessary.
@Suppress("ArrayInDataClass") internal data class LibraryData(val content: ByteArray, val dependencies: List<String>)
