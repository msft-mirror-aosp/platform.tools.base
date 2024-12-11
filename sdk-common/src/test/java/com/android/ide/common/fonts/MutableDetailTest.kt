/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.ide.common.fonts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MutableDetailTest {
    @Test
    fun testMatch() {
        val font1 = FontDetailTest.createFontDetail(
                400, 100f, NORMAL, "http://someurl.com/myfont1.ttf", "MyStyle")

        assertThat(MutableFontDetail(400, 100f, NORMAL).match(font1)).isEqualTo(0f)
        assertThat(MutableFontDetail(400, 100f, NORMAL).match(font1)).isEqualTo(0f)
        assertThat(MutableFontDetail(300, 100f, NORMAL).match(font1)).isEqualTo(100f)
        assertThat(MutableFontDetail(500, 100f, NORMAL).match(font1)).isEqualTo(100f)
        assertThat(MutableFontDetail(900, 100f, NORMAL).match(font1)).isEqualTo(500f)
        assertThat(MutableFontDetail(400, 90f, NORMAL).match(font1)).isEqualTo(10f)
        assertThat(MutableFontDetail(400, 100f, ITALICS).match(font1)).isEqualTo(50f)
        assertThat(MutableFontDetail(700, 120f, ITALICS).match(font1)).isEqualTo(370f)
    }

    @Test
    fun testFindBestMatch() {
        val font1 = FontDetailTest.createFontDetail(
                400, 100f, NORMAL, "http://someurl.com/myfont1.ttf", "MyStyle")
        val font2 = FontDetailTest.createFontDetail(
                400, 100f, ITALICS, "http://someurl.com/myfont2.ttf", "MyStyle")
        val font3 = FontDetailTest.createFontDetail(
                700, 100f, NORMAL, "http://someurl.com/myfont3.ttf", "MyStyle")
        val font4 = FontDetailTest.createFontDetail(
                700, 100f, ITALICS, "http://someurl.com/myfont4.ttf", "MyStyle")
        val fonts = listOf(font1, font2, font3, font4)

        assertThat(MutableFontDetail(900, 100f, ITALICS).findBestMatch(fonts)).isEqualTo(font4)
    }
}
