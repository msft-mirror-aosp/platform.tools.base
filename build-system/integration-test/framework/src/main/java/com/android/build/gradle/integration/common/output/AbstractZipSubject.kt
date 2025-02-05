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
import com.google.common.truth.Fact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.IterableSubject
import com.google.common.truth.PrimitiveByteArraySubject
import com.google.common.truth.StringSubject
import com.google.common.truth.Subject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Base Truth subject for all Zip archive types.
 */
open class AbstractZipSubject<S: Subject<S, T>, T: Zip> internal constructor(
    metadata: FailureMetadata,
    actual: T
): Subject<S, T>(metadata, actual) {

    /**
     * Checks if the zip file exists.
     */
    fun exists() {
        when (actual().status) {
            Zip.Status.EXISTS -> {
                // nothing to be done here.
            }
            Zip.Status.DIRECTORY -> {
                failWithoutActual(Fact.simpleFact("points to a directory"))
            }
            Zip.Status.DOES_NOT_EXIST -> {
                var nearestParent: Path? = actual().archivePath
                while (nearestParent != null && !Files.exists(nearestParent)) {
                    nearestParent = nearestParent.parent
                }

                failWithoutActual(
                    Fact.fact("expected to exist", actual().archivePath),
                    Fact.fact("nearest existing ancestor", nearestParent)
                )
            }
        }
    }

    /**
     * Checks if the zip file does not exist.
     */
    fun doesNotExist() {
        if (actual().exists()) {
            failWithoutActual(Fact.simpleFact("does not exist"))
        }
    }

    /**
     * Checks if the zip file contains a given path.
     *
     * This is a shortcut to `entries().contains(path)`
     */
    fun contains(path: String) {
        exists()

        val path = path.asPath()
        entries().contains(path)
    }

    // --------------

    /**
     * Returns a [IterableSubject] of all the Zip entries (as [String])
     */
    fun entries(): IterableSubject {
        exists()
        return check("entries()").that(actual().getEntries().map { it.toString() })
    }

    /**
     * Returns a [StringSubject] with the text content of the file at the given path.
     */
    fun textFile(path: String): StringSubject {
        val path = path.asPath()
        contains(path)
        return check("textFile($path)").that(actual().textFile(path))
    }

    /**
     * Returns a [PrimitiveByteArraySubject] with the binary content of the file at the given path.
     */
    fun binaryFile(path: String): PrimitiveByteArraySubject {
        val path = path.asPath()
        contains(path)
        return check("binaryFile($path)").that(actual().binaryFile(path))
    }

    /**
     * Returns a [ZipSubject] with the zip content of the file at the given path.
     */
    fun innerZip(path: String): ZipSubject {
        val path = path.asPath()
        contains(path)
        return check("innerZip($path)").about(zips()).that(actual().innerZip(path))
    }

    fun innerZip(path: String, action: ZipSubject.() -> Unit) {
        action(innerZip(path))
    }

    // --------------

    /**
     * Ensures the zip archive path is correct by prepending a '/' if needed.
     */
    protected fun String.asPath() = if (startsWith('/')) this else "/$this"
}
