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

import com.android.ide.common.fonts.FontType.SINGLE
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MutableDetailTest {
  @Test
  fun testMatch() {
    val font1 = FontDetailTest.createFontDetail(SINGLE, 400, 100f, NORMAL, "http://someurl.com/myfont1.ttf", "MyStyle")

    val name = font1.family.name
    assertThat(MutableFontDetail(name, 400, 100f, NORMAL, false).match(font1)).isEqualTo(0f)
    assertThat(MutableFontDetail(name, 400, 100f, NORMAL, false).match(font1)).isEqualTo(0f)
    assertThat(MutableFontDetail(name, 300, 100f, NORMAL, false).match(font1)).isEqualTo(100f)
    assertThat(MutableFontDetail(name, 500, 100f, NORMAL, false).match(font1)).isEqualTo(100f)
    assertThat(MutableFontDetail(name, 900, 100f, NORMAL, false).match(font1)).isEqualTo(500f)
    assertThat(MutableFontDetail(name, 400, 90f, NORMAL, false).match(font1)).isEqualTo(10f)
    assertThat(MutableFontDetail(name, 400, 100f, ITALICS, false).match(font1)).isEqualTo(50f)
    assertThat(MutableFontDetail(name, 700, 120f, ITALICS, false).match(font1)).isEqualTo(370f)
  }

  @Test
  fun testFindBestMatch() {
    val font1 = FontDetailTest.createFontDetail(SINGLE, 400, 100f, NORMAL, "http://someurl.com/myfont1.ttf", "MyStyle")
    val font2 = FontDetailTest.createFontDetail(SINGLE, 400, 100f, ITALICS, "http://someurl.com/myfont2.ttf", "MyStyle")
    val font3 = FontDetailTest.createFontDetail(SINGLE, 700, 100f, NORMAL, "http://someurl.com/myfont3.ttf", "MyStyle")
    val font4 = FontDetailTest.createFontDetail(SINGLE, 700, 100f, ITALICS, "http://someurl.com/myfont4.ttf", "MyStyle")
    val fonts = listOf(font1, font2, font3, font4)

    val name = font1.family.name
    assertThat(MutableFontDetail(name, 900, 100f, ITALICS, false).findBestMatch(fonts)).isEqualTo(font4)
  }
}
