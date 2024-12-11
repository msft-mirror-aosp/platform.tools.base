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

class FontDetailTest {

    @Test
    fun testGenerateStyleName() {
        assertThat(generateStyleName(77, NORMAL)).isEqualTo("Custom-Light")
        assertThat(generateStyleName(100, NORMAL)).isEqualTo("Thin")
        assertThat(generateStyleName(200, NORMAL)).isEqualTo("Extra-Light")
        assertThat(generateStyleName(300, NORMAL)).isEqualTo("Light")
        assertThat(generateStyleName(400, NORMAL)).isEqualTo("Regular")
        assertThat(generateStyleName(500, NORMAL)).isEqualTo("Medium")
        assertThat(generateStyleName(600, NORMAL)).isEqualTo("Semi-Bold")
        assertThat(generateStyleName(700, NORMAL)).isEqualTo("Bold")
        assertThat(generateStyleName(800, NORMAL)).isEqualTo("Extra-Bold")
        assertThat(generateStyleName(900, NORMAL)).isEqualTo("Black")
        assertThat(generateStyleName(977, NORMAL)).isEqualTo("Custom-Bold")
    }

    @Test
    fun testGenerateStyleNameWithItalics() {
        assertThat(generateStyleName(67, ITALICS)).isEqualTo("Custom-Light Italic")
        assertThat(generateStyleName(100, ITALICS)).isEqualTo("Thin Italic")
        assertThat(generateStyleName(200, ITALICS)).isEqualTo("Extra-Light Italic")
        assertThat(generateStyleName(300, ITALICS)).isEqualTo("Light Italic")
        assertThat(generateStyleName(400, ITALICS)).isEqualTo("Regular Italic")
        assertThat(generateStyleName(500, ITALICS)).isEqualTo("Medium Italic")
        assertThat(generateStyleName(600, ITALICS)).isEqualTo("Semi-Bold Italic")
        assertThat(generateStyleName(700, ITALICS)).isEqualTo("Bold Italic")
        assertThat(generateStyleName(800, ITALICS)).isEqualTo("Extra-Bold Italic")
        assertThat(generateStyleName(900, ITALICS)).isEqualTo("Black Italic")
        assertThat(generateStyleName(901, ITALICS)).isEqualTo("Custom-Bold Italic")
    }

    @Test
    fun testConstructorAndGetters() {
        val family = createFontFamily(800, 120f, NORMAL, "http://someurl.com/myfont1.ttf", "MyStyle")
        val font = family.fonts[0]
        assertThat(font.family).isSameAs(family)
        assertThat(font.weight).isEqualTo(800)
        assertThat(font.width).isEqualTo(120f)
        assertThat(font.italics).isEqualTo(NORMAL)
        assertThat(font.fontUrl).isEqualTo("http://someurl.com/myfont1.ttf")
        assertThat(font.styleName).isEqualTo("MyStyle")
    }

    @Test
    fun testConstructorWithGeneratedStyleName() {
        val font = createFontDetail(800, 110f, ITALICS, "http://someurl.com/myfont2.ttf", "")
        assertThat(font.styleName).isEqualTo("Extra-Bold Italic")
    }

    @Test
    fun testDerivedConstructor() {
        val font = createFontDetail(800, 110f, ITALICS, "http://someurl.com/myfont2.ttf", "")
        val derived = FontDetail(font, MutableFontDetail(700, 100f, NORMAL, "whatever", "", false, false))
        assertThat(derived.family).isSameAs(font.family)
        assertThat(derived.weight).isEqualTo(700)
        assertThat(derived.width).isEqualTo(100f)
        assertThat(derived.italics).isEqualTo(NORMAL)
        assertThat(derived.fontUrl).isEqualTo("http://someurl.com/myfont2.ttf")
        assertThat(derived.styleName).isEqualTo("Bold")
    }

    @Test
    fun testGenerateQuery() {
        val font = createFontDetail(800, 110f, ITALICS, "http://someurl.com/myfont2.ttf", "")
        assertThat(font.generateQuery(true)).isEqualTo("name=MyFont&weight=800&italic=1&width=110&besteffort=false")
        assertThat(font.generateQuery(false)).isEqualTo("name=MyFont&weight=800&italic=1&width=110")
    }

    companion object {
        internal fun createFontDetail(weight: Int, width: Float, italics: Float, url: String, styleName: String): FontDetail {
            val family = createFontFamily(weight, width, italics, url, styleName)
            return family.fonts[0]
        }

        private fun createFontFamily(weight: Int, width: Float, italics: Float, url: String, styleName: String): FontFamily {
            return FontFamily(FontProvider.GOOGLE_PROVIDER, FontSource.DOWNLOADABLE, "MyFont", "http://someurl.com/mymenufont.ttf", "myMenu",
                    listOf(MutableFontDetail(weight, width, italics, url, styleName, false, false)))
        }

        private fun generateStyleName(weight: Int, italics: Float): String {
            val family = FontFamily(FontProvider.GOOGLE_PROVIDER, "San Serif")
            val font = MutableFontDetail(weight, DEFAULT_WIDTH, italics)
            val detail = FontDetail(family, font)
            return detail.styleName
        }
    }
}
