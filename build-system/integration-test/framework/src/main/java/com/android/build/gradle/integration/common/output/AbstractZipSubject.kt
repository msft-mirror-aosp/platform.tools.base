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

import com.android.build.gradle.integration.common.output.ZipSubject.Companion.zips
import com.google.common.truth.FailureMetadata
import com.google.common.truth.IterableSubject
import com.google.common.truth.PrimitiveByteArraySubject
import com.google.common.truth.StringSubject
import com.google.common.truth.Subject

/**
 * Base Truth subject for all Zip archive types, providing basic validation for the content.
 */
open class AbstractZipSubject<S: Subject<S, T>, T: Zip> internal constructor(
    metadata: FailureMetadata,
    actual: T
): BaseZipSubject<S, T>(metadata, actual) {

    /**
     * Checks if the zip file contains a given path.
     *
     * This is a shortcut to `entries().contains(path)`
     *
     * @param path the path of the item which must not include a leading /
     */
    fun contains(path: String) {
        exists()
        entries().contains(path)
    }

    /**
     * Checks if the zip file does not contain a given path.
     *
     * This is a shortcut to `entries().doesNotContain(path)`
     *
     * @param path the path of the item which must not include a leading /
     */
    fun doesNotContain(path: String) {
        exists()
        entries().doesNotContain(path)
    }

    // --------------

    /**
     * Returns a [IterableSubject] of all the Zip entries (as [String])
     */
    fun entries(): IterableSubject {
        exists()
        return check("entries()").that(actual().getEntries())
    }

    /**
     * Returns a [StringSubject] with the text content of the file at the given path.
     *
     * @param path the path of the item which must not include a leading /
     */
    fun textFile(path: String): StringSubject {
        contains(path)
        return check("textFile($path)").that(actual().textFile(path))
    }

    /**
     * Returns a [PrimitiveByteArraySubject] with the binary content of the file at the given path.
     *
     * @param path the path of the item which must not include a leading /
     */
    fun binaryFile(path: String): PrimitiveByteArraySubject {
        contains(path)
        return check("binaryFile($path)").that(actual().binaryFile(path))
    }

    /**
     * Returns a [ZipSubject] with the zip content of the file at the given path.
     *
     * @param path the path of the item which must not include a leading /
     */
    fun innerZip(path: String): ZipSubject {
        contains(path)
        return check("innerZip($path)").about(zips()).that(actual().innerZip(path))
    }

    /**
     * creates a [ZipSubject] with the zip content of the file at the given path,
     * and configures it with the provided action
     *
     * @param path the path of the item which must not include a leading /
     */
    fun innerZip(path: String, action: ZipSubject.() -> Unit) {
        action(innerZip(path))
    }
}
