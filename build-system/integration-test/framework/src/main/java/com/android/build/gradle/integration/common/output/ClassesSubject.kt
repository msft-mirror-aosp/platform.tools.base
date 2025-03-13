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
@SubjectDsl
interface ClassesSubject {

    /**
     * Returns a [IterableSubject] of all the classes in the jar (as [String] for binary names)
     */
    @Deprecated("Use ClassesSubject.containsExactly directly")
    fun classes(): IterableSubject

    /**
     * Validates that the class list matches exactly with the provided list.
     *
     * The class list contains classes only. There are no folders in it.
     *
     * The format of the class names is using the binary format. For example:
     *   com/example/Foo$InnerClass
     *
     * The possible format of the items in the provided list includes
     * - normal binary class names
     * - packages/folders (ending with /), in which case it will match against any classes in the
     *   archive that are in that package.
     * - binary class names ending with `$` to match against a class and all its inner classes.
     */
    fun containsExactly(classNames: Iterable<String>)

    /**
     * Validates that the class list matches exactly with the provided list.
     *
     * The class list contains classes only. There are no folders in it.
     *
     * The format of the class names is using the binary format. For example:
     *   com/example/Foo$InnerClass
     *
     * The possible format of the items in the provided list includes
     * - normal binary class names
     * - packages/folders (ending with /), in which case it will match against any classes in the
     *   archive that are in that package.
     * - binary class names ending with `$` to match against a class and all its inner classes.
     */
    fun containsExactly(className: String) {
        containsExactly(listOf(className))
    }

    /**
     * Validates that the class list matches exactly with the provided list.
     *
     * The class list contains classes only. There are no folders in it.
     *
     * The format of the class names is using the binary format. For example:
     *   com/example/Foo$InnerClass
     *
     * The possible format of the items in the provided list includes
     * - normal binary class names
     * - packages/folders (ending with /), in which case it will match against any classes in the
     *   archive that are in that package.
     * - binary class names ending with `$` to match against a class and all its inner classes.
     */
    fun containsExactly(vararg classNames: String) {
        containsExactly(classNames.toList())
    }

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
    fun classDefinition(binaryName: String): ClassDefinitionSubject

    /**
     * creates a [ClassDefinitionSubject] for the class with the given binary name, and configures
     * it with the given action
     */
    fun classDefinition(binaryName: String, action: ClassDefinitionSubject.() -> Unit) {
        action(classDefinition(binaryName))
    }

    /**
     * Returns a [BinarySubject] with the binary content of class with the
     * given binary name.
     */
    fun classAsBytes(binaryName: String): BinarySubject
}

/**
 * Base Implementation of CodeSubject over dex files
 */
abstract internal class BaseDexSubject<S: Subject<S, T>, T>(
    metadata: FailureMetadata,
    actual: T
): Subject<S, T>(metadata, actual), ClassesSubject {

    @Deprecated("Use ClassesSubject.containsExactly directly")
    override fun classes(): IterableSubject {
        return check("classes()").that(allClasses.keys)
    }

    override fun containsExactly(classNames: Iterable<String>) {
        check("entries()")
            .about(ComparatorSubject.lists())
            .that(allClasses.keys)
            .containsExactly(classNames)
    }

    override fun isEmpty() {
        check("entries()").that(allClasses.keys).isEmpty()
    }

    override fun hasSize(size: Int) {
        check("size()").that(allClasses.keys.size).isEqualTo(size)
    }

    override fun classDefinition(binaryName: String): ClassDefinitionSubject {
        check("classes()").that(allClasses.keys).contains(binaryName)

        val classDef = allClasses[binaryName]!!

        return check("classDefinition($binaryName)")
            .about(ClassDefinitionSubject.classes())
            .that(ClassDefinitionFromDex(classDef))
    }

    /**
     * Implementation of [ClassesSubject.classAsBytes] over _all_ the dex in the APK
     */
    override fun classAsBytes(binaryName: String): BinarySubject {
        throw RuntimeException("classAsBytes not supported for dex files")
    }

    /**
     * Cached copy of all the class coming from dex files
     *
     * The names have been sanitized to not contain the L; wrapper.
     */
    protected abstract val allClasses: Map<String, DexBackedClassDef>
}

/**
 * Implementation of CodeSubject over one of more Dex files
 */
internal class DexSubject(
    metadata: FailureMetadata,
    actual: List<Dex>
): BaseDexSubject<DexSubject, List<Dex>>(metadata, actual), ClassesSubject {

    companion object {
        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun dexFiles(): Factory<DexSubject, List<Dex>> {
            return Factory<DexSubject, List<Dex>> { metadata, actual ->
                DexSubject(metadata, actual)
            }
        }
    }

    /**
     * Cached copy of all the class names.
     *
     * The names have been sanitized to not contain the L; wrapper.
     */
    override val allClasses: Map<String, DexBackedClassDef> by lazy(LazyThreadSafetyMode.NONE) {
        actual().flatMap {
            it.classes.entries.map { entry ->
                entry.key.substring(1, entry.key.length - 1) to entry.value
            }
        }.associateBy({ it.first }) { it.second }
    }

}

internal class DexClassesFromApkSubject(
    metadata: FailureMetadata,
    actual: Zip
): BaseDexSubject<DexClassesFromApkSubject, Zip>(metadata, actual), ClassesSubject {

    companion object {
        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun apk(): Factory<DexClassesFromApkSubject, Zip> {
            return Factory<DexClassesFromApkSubject, Zip> { metadata, actual ->
                DexClassesFromApkSubject(metadata, actual)
            }
        }
    }

    override val allClasses: Map<String, DexBackedClassDef> by lazy(LazyThreadSafetyMode.NONE) {
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
    @Deprecated("Use ClassesSubject.containsExactly directly")
    override fun classes(): IterableSubject {
        return check("classes()").that(classNames)
    }

    override fun containsExactly(classNames: Iterable<String>) {
        check("entries()")
            .about(ComparatorSubject.lists())
            .that(this.classNames)
            .containsExactly(classNames)
    }

    override fun isEmpty() {
         check("entries()").that(classNames).isEmpty()
    }

    override fun hasSize(size: Int) {
        check("size()").that(classNames.size).isEqualTo(size)
    }

    /**
     * Returns a [ClassDefinitionSubject] for the class with the given binary name
     */
    override fun classDefinition(binaryName: String): ClassDefinitionSubject {
        check("classes()").that(classNames).contains(binaryName)

        val classNode = ClassNode(Opcodes.ASM9)

        // this can be null when we're testing the fixture. In normal operation, the call
        // to contains above guarantees that it's not null
        actual().binaryFile(binaryName.toPath())?.let {
            ClassReader(it).accept(classNode, 0)
        }

        return check("classDefinition($binaryName)")
            .about(ClassDefinitionSubject.classes())
            .that(ClassDefinitionFromAsm(classNode))
    }

    override fun classAsBytes(binaryName: String): BinarySubject {
        check("classes()").that(classNames).contains(binaryName)
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
