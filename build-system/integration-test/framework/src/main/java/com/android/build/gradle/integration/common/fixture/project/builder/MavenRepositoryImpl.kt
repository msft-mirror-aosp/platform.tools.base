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

import com.android.testutils.MavenRepoGenerator
import com.android.testutils.MavenRepoGenerator.Library
import com.android.testutils.TestInputsGenerator
import com.android.testutils.TestInputsGenerator.jarWithEmptyEntries
import com.android.testutils.generateAarWithContent
import java.nio.charset.Charset

internal class MavenRepositoryImpl: MavenRepository {
    private val libraryList = mutableListOf<Library>()

    private val aarBuilders = mutableListOf<AarBuilderImpl>()
    private val jarBuilders = mutableListOf<JarBuilderImpl>()

    internal val libraries: List<Library>
        get() {
            val result = mutableListOf<Library>()
            result += libraryList
            result += aarBuilders.map { it.toLibrary() }
            result += jarBuilders.map { it.toLibrary() }
            return result.toList()
        }

    override fun library(library: Library) {
        libraryList.add(library)
    }

    override fun jar(mavenCoordinate: String): JarWithDependenciesBuilder =
        JarWithDependenciesBuilderImpl(mavenCoordinate).also {
            jarBuilders.add(it)
        }

    override fun aar(groupId: String, artifactId: String?, version: String): AarBuilder =
        AarBuilderImpl(groupId, artifactId, version).also {
            aarBuilders.add(it)
        }
}

internal class AarBuilderImpl(
    private val groupId: String,
    artifactId: String?,
    private val version: String
): AarBuilder {
    private val artifactId: String = artifactId ?: groupId.split('.').last()

    private var mainJar: ByteArray? = null
    private val secondaryJars = mutableMapOf<String, ByteArray>()
    private var apiJar: ByteArray? = null
    private var lintJar: ByteArray? = null
    private var manifest: String? = null
    private val resources = mutableMapOf<String, ByteArray>()
    private var fixtures: LibraryData? = null
    private val dependencies = mutableListOf<String>()

    internal fun toLibrary(): Library = MavenRepoGenerator.libraryWithFixtures(
        "$groupId:$artifactId:$version",
        "aar",
        mainLibrary = toLibraryData().let {
            {
                artifact = it.content
                dependencies += it.dependencies
            }
        },
        fixtureLibrary = fixtures?.let{
            {
                artifact = it.content
                dependencies += it.dependencies
            }
        }
    )

    private fun toLibraryData(): LibraryData {
        return LibraryData(
            generateAarWithContent(
                groupId, // not actually used since we also pass a manifest string
                mainJar ?: jarWithEmptyEntries(listOf()),
                secondaryJars,
                resources,
                apiJar,
                lintJar,
                manifest ?: """<manifest package="$groupId"></manifest>""",
                emptyList() // FIXME
            ),
            dependencies
        )
    }

    override fun withMainJar(action: JarBuilder.() -> Unit): AarBuilder {
        val builder = JarBuilderImpl("")
        action(builder)
        mainJar = builder.content ?: emptyJar()
        return this
    }

    override fun setMainEmptyClasses(classBinaryNames: Collection<String>): AarBuilder {
        mainJar = JarBuilderImpl("").also {
            it.setEmptyClasses(classBinaryNames)
        }.content // should not be null
        return this
    }

    override fun setMainEmptyClasses(vararg classBinaryNames: String): AarBuilder {
        mainJar = JarBuilderImpl("").also {
            it.setEmptyClasses(classBinaryNames.toList())
        }.content // should not be null
        return this
    }

    override fun setMainJar(jar: ByteArray): AarBuilder {
        mainJar = jar
        return this
    }

    override fun setMainClasses(classes: Collection<Class<*>>): AarBuilder {
        mainJar = JarBuilderImpl("").also {
            it.setClasses(classes)
        }.content // should not be null
        return this
    }

    override fun addSecondaryJar(name: String, action: JarBuilder.() -> Unit): AarBuilder {
        val builder = JarBuilderImpl("")
        action(builder)
        secondaryJars[name] = builder.content ?: emptyJar()
        return this
    }

    override fun withApiJar(action: JarBuilder.() -> Unit): AarBuilder {
        val builder = JarBuilderImpl("")
        action(builder)
        apiJar = builder.content
        return this
    }

    override fun withManifest(content: String): AarBuilder {
        manifest = content
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

    override fun withLintJar(action: JarBuilder.() -> Unit): AarBuilder {
        val builder = JarBuilderImpl("")
        action(builder)
        lintJar = builder.content
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
}

internal open class JarBuilderImpl(private val mavenCoordinate: String): JarBuilder {
    internal var content: ByteArray? = null
    protected val dependencies = mutableListOf<String>()

    internal fun toLibrary(): Library = Library(
        mavenCoordinate,
        "jar",
        content ?: emptyJar(),
        *dependencies.toTypedArray()
    )

    override fun setEmptyClasses(classBinaryNames: Collection<String>): JarBuilder {
        content = TestInputsGenerator.jarWithEmptyClasses(classBinaryNames)
        return this
    }

    override fun setEmptyClasses(vararg classBinaryNames: String): JarBuilder {
        content = TestInputsGenerator.jarWithEmptyClasses(classBinaryNames.toList())
        return this
    }

    override fun setJar(jar: ByteArray): JarBuilder {
        content = jar
        return this
    }

    override fun setClasses(classes: Collection<Class<*>>): JarBuilder {
        content = TestInputsGenerator.jarWithClasses(classes)
        return this
    }
}

internal class JarWithDependenciesBuilderImpl(
    mavenCoordinate: String
): JarBuilderImpl(mavenCoordinate), JarWithDependenciesBuilder {

    override fun withDependencies(list: List<String>): JarBuilder {
        dependencies += list
        return this
    }
}

// IJ complains that we should implement equals(), etc... due to the array.
// Based on how/where this class is used, this is not necessary.
@Suppress("ArrayInDataClass")
internal data class LibraryData(
    val content: ByteArray,
    val dependencies: List<String>
)

private fun emptyJar(): ByteArray = TestInputsGenerator.jarWithEmptyClasses(listOf())
