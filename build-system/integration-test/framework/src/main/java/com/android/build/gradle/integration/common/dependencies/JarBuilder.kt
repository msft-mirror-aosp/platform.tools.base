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

import com.android.build.gradle.integration.common.fixture.project.builder.GradleDefinitionDsl
import com.android.testutils.MavenRepoGenerator.Library
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V1_6
import org.objectweb.asm.Type
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A object to create a jar
 */
@GradleDefinitionDsl
interface JarBuilder {
    /**
     * adds empty classes to the Jar
     * @param classBinaryNames the binary class names to add
     */
    fun addEmptyClasses(classBinaryNames: Collection<String>): JarBuilder
    /**
     * adds empty classes to the Jar
     * @param classBinaryNames the binary class names to add
     */
    fun addEmptyClasses(vararg classBinaryNames: String): JarBuilder

    /**
     * adds existing classes to the Jar
     * @param classBinaryNames the binary class names to add
     */
    fun addClasses(classes: Collection<Class<*>>): JarBuilder
    /**
     * adds existing classes to the Jar
     * @param classBinaryNames the binary class names to add
     */
    fun addClasses(vararg classes: Class<*>): JarBuilder

    /**
     * adds a single class to the Jar
     * @param classBinaryName the binary class name to add
     * @param namesAndDescriptors a list of method to add using the descriptor format
     */
    fun addClassWithEmptyMethods(classBinaryName: String, vararg namesAndDescriptors: String): JarBuilder

    /**
     * adds a text file with the provided content
     */
    fun addTextFile(path: String, content: String): JarBuilder

    /**
     * adds multiple text files with the provided content
     */
    fun addTextFiles(entries: List<Pair<String, String>>): JarBuilder

    /**
     * adds a binary file with the provided content
     */
    fun addBinaryFile(path: String, content: ByteArray): JarBuilder
}

/**
 * A jar with dependencies
 */
@GradleDefinitionDsl
interface JarWithDependenciesBuilder: JarBuilder {
    /**
     * Sets the dependencies of the Jar
     */
    fun withDependencies(vararg list: String): JarWithDependenciesBuilder

    // redefine these methods to provide new return type

    override fun addEmptyClasses(classBinaryNames: Collection<String>): JarWithDependenciesBuilder
    override fun addEmptyClasses(vararg classBinaryNames: String): JarWithDependenciesBuilder
    override fun addClasses(classes: Collection<Class<*>>): JarWithDependenciesBuilder
    override fun addClasses(vararg classes: Class<*>): JarWithDependenciesBuilder
    override fun addClassWithEmptyMethods(classBinaryName: String, vararg namesAndDescriptors: String): JarWithDependenciesBuilder
    override fun addTextFile(path: String, content: String): JarWithDependenciesBuilder
    override fun addTextFiles(entries: List<Pair<String, String>>): JarWithDependenciesBuilder
    override fun addBinaryFile(path: String, content: ByteArray): JarWithDependenciesBuilder
}

// ----------

/**
 * Base Implementation of [JarBuilder]
 */
internal open class JarBuilderImpl: JarBuilder {
    private val jarContentBuilder = JarContentBuilder()

    internal fun getContent(): ByteArray = jarContentBuilder.getContent()

    override fun addEmptyClasses(classBinaryNames: Collection<String>): JarBuilder {
        classBinaryNames.forEach(jarContentBuilder::addEmptyClass)
        return this
    }

    override fun addEmptyClasses(vararg classBinaryNames: String): JarBuilder {
        return addEmptyClasses(classBinaryNames.toList())
    }

    override fun addClasses(classes: Collection<Class<*>>): JarBuilder {
        classes.forEach(jarContentBuilder::addClass)
        return this
    }

    override fun addClasses(vararg classes: Class<*>): JarBuilder {
        return addClasses(classes.toList())
    }

    override fun addClassWithEmptyMethods(classBinaryName: String, vararg namesAndDescriptors: String): JarBuilder {
        jarContentBuilder.addClassWithEmptyMethods(classBinaryName, *namesAndDescriptors)
        return this
    }

    override fun addTextFile(path: String, content: String): JarBuilder {
        jarContentBuilder.addTextEntry(path, content)
        return this
    }

    override fun addTextFiles(entries: List<Pair<String, String>>): JarBuilder {
        entries.forEach {
            addTextFile(it.first, it.second)
        }
        return this
    }

    override fun addBinaryFile(path: String, content: ByteArray): JarBuilder {
        jarContentBuilder.addBinaryEntry(path, content)
        return this
    }
}

/**
 * Implementation of [JarWithDependenciesBuilder] specifically for
 * [com.android.build.gradle.integration.common.fixture.project.builder.MavenRepository.jar]
 */
