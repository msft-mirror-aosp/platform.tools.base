/*
 * Copyright (C) 2021 The Android Open Source Project
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
import com.android.build.gradle.integration.common.dependencies.JarBuilderImpl
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.testutils.MavenRepoGenerator
import com.android.utils.FileUtils
import java.io.File
import java.nio.file.Path

class DependenciesBuilderImpl() : DependenciesBuilder {
    private val dependencies = mutableListOf<Pair<String, DependencyData>>()

    private data class DependencyData(
        val dependency: Any,
        val capability: String? = null
    )

    private class DependencyBuilderImpl: DependencyBuilder {
        internal var capability: String? = null

        override fun requireCapability(capability: String) {
            this.capability = capability
        }
    }

    val externalLibraries: List<MavenRepoGenerator.Library>
        get() = dependencies
            .map { it.second.dependency }
            .filterIsInstance<MavenRepoGenerator.Library>()

    override fun clear() {
        dependencies.clear()
    }

    private fun handleDependency(scope: String, dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        val data = action?.let {
            val builder = DependencyBuilderImpl()
            action(builder)
            DependencyData(dependency, builder.capability)
        } ?: DependencyData(dependency)

        dependencies.add(scope to data)
    }

    override fun implementation(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("implementation", dependency, action)
    }

    override fun api(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("api", dependency, action)
    }

    override fun compileOnly(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("compileOnly", dependency, action)
    }

    override fun compileOnlyApi(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("compileOnlyApi", dependency, action)
    }

    override fun runtimeOnly(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("runtimeOnly", dependency, action)
    }

    override fun testImplementation(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("testImplementation", dependency, action)
    }

    override fun testRuntimeOnly(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("testRuntimeOnly", dependency, action)
    }

    override fun androidTestImplementation(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("androidTestImplementation", dependency, action)
    }

    override fun include(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("include", dependency, action)
    }

    override fun requiredSdk(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("requiredSdk", dependency, action)
    }

    override fun optionalSdk(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("optionalSdk", dependency, action)
    }

    override fun lintPublish(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("lintPublish", dependency, action)
    }

    override fun lintChecks(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("lintChecks", dependency, action)
    }

    override fun screenshotTestImplementation(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("screenshotTestImplementation", dependency, action)
    }

    override fun coreLibraryDesugaring(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("coreLibraryDesugaring", dependency, action)
    }

    override fun ksp(dependency: Any, action: (DependencyBuilder.() -> Unit)?) {
        handleDependency("ksp", dependency, action)
    }

    private class LocalJarDependencyImpl(
        override val name: String,
        override val content: ByteArray
    ): LocalJarDependency

    override fun localJar(name: String, action: JarBuilder.() -> Unit): LocalJarDependency {
        val builder = JarBuilderImpl().also {
            action(it)
        }

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

    fun writeBuildFile(sb: StringBuilder, projectDir: File) {
        sb.append("\ndependencies {\n")
        for ((scope, dependencyData) in dependencies) {
            val dependency = dependencyData.dependency
            if (dependencyData.capability != null) throw RuntimeException("Capability is not support in fixtures other than GradleRule")
            when (dependency) {
                is String -> sb.append("$scope '$dependency'\n")
                is ExternalDependencyBuilder -> {
                    if (dependency.testFixtures) {
                        sb.append("$scope testFixtures('${dependency.coordinate}')\n")
                    } else {
                        sb.append("$scope '${dependency.coordinate}'\n")
                    }
                }
                is ProjectDependencyBuilder -> {
                    val projectStr = dependency.configuration?.let { configName ->
                        "project(path: '${dependency.path}', configuration: '$configName')"
                    } ?: "project('${dependency.path}')"

                    val dependencyStr = if (dependency.testFixtures) {
                        "testFixtures($projectStr)"
                    } else {
                        projectStr
                    }

                    sb.append("$scope $dependencyStr\n")
                }
                is MavenRepoGenerator.Library -> sb.append("$scope '${dependency.mavenCoordinate}'\n")
                is LocalJarDependency -> {
                    val path = createLocalJar(dependency, projectDir)
                    sb.append("$scope files('$path')\n")
                }
                is LocalFiles -> {
                    sb.append("$scope files('${dependency.path}')\n")
                }
                else -> throw RuntimeException("unsupported dependency type: ${dependency.javaClass}")
            }
        }

        sb.append("}\n")
    }

    fun write(buildWriter: BuildWriter, projectLocation: Path) {
        if (dependencies.isEmpty()) return

        buildWriter.apply {
            block("dependencies") {
                for ((scope, dependencyData) in dependencies) {
                    val dependency = dependencyData.dependency
                    val capability = dependencyData.capability
                    when (dependency) {
                        is String -> dependency(scope, dependency, capability)
                        is ExternalDependencyBuilder -> {
                            if (dependency.testFixtures) {
                                dependency(scope, rawMethod("testFixtures", dependency.coordinate), capability)
                            } else {
                                dependency(scope, dependency.coordinate, capability)
                            }
                        }

                        is ProjectDependencyBuilder -> {
                            val dep = getDependencyNotationForProject(dependency)

                            dependency(scope, dep, capability)
                        }

                        is PlatformDependency -> {
                            val path = (dependency.path as? ProjectDependencyBuilder)?.let {
                                getDependencyNotationForProject(it)
                            } ?: dependency.path

                            val dep = rawMethod("platform", path)
                            dependency(scope, dep, capability)
                        }

                        is MavenRepoGenerator.Library -> dependency(scope, dependency.mavenCoordinate.toString(), capability)
                        is LocalJarDependency -> {
                            val path = createLocalJar(dependency, projectLocation.toFile())
                            dependency(scope, rawMethod("files", path), capability)
                        }
                        is LocalFiles -> {
                            dependency(scope, rawMethod("files", dependency.path.toFile().toFormatted()), capability)
                        }
                        else -> throw RuntimeException("unsupported dependency type: ${dependency.javaClass}")
                    }
                }
            }

            emptyLine()
        }
    }

    private fun File.toFormatted(): String {
        return if (this.isAbsolute){
            toURI().toString()
        } else {
            // in this case, we want to make sure this is using / even on window as the
            // gradle (groovy) API requires this
            toString().replace('\\', '/')
        }
    }

    private fun BuildWriter.getDependencyNotationForProject(
        dependency: ProjectDependencyBuilder
    ): BuildWriter.RawString {
        val projectNotation = dependency.configuration?.let { configName ->
            rawMethod(
                "project", listOf(
                    "path" to dependency.path,
                    "configuration" to configName
                )
            )
        } ?: rawMethod("project", dependency.path)

        val dep = if (dependency.testFixtures) {
            rawMethod("testFixtures", projectNotation)
        } else {
            projectNotation
        }
        return dep
    }

    private fun createLocalJar(
        localJarDependency : LocalJarDependency,
        projectDir: File,
    ): String {
        val libsFolder = File(projectDir, "libs")
        FileUtils.mkdirs(libsFolder)
        val jarFile = File(libsFolder, localJarDependency.name)
        jarFile.writeBytes(localJarDependency.content)

        return "libs/${localJarDependency.name}"
    }
}

private data class LocalFilesImpl(override val path: Path): LocalFiles

private class ProjectDependencyBuilderImpl(
    override val path: String,
    override val testFixtures: Boolean,
    override val configuration: String? = null
): ProjectDependencyBuilder

private class PlatformDependencyImpl(
    override val path: Any
) : PlatformDependency

private class ExternalDependencyBuilderImpl(
    override val coordinate: String,
    override val testFixtures: Boolean
): ExternalDependencyBuilder
