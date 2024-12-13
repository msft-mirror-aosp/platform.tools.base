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

import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.JarBuilder
import com.android.build.gradle.integration.common.fixture.project.builder.JarContentBuilder
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.TestInputsGenerator
import com.android.utils.FileUtils
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V1_6
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DependenciesBuilderImpl() : DependenciesBuilder {
    private val dependencies = mutableListOf<Pair<String, Any>>()

    val externalLibraries: List<MavenRepoGenerator.Library>
        get() = dependencies
            .map { it.second }
            .filterIsInstance<MavenRepoGenerator.Library>()

    override fun clear() {
        dependencies.clear()
    }

    override fun implementation(dependency: Any) {
        dependencies.add("implementation" to dependency)
    }

    override fun api(dependency: Any) {
        dependencies.add("api" to dependency)
    }

    override fun compileOnly(dependency: Any) {
        dependencies.add("compileOnly" to dependency)
    }

    override fun compileOnlyApi(dependency: Any) {
        dependencies.add("compileOnlyApi" to dependency)
    }

    override fun runtimeOnly(dependency: Any) {
        dependencies.add("runtimeOnly" to dependency)
    }

    override fun testImplementation(dependency: Any) {
        dependencies.add("testImplementation" to dependency)
    }

    override fun testRuntimeOnly(dependency: Any) {
        dependencies.add("testRuntimeOnly" to dependency)
    }

    override fun androidTestImplementation(dependency: Any) {
        dependencies.add("androidTestImplementation" to dependency)
    }

    override fun include(dependency: Any) {
        dependencies.add("include" to dependency)
    }

    override fun requiredSdk(dependency: Any) {
        dependencies.add("requiredSdk" to dependency)
    }

    override fun optionalSdk(dependency: Any) {
        dependencies.add("optionalSdk" to dependency)
    }

    override fun lintPublish(dependency: Any) {
        dependencies.add("lintPublish" to dependency)
    }

    override fun lintChecks(dependency: Any) {
        dependencies.add("lintChecks" to dependency)
    }

    override fun screenshotTestImplementation(dependency: Any) {
        dependencies.add("screenshotTestImplementation" to dependency)
    }

    override fun localJar(name: String, action: JarBuilder.() -> Unit): LocalJarDependency =
            LocalJarBuilderImpl(name).also {
                action(it)
            }.toDependency()

    override fun files(path: Path): LocalFiles {
        return LocalFilesImpl(path)
    }

    override fun project(path: String, testFixtures: Boolean, configuration: String?): ProjectDependencyBuilder =
            ProjectDependencyBuilderImpl(path, testFixtures, configuration)

    override fun externalLibrary(path: String, testFixtures: Boolean): ExternalDependencyBuilder =
            ExternalDependencyBuilderImpl(path, testFixtures)

    override fun coreLibraryDesugaring(dependency: Any) {
        dependencies.add("coreLibraryDesugaring" to dependency)
    }

    override fun ksp(dependency: Any) {
        dependencies.add("ksp" to dependency)
    }

    fun writeBuildFile(sb: StringBuilder, projectDir: File) {
        sb.append("\ndependencies {\n")
        for ((scope, dependency) in dependencies) {
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
                for ((scope, dependency) in dependencies) {
                    when (dependency) {
                        is String -> dependency(scope, dependency)
                        is ExternalDependencyBuilder -> {
                            if (dependency.testFixtures) {
                                dependency(scope, rawMethod("testFixtures", dependency.coordinate))
                            } else {
                                dependency(scope, dependency.coordinate)
                            }
                        }

                        is ProjectDependencyBuilder -> {
                            val projectNotation = dependency.configuration?.let { configName ->
                                rawMethod("project", listOf(
                                    "path" to dependency.path,
                                    "configuration" to configName
                                ))
                            } ?: rawMethod("project", dependency.path)

                            val dep = if (dependency.testFixtures) {
                                rawMethod("testFixtures", projectNotation)
                            } else {
                                projectNotation
                            }

                            dependency(scope, dep)
                        }

                        is MavenRepoGenerator.Library -> dependency(scope, dependency.mavenCoordinate.toString())
                        is LocalJarDependency -> {
                            val path = createLocalJar(dependency, projectLocation.toFile())
                            dependency(scope, rawMethod("files", path))
                        }
                        is LocalFiles -> {
                            dependency(scope, rawMethod("files", dependency.path.toFile().toFormatted()))
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

private class LocalJarBuilderImpl(
    private val name: String = "foo.jar"
): JarBuilder {
    private var content: ByteArray? = null

    fun toDependency(): LocalJarDependency {
        return object : LocalJarDependency {
            override val name: String
                get() = this@LocalJarBuilderImpl.name
            override val content: ByteArray
                get() = this@LocalJarBuilderImpl.content ?: throw RuntimeException("no content set on localJar")
        }
    }

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

    override fun createJar(action: JarContentBuilder.() -> Unit): JarBuilder {
        val builder = JarContentBuilderImpl()
        action(builder)
        return setJar(builder.close())
    }
}

internal class JarContentBuilderImpl(): JarContentBuilder {
    private val byteArray = ByteArrayOutputStream()
    private val zip = ZipOutputStream(byteArray)

    internal fun close(): ByteArray {
        zip.close()
        return byteArray.toByteArray()
    }

    override fun addClassWithEmptyMethods(
        binaryClassName: String,
        vararg namesAndDescriptors: String
    ) {
        zip.putNextEntry(ZipEntry("$binaryClassName.class"))
        zip.write(classWithEmptyMethods(binaryClassName, *namesAndDescriptors))
        zip.closeEntry()
    }

    private fun classWithEmptyMethods(
        binaryClassName: String,
        vararg namesAndDescriptors: String
    ): ByteArray {
        val cw = ClassWriter(0)

        cw.visit(V1_6, ACC_PUBLIC + ACC_SUPER, binaryClassName, null, "java/lang/Object", null);

        addDefaultConstructor(cw);

        for (nameAndDescriptor: String in namesAndDescriptors) {
            val colon = nameAndDescriptor.indexOf(':')
            val methodName = nameAndDescriptor.substring(0, colon)
            val descriptor: String = nameAndDescriptor.substring(colon + 1, nameAndDescriptor.length)

            val mv = cw.visitMethod(ACC_PUBLIC, methodName, descriptor, null, null)
            mv.visitCode()
            // This bytecode is only valid for some signatures (void methods). This class is used
            // for testing the parser, we don't ever load these classes to a running VM anyway.
            mv.visitInsn(RETURN)
            mv.visitMaxs(0, 1)
            mv.visitEnd()
        }
        cw.visitEnd();

        return cw.toByteArray();
    }

    private fun  addDefaultConstructor(cw: ClassWriter) {
        val mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
    }
}

private data class LocalFilesImpl(override val path: Path): LocalFiles

private class ProjectDependencyBuilderImpl(
    override val path: String,
    override val testFixtures: Boolean,
    override val configuration: String? = null
): ProjectDependencyBuilder

private class ExternalDependencyBuilderImpl(
    override val coordinate: String,
    override val testFixtures: Boolean
): ExternalDependencyBuilder
