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
import com.google.common.truth.Truth.assertAbout
import java.io.File
import java.nio.file.Path
import java.util.function.Consumer

/**
 * Generic Zip archive Truth subject
 */
@SubjectDsl
class ZipSubject(
    metadata: FailureMetadata,
    actual: Zip
): AbstractZipSubject<ZipSubject, Zip>(metadata, actual) {

    companion object {
        /**
         * Creates a [ZipSubject] and configures it with the given action
         */
        fun assertThat(path: Path, action: ZipSubject.() -> Unit) {
            SimpleZip(path).use {
                action(assertThat(it))
            }
        }

        /**
         * Creates a [ZipSubject] and configures it with the given action
         */
        fun assertThat(file: File, action: ZipSubject.() -> Unit) {
            assertThat(file.toPath(), action)
        }

        /**
         * Creates a [ZipSubject] and configures it with the given action
         */
        @JvmStatic
        fun assertThat(path: Path, action: Consumer<ZipSubject>) {
            assertThat(path) {
                action.accept(this)
            }
        }

        /**
         * Creates a [ZipSubject] and configures it with the given action
         */
        @JvmStatic
        fun assertThat(file: File, action: Consumer<ZipSubject>) {
            assertThat(file.toPath(), action)
        }

        /**
         * Returns a [ZipSubject]
         */
        internal fun assertThat(zip: Zip): ZipSubject {
            return assertAbout(zips()).that(zip)
        }

        /**
         * Creates a [ZipSubject] and configures it with the given action
         */
        internal fun assertThat(zip: Zip, action: ZipSubject.() -> Unit) {
            action(assertThat(zip))
        }

        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun zips(): Factory<ZipSubject, Zip> {
            return Factory<ZipSubject, Zip> { metadata, actual ->
                ZipSubject(metadata, actual)
            }
        }
    }

    /**
     * Validates that the archive file list matches exactly with the provided list.
     *
     * The archive list contains files only. There are no folders in it.
     *
     * The possible format of the items in the provided list includes both file path and folders.
     * In the case of folders, it will match against any files in the archive that are in that folder.
     */
    fun containsExactly(filePaths: Iterable<String>) {
        exists()
        check("entries()")
            .about(ComparatorSubject.lists())
            .that(actual().getEntries())
            .containsExactly(filePaths)
    }

    /**
     * Validates that the archive file list matches exactly with the provided list.
     *
     * The archive list contains files only. There are no folders in it.
     *
     * The possible format of the items in the provided list includes both file path and folders.
     * In the case of folders, it will match against any files in the archive that are in that folder.
     */
    fun containsExactly(filePath: String) {
        containsExactly(listOf(filePath))
    }

    /**
     * Validates that the archive file list matches exactly with the provided list.
     *
     * The archive list contains files only. There are no folders in it.
     *
     * The possible format of the items in the provided list includes both file path and folders.
     * In the case of folders, it will match against any files in the archive that are in that folder.
     */
    fun containsExactly(vararg filePaths: String) {
        containsExactly(filePaths.toList())
    }

    /**
     * Validates whether the archive is empty.
     */
    fun isEmpty() {
        entries().isEmpty()
    }
}
