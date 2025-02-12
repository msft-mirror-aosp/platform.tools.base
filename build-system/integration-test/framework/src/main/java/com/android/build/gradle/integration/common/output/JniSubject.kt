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

import com.android.build.gradle.integration.common.truth.NativeLibrarySubject
import com.android.utils.FileUtils
import com.google.common.truth.FailureMetadata
import com.google.common.truth.StringSubject
import com.google.common.truth.Subject
import com.google.common.truth.Truth.assertAbout
import java.nio.file.Files
import java.util.regex.Pattern

/**
 * An object that can validate the content of a folder of jni libraries.
 */
@SubjectDsl
interface JniSubject {
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

    fun library(path: String): NativeLibrarySubject

    /**
     * Returns a [BinarySubject] with the binary content of the file at the given path.
     *
     * @param path the path of the item which must not include a leading /
     */
    fun bytesOf(path: String): BinarySubject
}

/**
 * Implementation of [ResourcesSubject] over a Jar archive.
 *
 * The main goal here is to filter out the class files
 */
@SubjectDsl
internal class JniSubjectImpl(
    metadata: FailureMetadata,
    actual: Zip
): Subject<JniSubjectImpl, Zip>(metadata, actual), JniSubject {

    companion object {
        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun libs(): Factory<JniSubjectImpl, Zip> {
            return Factory<JniSubjectImpl, Zip> { metadata, actual ->
                JniSubjectImpl(metadata, actual)
            }
        }
    }

    override fun containsExactly(resourceNames: Iterable<String>) {
        check("entries()")
            .about(ComparatorSubject.lists())
            .that(actual().getEntries())
            .containsExactly(resourceNames)
    }

    override fun isEmpty() {
        check("entries()").that(actual().getEntries()).isEmpty()
    }

    override fun hasSize(size: Int) {
        check("size()").that(actual().getEntries().size).isEqualTo(size)
    }

    override fun bytesOf(path: String): BinarySubject {
        check("entries()").that(actual().getEntries()).contains(path)
        return check("resourceAsBytes($path)").about(BinarySubject.bytes()).that(actual().binaryFile(path))
    }

    override fun library(path: String): NativeLibrarySubject {
        check("entries()").that(actual().getEntries()).contains(path)

        val location = actual().getEntry(path)

        // we need to create a temporary file because the subject needs to run command lines against it.
        // TODO inject a TemporaryFolder rule?

        // location can be null when testing the fixture
        val nativeFile = location?.let {
            Files.createTempFile("nativeLibrary_", "_${location.fileName}").also {
                FileUtils.copyFile(location, it)
            }.toFile()
        } ?: Files.createTempFile("empty", ".so").toFile()

        nativeFile.deleteOnExit()

        return check("library($path)").about(NativeLibrarySubject.nativeLibraries()).that(nativeFile)
    }
}
