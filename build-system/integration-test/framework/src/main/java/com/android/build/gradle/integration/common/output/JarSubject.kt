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
import com.google.common.truth.PrimitiveByteArraySubject
import com.google.common.truth.Truth.assertAbout
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.util.regex.Pattern

open class JarSubject(
    metadata: FailureMetadata,
    actual: Zip
): AbstractZipSubject<JarSubject, Zip>(metadata, actual) {

    companion object {
        /**
         * Returns a [com.android.build.gradle.integration.common.output.ZipSubject]
         */
        fun assertThat(zip: Zip): JarSubject {
            return assertAbout(jars()).that(zip)
        }

        /**
         * Creates a [com.android.build.gradle.integration.common.output.ZipSubject] and
         * configures it with the given action
         */
        fun assertThat(zip: Zip, action: JarSubject.() -> Unit) {
            action(assertThat(zip))
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
     * Returns a [PrimitiveByteArraySubject] with the binary content of class with the
     * given binary name.
     */
    fun classContent(binaryName: String): PrimitiveByteArraySubject {
        val path = binaryName.toPath()
        contains(path)
        return check("classFile($path)").that(actual().binaryFile(path))
    }

    /**
     * Returns a [ClassSubject] for the class with the given binary name
     */
    fun classData(binaryName: String): ClassSubject {
        val path = binaryName.toPath()
        contains(path)

        val classNode = ClassNode(Opcodes.ASM9)

        // this can be null when we're testing the fixture. In normal operation, the call
        // to contains above guarantees that it's not null
        actual().binaryFile(path)?.let {
            ClassReader(it).accept(classNode, 0)
        }

        return check("classData($binaryName)").about(ClassSubject.classNodes()).that(classNode)
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

private val PATTERN_CLASS_FILE = Pattern.compile("^.+\\.class$")