internal class JarWithDependenciesBuilderImpl(
    private val mavenCoordinate: String
): JarBuilderImpl(), JarWithDependenciesBuilder {

    protected val dependencies = mutableListOf<String>()

    internal fun toLibrary(): Library = Library(
        mavenCoordinate,
        "jar",
        getContent(),
        *dependencies.toTypedArray()
    )

    override fun withDependencies(vararg list: String): JarWithDependenciesBuilderImpl {
        dependencies += list
        return this
    }

    override fun addEmptyClasses(classBinaryNames: Collection<String>): JarWithDependenciesBuilder {
        super.addEmptyClasses(classBinaryNames)
        return this
    }

    override fun addEmptyClasses(vararg classBinaryNames: String): JarWithDependenciesBuilder {
        super.addEmptyClasses(*classBinaryNames)
        return this
    }

    override fun addClasses(classes: Collection<Class<*>>): JarWithDependenciesBuilder {
        super.addClasses(classes)
        return this
    }

    override fun addClasses(vararg classes: Class<*>): JarWithDependenciesBuilder {
        super.addClasses(*classes)
        return this
    }

    override fun addClassWithEmptyMethods(classBinaryName: String, vararg namesAndDescriptors: String): JarWithDependenciesBuilder {
        super.addClassWithEmptyMethods(classBinaryName, *namesAndDescriptors)
        return this
    }

    override fun addTextFile(path: String, content: String): JarWithDependenciesBuilder {
        super.addTextFile(path, content)
        return this
    }

    override fun addTextFiles(entries: List<Pair<String, String>>): JarWithDependenciesBuilder {
        super.addTextFiles(entries)
        return this
    }

    override fun addBinaryFile(path: String, content: ByteArray): JarWithDependenciesBuilder {
        super.addBinaryFile(path, content)
        return this
    }
}

internal class JarContentBuilder {
    private val byteArray = ByteArrayOutputStream()
    private val zip = ZipOutputStream(byteArray)
    private var closed = false

    internal fun addEmptyClass(binaryClassName: String) {
        if (closed) throw RuntimeException("cannot call addEmptyClass after getContent")
        zip.putNextEntry(ZipEntry("$binaryClassName.class"))
        zip.write(createClass(binaryClassName))
        zip.closeEntry()
    }

    internal fun addClassWithEmptyMethods(
        binaryClassName: String,
        vararg namesAndDescriptors: String
    ) {
        if (closed) throw RuntimeException("cannot call addClassWithEmptyMethods after getContent")
        zip.putNextEntry(ZipEntry("$binaryClassName.class"))
        zip.write(createClass(binaryClassName) {
            for (nameAndDescriptor: String in namesAndDescriptors) {
                val colon = nameAndDescriptor.indexOf('(')
                val methodName = nameAndDescriptor.substring(0, colon)
                val descriptor: String = nameAndDescriptor.substring(colon, nameAndDescriptor.length)

                val mv = visitMethod(ACC_PUBLIC, methodName, descriptor, null, null)
                mv.visitCode()
                // This bytecode is only valid for some signatures (void methods). This class is used
                // for testing the parser, we don't ever load these classes to a running VM anyway.
                mv.visitInsn(RETURN)
                mv.visitMaxs(0, 1)
                mv.visitEnd()
            }
        })
        zip.closeEntry()
    }

    internal fun addClass(theClass: Class<*>) {
        if (closed) throw RuntimeException("cannot call addClass after getContent")

        val binaryName = Type.getInternalName(theClass)
        val classPath = "$binaryName.class"
        val content = theClass.getClassLoader().getResourceAsStream(classPath)?.use { it ->
            it.readAllBytes()
        } ?: throw RuntimeException("Unable to load content of  $binaryName")

        zip.putNextEntry(ZipEntry(classPath))
        zip.write(content)
        zip.closeEntry()
    }

    internal fun addTextEntry(path: String, content: String) {
        if (closed) throw RuntimeException("cannot call addTextEntry after getContent")
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    internal fun addBinaryEntry(path: String, content: ByteArray) {
        if (closed) throw RuntimeException("cannot call addBinaryEntry after getContent")
        zip.putNextEntry(ZipEntry(path))
        zip.write(content)
        zip.closeEntry()
    }

    private fun createClass(
        binaryClassName: String,
        action: (ClassWriter.() -> Unit)? = null
    ): ByteArray {
        val cw = ClassWriter(0)

        cw.visit(V1_6, ACC_PUBLIC + ACC_SUPER, binaryClassName, null, "java/lang/Object", null)
        addDefaultConstructor(cw)

        action?.let { it(cw) }

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

    internal fun getContent(): ByteArray {
        closed = true
        zip.close()
        return byteArray.toByteArray()
    }
}
