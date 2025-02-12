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
import com.google.common.truth.StringSubject
import com.google.common.truth.Subject
import com.google.common.truth.Truth.assertAbout
import java.util.regex.Pattern

/**
 * An object that can validate the content of resources.
 */
@SubjectDsl
interface ResourcesSubject {

    /**
     * Checks that the list of classes contains exactly the list provided
     */
    fun containsExactly(resourceNames: Iterable<String>)

    /**
     * Checks that the list of classes contains a single entry matching the one provided
     */
    fun containsExactly(resourceName: String) {
        containsExactly(listOf(resourceName))
    }

    /**
     * Checks that the list of classes contains exactly the list provided
     */
    fun containsExactly(vararg resourceNames: String) {
        containsExactly(resourceNames.toList())
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
     * Returns a [StringSubject] with the text content of the file at the given path.
     *
     * @param path the path of the item which must not include a leading /
     */
    fun resourceAsText(path: String): StringSubject

    /**
     * Returns a [BinarySubject] with the binary content of the file at the given path.
     *
     * @param path the path of the item which must not include a leading /
     */
    fun resourceAsBytes(path: String): BinarySubject

    fun folderView(path: String): ResourcesSubject
}

internal abstract class BaseJavaResourcesSubject<S: Subject<S, T>, T: Zip>(
    metadata: FailureMetadata,
    actual: T
): Subject<S, T>(metadata, actual), ResourcesSubject {

    protected abstract val allResources: List<String>

    override fun containsExactly(resourceNames: Iterable<String>) {
        check("entries()")
            .about(ComparatorSubject.lists())
            .that(allResources)
            .containsExactly(resourceNames)
    }

    override fun isEmpty() {
        check("entries()").that(allResources).isEmpty()
    }

    override fun hasSize(size: Int) {
        check("size()").that(allResources.size).isEqualTo(size)
    }

    override fun resourceAsText(path: String): StringSubject {
        check("entries()").that(allResources).contains(path)
        return check("resourceAsText($path)").that(actual().textFile(path))
    }

    override fun resourceAsBytes(path: String): BinarySubject {
        check("entries()").that(allResources).contains(path)
        return check("resourceAsBytes($path)").about(BinarySubject.bytes()).that(actual().binaryFile(path))
    }
}

/**
 * Implementation of [ResourcesSubject] over an APK archive.
 *
 * The main goal here is to filter out the non java resource files
 */
internal class ApkWithJavaResourcesSubject(
    metadata: FailureMetadata,
    actual: Zip
): BaseJavaResourcesSubject<ApkWithJavaResourcesSubject, Zip>(metadata, actual) {

    companion object {
        /**
         * Returns a [ResourcesSubject]
         */
        internal fun assertThat(zip: Zip): ApkWithJavaResourcesSubject {
            return assertAbout(javaResources()).that(zip)
        }

        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun javaResources(): Factory<ApkWithJavaResourcesSubject, Zip> {
            return Factory<ApkWithJavaResourcesSubject, Zip> { metadata, actual ->
                ApkWithJavaResourcesSubject(metadata, actual)
            }
        }
    }

    override fun folderView(path: String): ResourcesSubject {
        val view = ZipFolderView(actual(), path)
        return check("folderView($path)").about(javaResources()).that(view)
    }

    /**
     * Cached copy of all the resources names.
     */
    override val allResources: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        // the list of java resources is basically everything expect some know items
        actual().getEntries {
            when {
                it == "AndroidManifest.xml" || it == "classes.dex" || it == "resources.arsc" -> false
                it.startsWith("res/") || it.startsWith("lib/") || it.startsWith("assets/") -> false
                PATTERN_SECONDARY_DEXES.matcher(it).matches() -> false
                else -> true
            }
        }
    }
}

/**
 * Implementation of [ResourcesSubject] over a Jar archive.
 *
 * The main goal here is to filter out the class files
 */
internal class JarWithJavaResourcesSubject(
    metadata: FailureMetadata,
    actual: Zip
): BaseJavaResourcesSubject<JarWithJavaResourcesSubject, Zip>(metadata, actual) {

    companion object {
        /**
         * Returns a [ResourcesSubject]
         */
        internal fun assertThat(zip: Zip): JarWithJavaResourcesSubject {
            return assertAbout(jars()).that(zip)
        }

        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun jars(): Factory<JarWithJavaResourcesSubject, Zip> {
            return Factory<JarWithJavaResourcesSubject, Zip> { metadata, actual ->
                JarWithJavaResourcesSubject(metadata, actual)
            }
        }
    }

    override fun folderView(path: String): ResourcesSubject {
        val view = ZipFolderView(actual(), path)
        return check("folderView($path)").about(jars()).that(view)
    }

    /**
     * Cached copy of all the resources names.
     */
    override val allResources: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        // the list of java resources is basically everything expect class files
        actual().getEntries {
            when {
                it.endsWith(".class") -> false
                else -> true
            }
        }
    }
}

/**
 * Implementation of [ResourcesSubject] over an archive with no filtering of any sort
 */
@SubjectDsl
internal class FullJarResourcesSubject(
    metadata: FailureMetadata,
    actual: Zip
): BaseJavaResourcesSubject<FullJarResourcesSubject, Zip>(metadata, actual) {

    companion object {
        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun jars(): Factory<FullJarResourcesSubject, Zip> {
            return Factory<FullJarResourcesSubject, Zip> { metadata, actual ->
                FullJarResourcesSubject(metadata, actual)
            }
        }
    }

    override fun folderView(path: String): ResourcesSubject {
        val view = ZipFolderView(actual(), path)
        return check("folderView($path)").about(jars()).that(view)
    }


    /**
     * Cached copy of all the resources names.
     */
    override val allResources: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        actual().getEntries()
    }
}

private val PATTERN_SECONDARY_DEXES: Pattern = Pattern.compile("classes\\d+.dex")
