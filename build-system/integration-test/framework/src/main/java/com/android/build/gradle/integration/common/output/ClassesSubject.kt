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

import com.android.testutils.apk.Dex
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.google.common.truth.FailureMetadata
import com.google.common.truth.IterableSubject
import com.google.common.truth.Subject
import com.google.common.truth.Truth.assertAbout
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.util.regex.Pattern

/**
 * An object that can validate the content of code.
 */
interface ClassesSubject {

    /**
     * Returns a [IterableSubject] of all the classes in the jar (as [String] for binary names)
     */
    @Deprecated("Use containsExactly directly")
    fun classes(): IterableSubject

    /**
     * Checks that the list of classes contains a single entry matching the one provided
     */
    fun containsExactly(className: String)

    /**
     * Checks that the list of classes contains exactly the list provided
     */
    fun containsExactly(vararg classNames: String)

    /**
     * Checks that the list of classes is empty
     */
    fun isEmpty()

    /**
     * Checks that the list of classes has the given size
     */
    fun hasSize(size: Int)

    /**
     * Returns a [ClassDefinitionSubject] for the class with the given binary name
     */
    fun classDefinition(binaryName: String): ClassSubject

    /**
     * creates a [ClassDefinitionSubject] for the class with the given binary name, and configures
     * it with the given action
     */
    fun classDefinition(binaryName: String, action: ClassSubject.() -> Unit) {
        action(classDefinition(binaryName))
    }

    /**
     * Returns a [BinarySubject] with the binary content of class with the
     * given binary name.
     */
    fun classAsBytes(binaryName: String): BinarySubject
}

/**
 * Implementation of CodeSubject over a Dex file
 */
@SubjectDsl
internal class SingleDexSubject(
    metadata: FailureMetadata,
    actual: Dex
): Subject<SingleDexSubject, Dex>(metadata, actual), ClassesSubject {

    companion object {
        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun dexes(): Factory<SingleDexSubject, Dex> {
            return Factory<SingleDexSubject, Dex> { metadata, actual ->
                SingleDexSubject(metadata, actual)
            }
        }
    }

    @Deprecated("Use containsExactly directly")
    override fun classes(): IterableSubject {
        return check("classes()").that(classNames)
    }

    override fun containsExactly(className: String) {
        check("classes()").that(classNames).containsExactly(className)
    }

    override fun containsExactly(vararg classNames: String) {
        check("classes()").that(this.classNames).containsExactly(*classNames)
    }

    override fun isEmpty() {
        check("classes()").that(classNames).isEmpty()
    }

    override fun hasSize(size: Int) {
        check("classes()").that(classNames).hasSize(size)
    }

    override fun classDefinition(binaryName: String): ClassSubject {
        classes().contains(binaryName)
        val classDef = actual().classes["L$binaryName;"]!!

        return check("classByName($binaryName)")
            .about(ClassSubject.classNodes())
            .that(ClassDefinitionFromDex(classDef))
    }

    override fun classAsBytes(binaryName: String): BinarySubject {
        throw RuntimeException("classAsBytes not supported for dex files")
    }

    /**
     * Cached copy of all the class names.
     *
     * The names have been sanitized to not contain the L; wrapper.
     */
    private val classNames by lazy(LazyThreadSafetyMode.NONE) {
        // have to remove the L; wrapper from the name.
        actual().classes.keys.map { it.substring(1, it.length - 1) }
    }
}

