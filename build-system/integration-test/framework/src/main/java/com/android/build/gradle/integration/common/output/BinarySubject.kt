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

import com.google.common.primitives.Bytes
import com.google.common.truth.Fact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Truth.assertAbout

/**
 * Subject to do binary comparison since Truth does not really offer anything useful
 */
class BinarySubject internal constructor(
    metadata: FailureMetadata,
    actual: ByteArray?
): Subject<BinarySubject, ByteArray?>(metadata, actual) {

    companion object {
        /**
         * Returns a [BinarySubject]
         */
        internal fun assertThat(bytes: ByteArray?): BinarySubject {
            return assertAbout(bytes()).that(bytes)
        }

        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun bytes(): Factory<BinarySubject, ByteArray?> {
            return Factory<BinarySubject, ByteArray?> { metadata, actual ->
                BinarySubject(metadata, actual)
            }
        }
    }

    /**
     * Asserts that the actual and the provided [ByteArray] have the same content.
     */
    fun isEqualTo(content: ByteArray) {
        if (!array().contentEquals(content)) {
            failWithActual(
                Fact.fact("expected", content.contentToString()),
                Fact.fact("but was", actual().contentToString())
            )
        }
    }

    fun isNotEqualTo(content: ByteArray) {
        if (array().contentEquals(content)) {
            failWithActual(
                Fact.fact("expected", content.contentToString()),
                Fact.fact("but was", actual().contentToString())
            )
        }
    }

    fun contains(content: ByteArray) {
        val index: Int = Bytes.indexOf(array(), content)
        if (index == -1) {
            failWithActual(
                Fact.simpleFact("Did not find byte sequence")
            )
        }
    }

    fun doesNotContain(content: ByteArray) {
        val index: Int = Bytes.indexOf(array(), content)
        if (index != -1) {
            failWithActual(
                Fact.simpleFact("Found byte sequence ${content.contentToString()} at index $index")
            )
        }
    }

    fun isEmpty() {
        if (array().isNotEmpty()) {
            failWithActual(Fact.simpleFact("expected to be empty"))
        }
    }

    fun isNotEmpty() {
        if (array().isEmpty()) {
            failWithoutActual(Fact.simpleFact("expected not to be empty"))
        }
    }

    fun hasLength(length: Int) {
        check("size").that(array().size).isEqualTo(length)
    }

    private fun array(): ByteArray {
        val actual = actual()
        if (actual != null) return actual

        failWithoutActual(Fact.simpleFact("Array is null"))
        throw RuntimeException("Array is null")
    }
}
