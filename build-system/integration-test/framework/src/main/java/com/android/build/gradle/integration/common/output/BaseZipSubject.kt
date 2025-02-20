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

import com.google.common.truth.Fact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Basic Truth subject to validate if the underlying zip archive exists.
 */
open class BaseZipSubject<S: Subject<S, T>, T: Zip> internal constructor(
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
                val zip = actual() as? SimpleZip
                    ?: throw RuntimeException("Zip other than SimpleZip should only be with status EXIST")

                var nearestParent: Path? =zip.archivePath
                while (nearestParent != null && !Files.exists(nearestParent)) {
                    nearestParent = nearestParent.parent
                }

                failWithoutActual(
                    Fact.fact("expected to exist", zip.archivePath),
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
            failWithActual(Fact.simpleFact("expected zip to not exist"))
        }
    }
}
