/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.sdklib

import com.google.common.truth.StringSubject
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.fail

class AndroidVersionTest {

    /** Regression test for Issue 216736348 */
    @Test
    fun testAllowedCodenames() {
        val codenames = listOf("Tiramisu", "O_MR1", "S")
        codenames.forEach {
            val androidVersion = AndroidVersion.fromString(it)
            Truth.assertThat(androidVersion.codename).isEqualTo(it)
        }
    }

    @Test
    fun testDisallowedCodenames() {
        val codenames = listOf("tiramisu", "1S", "s")
        codenames.forEach {
            try {
                AndroidVersion.fromString(it)
                fail("expecting exception")
            } catch (expectedException: IllegalArgumentException) {
                // do nothing
            }
        }
    }

    @Test
    fun testMinorVersionNormalization() {
        fun assertAndroidVersionNormalized(from: String): StringSubject {
            return assertThat(AndroidVersion.fromString(from).getApiStringWithExtension())
                .named("AndroidVersion.fromString(\"%s\").getApiStringWithExtension()", from)
        }
        assertAndroidVersionNormalized("36").isEqualTo("36.0")
        assertAndroidVersionNormalized("36.0").isEqualTo("36.0")
        assertAndroidVersionNormalized("36.00").isEqualTo("36.0")
        assertAndroidVersionNormalized("36.1").isEqualTo("36.1")
        assertAndroidVersionNormalized("36.01").isEqualTo("36.1")
        assertAndroidVersionNormalized("37").isEqualTo("37.0")
        assertAndroidVersionNormalized("37.0").isEqualTo("37.0")
        assertAndroidVersionNormalized("37.00").isEqualTo("37.0")
        assertAndroidVersionNormalized("37.1").isEqualTo("37.1")
        assertAndroidVersionNormalized("37.01").isEqualTo("37.1")
    }

    @Test
    fun testToStringWithMinorVersions() {
        fun assertAndroidVersionToString(from: String) =
            assertThat(AndroidVersion.fromString(from).toString())
                .named("AndroidVersion.fromString(\"%s\").toString()", from)
        assertAndroidVersionToString("36.0").isEqualTo("API 36.0")
        assertAndroidVersionToString("36.1").isEqualTo("API 36.1")
        assertAndroidVersionToString("37").isEqualTo("API 37.0")
        assertAndroidVersionToString("37.1").isEqualTo("API 37.1")
    }

    @Test
    fun testBaseExtensionLevel() {
        fun assertBaseExtensionLevel(api: AndroidApiLevel) =
            assertThat(AndroidVersion.getBaseExtensionLevel(api))
                .named("AndroidVersion.getBaseExtensionLevel(\"%s\")", api)
        fun assertBaseExtensionLevel(api: Int) = assertBaseExtensionLevel(AndroidApiLevel(api))
        assertBaseExtensionLevel(30).isEqualTo(0)
        assertBaseExtensionLevel(31).isEqualTo(1)
        assertBaseExtensionLevel(32).isEqualTo(1)
        assertBaseExtensionLevel(33).isEqualTo(3)
        assertBaseExtensionLevel(34).isEqualTo(7)
        assertBaseExtensionLevel(35).isEqualTo(13)
        assertBaseExtensionLevel(36).isEqualTo(17)
        assertBaseExtensionLevel(AndroidApiLevel(36, 1)).isEqualTo(19)
    }

    @Test
    fun testBaseExtensionDetection() {
        fun assertFromStringBaseExtension(apiString: String) =
            assertThat(AndroidVersion.fromString(apiString).isBaseExtension)
                .named("AndroidVersion.fromString(\"%s\").isBaseExtension", apiString)

        assertFromStringBaseExtension("36.0").isTrue()
        assertFromStringBaseExtension("36.0-ext16").isTrue()
        assertFromStringBaseExtension("36.0-ext17").isTrue()
        assertFromStringBaseExtension("36.0-ext18").isFalse()
        assertFromStringBaseExtension("36.1").isTrue()
        assertFromStringBaseExtension("36.1-ext18").isTrue()
        assertFromStringBaseExtension("36.1-ext19").isTrue()
        assertFromStringBaseExtension("36.1-ext20").isFalse()
    }

}
