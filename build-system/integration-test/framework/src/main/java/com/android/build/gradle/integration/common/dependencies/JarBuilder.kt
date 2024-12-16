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

import com.android.build.gradle.integration.common.fixture.testprojects.LocalJarDependency
import com.android.testutils.MavenRepoGenerator.Library
import com.android.testutils.TestInputsGenerator
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V1_6
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A object to create a jar
 */
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
    /**
     * Creates a jar
     */
    fun createJar(action: JarContentBuilder.() -> Unit): JarBuilder
}

/**
 * A builder to create the content of a jar
 */
interface JarContentBuilder {
    fun addClassWithEmptyMethods(binaryClassName: String, vararg namesAndDescriptors: String)
}

/**
 * A jar with dependencies
 */
interface JarWithDependenciesBuilder: JarBuilder {

    /**
     * Sets the dependencies of the Jar
     */
    fun withDependencies(list: List<String>): JarBuilder

    override fun setEmptyClasses(classBinaryNames: Collection<String>): JarWithDependenciesBuilder
    override fun setEmptyClasses(vararg classBinaryNames: String): JarWithDependenciesBuilder
    override fun setJar(jar: ByteArray): JarWithDependenciesBuilder
    override fun setClasses(classes: Collection<Class<*>>): JarWithDependenciesBuilder
}

// ----------

/**
 * Implementation of [JarBuilder] specifically for
 * [com.android.build.gradle.integration.common.fixture.project.builder.MavenRepository.jar]
 */
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

    override fun createJar(action: JarContentBuilder.() -> Unit): JarBuilder {
        val builder = JarContentBuilderImpl()
        action(builder)
        return setJar(builder.close())
    }
}

/**
 * Implementation of [JarWithDependenciesBuilder] specifically for
 * [com.android.build.gradle.integration.common.fixture.project.builder.MavenRepository.jar]
 */
internal class JarWithDependenciesBuilderImpl(
    mavenCoordinate: String
): JarBuilderImpl(mavenCoordinate), JarWithDependenciesBuilder {

    override fun withDependencies(list: List<String>): JarBuilder {
        dependencies += list
        return this
    }

    override fun setEmptyClasses(classBinaryNames: Collection<String>): JarWithDependenciesBuilder {
        super.setEmptyClasses(classBinaryNames)
        return this
    }

    override fun setEmptyClasses(vararg classBinaryNames: String): JarWithDependenciesBuilder {
        super.setEmptyClasses(*classBinaryNames)
        return this
    }

    override fun setJar(jar: ByteArray): JarWithDependenciesBuilder {
        super.setJar(jar)
        return this
    }

    override fun setClasses(classes: Collection<Class<*>>): JarWithDependenciesBuilder {
        super.setClasses(classes)
        return this
    }
}


/**
 * Implementation of [JarBuilder] specifically for use with
 * [com.android.build.gradle.integration.common.fixture.testprojects.DependenciesBuilder.localJar]
 */
internal class LocalJarBuilderImpl(
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

internal fun emptyJar(): ByteArray = TestInputsGenerator.jarWithEmptyClasses(listOf())
