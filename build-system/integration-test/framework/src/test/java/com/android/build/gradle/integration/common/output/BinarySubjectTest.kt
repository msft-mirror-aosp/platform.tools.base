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

import com.google.common.truth.ExpectFailure
import com.google.common.truth.SimpleSubjectBuilder
import org.junit.Test

private val DATA_CAFEBABE: ByteArray =
    byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())

private val DATA_FEBA: ByteArray = byteArrayOf(0xFE.toByte(), 0xBA.toByte())
private val DATA_BABA: ByteArray = byteArrayOf(0xBA.toByte(), 0xBA.toByte())
private val DATA_EMPTY: ByteArray = byteArrayOf()

class BinarySubjectTest {

    @Test
    fun isEqualTo() {
        BinarySubject.assertThat(DATA_CAFEBABE).isEqualTo(DATA_CAFEBABE)

        // test negative results
        expectFailure {
            it.that(DATA_CAFEBABE).isEqualTo(DATA_FEBA)
        }
    }

    @Test
    fun isNotEqualTo() {
        BinarySubject.assertThat(DATA_CAFEBABE).isNotEqualTo(DATA_FEBA)

        // test negative results
        expectFailure {
            it.that(DATA_CAFEBABE).isNotEqualTo(DATA_CAFEBABE)
        }
    }

    @Test
    fun contains() {
        BinarySubject.assertThat(DATA_CAFEBABE).contains(DATA_FEBA)

        // test negative results
        expectFailure {
            it.that(DATA_CAFEBABE).contains(DATA_BABA)
        }
    }

    @Test
    fun doesNotContain() {
        BinarySubject.assertThat(DATA_CAFEBABE).doesNotContain(DATA_BABA)

        // test negative results
        expectFailure {
            it.that(DATA_CAFEBABE).doesNotContain(DATA_FEBA)
        }
    }

    @Test
    fun isEmpty() {
        BinarySubject.assertThat(DATA_EMPTY).isEmpty()

        // test negative results
        expectFailure {
            it.that(DATA_CAFEBABE).isEmpty()
        }
    }

    @Test
    fun isNotEmpty() {
        BinarySubject.assertThat(DATA_CAFEBABE).isNotEmpty()

        // test negative results
        expectFailure {
            it.that(DATA_EMPTY).isNotEmpty()
        }
    }

    @Test
    fun hasLength() {
        BinarySubject.assertThat(DATA_CAFEBABE).hasLength(4)

        // test negative results
        expectFailure {
            it.that(DATA_EMPTY).hasLength(4)
        }
    }

    // ------

    private fun expectFailure(action: (SimpleSubjectBuilder<BinarySubject, ByteArray>) -> Unit): AssertionError {
        return ExpectFailure.expectFailureAbout(BinarySubject.bytes(), action)
    }
}