@SubjectDsl
internal class MultiDexClassesSubject(
    metadata: FailureMetadata,
    actual: Zip
): Subject<MultiDexClassesSubject, Zip>(metadata, actual), ClassesSubject {

    companion object {
        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun multiDexes(): Factory<MultiDexClassesSubject, Zip> {
            return Factory<MultiDexClassesSubject, Zip> { metadata, actual ->
                MultiDexClassesSubject(metadata, actual)
            }
        }
    }

    @Deprecated("Use containsExactly directly")
    override fun classes(): IterableSubject {
        return check("classes()").that(allClasses.keys)
    }

    override fun containsExactly(className: String) {
        check("classes()").that(allClasses.keys).containsExactly(className)
    }

    override fun containsExactly(vararg classNames: String) {
        check("classes()").that(allClasses.keys).containsExactly(*classNames)
    }

    override fun isEmpty() {
        check("classes()").that(allClasses.keys).isEmpty()
    }

    override fun hasSize(size: Int) {
        check("classes()").that(allClasses.keys).hasSize(size)
    }

    override fun classDefinition(binaryName: String): ClassSubject {
        classes().contains(binaryName)

        val classDef = allClasses[binaryName]!!

        return check("classByName($binaryName)")
            .about(ClassSubject.classNodes())
            .that(ClassDefinitionFromDex(classDef))
    }

    /**
     * Implementation of [ClassesSubject.classAsBytes] over _all_ the dex in the APK
     */
    override fun classAsBytes(binaryName: String): BinarySubject {
        throw RuntimeException("classAsBytes not supported for dex files")
    }

    private val allClasses: Map<String, DexBackedClassDef> by lazy(LazyThreadSafetyMode.NONE) {
        val dexList = buildList {
            actual().getEntry("classes.dex")?.let { add(Dex(it)) }

            var index = 2
            // we cannot just take al these entries. we have to do the same that the runtime does, which
            // is search from 2, increasing the index each time. If there is a gap in the indices, then
            // the rest is ignored.
            do {
                val path = actual().getEntry("classes$index.dex") ?: return@buildList
                add(Dex(path))
                index++
            } while (true)
        }

        dexList.flatMap {
            it.classes.entries.map { entry ->
                entry.key.substring(1, entry.key.length - 1) to entry.value
            }
        }.associateBy({ it.first }) { it.second }
    }
}

/**
 * Implementation of [ClassesSubject] over a jar file
 */
@SubjectDsl
internal class JarWithClassesSubject(
    metadata: FailureMetadata,
    actual: Zip
): Subject<JarWithClassesSubject, Zip>(metadata, actual), ClassesSubject {
    companion object {
        fun assertThat(zip: Zip): JarWithClassesSubject {
            return assertAbout(jars()).that(zip)
        }

        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun jars(): Factory<JarWithClassesSubject, Zip> {
            return Factory<JarWithClassesSubject, Zip> { metadata, actual ->
                JarWithClassesSubject(metadata, actual)
            }
        }
    }

    /**
     * Returns a [IterableSubject] of all the classes in the jar (as [String] for binary names)
     */
    @Deprecated("Use containsExactly directly")
    override fun classes(): IterableSubject {
        return check("classes()").that(classNames)
    }

    override fun containsExactly(className: String) {
        check("classes()").that(classNames).containsExactly(className)
    }

    override fun containsExactly(vararg classNames: String) {
        check("classes()").that(this.classNames).containsExactly(*classNames)
    }

    override fun isEmpty() {
        check("classes()").that(classNames).isEmpty()
    }

    override fun hasSize(size: Int) {
        check("classes()").that(classNames).hasSize(size)
    }

    /**
     * Returns a [ClassDefinitionSubject] for the class with the given binary name
     */
    override fun classDefinition(binaryName: String): ClassSubject {
        classes().contains(binaryName)

        val classNode = ClassNode(Opcodes.ASM9)

        // this can be null when we're testing the fixture. In normal operation, the call
        // to contains above guarantees that it's not null
        actual().binaryFile(binaryName.toPath())?.let {
            ClassReader(it).accept(classNode, 0)
        }

        return check("classDefinition($binaryName)")
            .about(ClassSubject.classNodes())
            .that(ClassDefinitionFromAsm(classNode))
    }

    override fun classAsBytes(binaryName: String): BinarySubject {
        classes().contains(binaryName)
        val content = actual().binaryFile(binaryName.toPath())
        return check("classAsBytes($binaryName)")
            .about(BinarySubject.bytes())
            .that(content)
    }

    /**
     * Converts a binary class name to a zip entry path
     */
    internal fun String.toPath(): String = "$this.class"

    /**
     * Cached copy of all the class names.
     *
     * The names have been sanitized to not contain .class extension
     */
    private val classNames by lazy(LazyThreadSafetyMode.NONE) {
        actual().getEntries(PATTERN_CLASS_FILE).map { it.removeSuffix(".class") }
    }

}

private val PATTERN_CLASS_FILE: Pattern = Pattern.compile("^.+\\.class$")
