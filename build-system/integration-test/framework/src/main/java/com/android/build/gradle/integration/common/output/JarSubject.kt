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

package com.android.build.gradle.integration.common.output

import com.google.common.truth.FailureMetadata
import com.google.common.truth.IterableSubject
import com.google.common.truth.StringSubject
import com.google.common.truth.Truth.assertAbout
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Truth subject for a zip archive as a jar.
 *
 * This provides custom content validation, in a different way than [ZipSubject] does.
 */
@SubjectDsl
open class JarSubject(
    metadata: FailureMetadata,
    actual: Zip
): BaseZipSubject<JarSubject, Zip>(metadata, actual) {

    companion object {
        /**
         * Returns a [JarSubject]
         */
        fun assertThat(zip: Zip): JarSubject {
            return assertAbout(jars()).that(zip)
        }

        /**
         * Creates a [JarSubject] and configures it with the given action
         */
        fun assertThat(zip: Zip, action: JarSubject.() -> Unit) {
            action(assertThat(zip))
        }

        /**
         * Creates a [JarSubject] and configures it with the given action
         */
        fun assertThat(path: Path, action: JarSubject.() -> Unit) {
            SimpleZip(path).use {
                action(assertThat(it))
            }
        }

        /**
         * Creates a [JarSubject] and configures it with the given action
         */
        fun assertThat(file: File, action: JarSubject.() -> Unit) {
            assertThat(file.toPath(), action)
        }

        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun jars(): Factory<JarSubject, Zip> {
            return Factory<JarSubject, Zip> { metadata, actual ->
                JarSubject(metadata, actual)
            }
        }
    }

    /**
     * Checks if the zip file contains a given class.
     *
     * This is a shortcut to `classes().contains(path)`
     *
     * @param name the binary name of the class
     */
    fun containsClass(name: String) {
        exists()
        classes().contains(name)
    }

    /**
     * Checks if the zip file does not contain a given class
     *
     * This is a shortcut to `classes().doesNotContain(path)`
     *
     * @param name the binary name of the class
     */
    fun doesNotContainClass(name: String) {
        exists()
        classes().doesNotContain(name)
    }

    /**
     * Checks if the zip file contains a given resource.
     *
     * This is a shortcut to `resources().contains(path)`
     *
     * @param path the path of the resource
     */
    fun containsResource(path: String) {
        exists()
        resources().contains(path)
    }

    /**
     * Checks if the zip file does not contain a given resource
     *
     * This is a shortcut to `resources().doesNotContain(path)`
     *
     * @param path the path of the resource
     */
    fun doesNotContainResource(path: String) {
        exists()
        resources().doesNotContain(path)
    }

    /**
     * Returns a [IterableSubject] of all the classes in the jar (as [String] for binary names)
     */
    fun classes(): IterableSubject {
        exists()
        val values = actual().getEntries(PATTERN_CLASS_FILE).map { it.removeSuffix(".class") }
        return check("classes()").that(values)
    }

    /**
     * Returns a [IterableSubject] of all the resources in the jar
     */
    fun resources(): IterableSubject {
        exists()
        val values = actual().getEntries { !it.endsWith(".class") }
        return check("resources()").that(values)
    }

    /**
     * Returns a [StringSubject] with the text content of the file at the given path.
     *
     * @param path the path of the item which must not include a leading /
     */
    fun resourceAsText(path: String): StringSubject {
        containsResource(path)
        return check("resourceAsText($path)").that(actual().textFile(path))
    }

    /**
     * Returns a [BinarySubject] with the binary content of the file at the given path.
     *
     * @param path the path of the item which must not include a leading /
     */
    fun resourceAsBytes(path: String): BinarySubject {
        containsResource(path)
        return check("resourceAsBytes($path)").about(BinarySubject.bytes()).that(actual().binaryFile(path))
    }

    /**
     * Returns a [BinarySubject] with the binary content of class with the
     * given binary name.
     */
    fun classContent(binaryName: String): BinarySubject {
        classes().contains(binaryName)

        return check("classFile($binaryName)").about(BinarySubject.bytes()).that(actual().binaryFile(binaryName.toPath()))
    }

    /**
     * Returns a [ClassSubject] for the class with the given binary name
     */
    fun classData(binaryName: String): ClassSubject {
        classes().contains(binaryName)

        val classNode = ClassNode(Opcodes.ASM9)

        // this can be null when we're testing the fixture. In normal operation, the call
        // to contains above guarantees that it's not null
        actual().binaryFile(binaryName.toPath())?.let {
            ClassReader(it).accept(classNode, 0)
        }

        return check("classData($binaryName)")
            .about(ClassSubject.classNodes())
            .that(ClassDefinitionFromAsm(classNode))
    }

    /**
     * creates a [ClassSubject] for the class with the given binary name, and configures
     * it with the given action
     */
    fun classData(binaryName: String, action: ClassSubject.() -> Unit) {
        action(classData(binaryName))
    }

    /**
     * Converts a binary class name to a zip entry path
     */
    internal fun String.toPath(): String = "$this.class"
}

private val PATTERN_CLASS_FILE: Pattern = Pattern.compile("^.+\\.class$")
