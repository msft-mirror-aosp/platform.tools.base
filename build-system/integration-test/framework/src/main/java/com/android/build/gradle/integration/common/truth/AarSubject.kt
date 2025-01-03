/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.build.gradle.integration.common.truth

import com.android.testutils.apk.Aar
import com.google.common.base.Charsets
import com.google.common.base.Preconditions
import com.google.common.truth.Fact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.StringSubject
import com.google.common.truth.Subject.Factory
import com.google.common.truth.Truth
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.util.stream.Collectors

/** Truth support for aar files.  */
class AarSubject(failureMetadata: FailureMetadata, subject: Aar) :
    AbstractAndroidSubject<AarSubject, Aar>(failureMetadata, subject) {
    init {
        validateAar()
    }

    private fun validateAar() {
        // only validate if the aar actually exists
        if (actual().exists() && actual().getEntry("AndroidManifest.xml") == null) {
            failWithoutActual(
                Fact.simpleFact("Invalid aar, should contain " + "AndroidManifest.xml")
            )
        }
    }

    fun textSymbolFile(): StringSubject {
        try {
            val entry = actual().getEntry("R.txt")
            Preconditions.checkNotNull(entry)
            return Truth.assertThat(String(Files.readAllBytes(entry), Charsets.UTF_8))
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    fun manifestFile(): StringSubject {
        try {
            return Truth.assertThat(actual().androidManifestContentsAsString)
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    /**
     * Asserts the subject contains an android resource at the given path with the specified String
     * content.
     *
     *
     * Content is trimmed when compared.
     */
    fun containsResourceWithContent(path: String, expected: String) {
        try {
            val resource = actual().getResource(path)
            if (resource == null) {
                failWithoutActual(
                    Fact.simpleFact("Resource " + path + " does not exist in " + actual())
                )
                return
            }
            val actual = Files.readAllLines(resource).stream().collect(Collectors.joining("\n"))
            if (expected != actual) {
                failWithoutActual(
                    Fact.simpleFact(
                        String.format(
                            "Resource %s in %s does not have expected contents."
                                    + " Expected '%s' actual '%s'",
                            path, actual(), expected, actual
                        )
                    )
                )
            }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    companion object {
        @JvmStatic
        fun aars(): Factory<AarSubject, Aar> {
            return Factory { failureMetadata: FailureMetadata, subject: Aar -> AarSubject(failureMetadata, subject) }
        }

        @JvmStatic
        fun assertThat(aar: Aar): AarSubject {
            return Truth.assertAbout(aars()).that(aar)
        }
    }
}
