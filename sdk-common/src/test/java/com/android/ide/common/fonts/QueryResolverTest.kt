/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.ide.common.fonts.FontMatching.BEST_EFFORT
import com.android.ide.common.fonts.FontMatching.EXACT
import com.google.common.truth.Truth.assertThat
import org.junit.Test

internal enum class FontMatching {
  EXACT,
  BEST_EFFORT,
}

class QueryResolverTest {

  @Test
  fun allWeightSpecifications() {
    val result = parse("Roboto:100,wght200,300italic,400i")
    assertThat(result.fonts.keys()).hasSize(4)
    assertFontEqual(result.fonts["Roboto"].elementAt(0), 100, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(1), 200, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(2), 300, 100f, ITALICS, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(3), 400, 100f, ITALICS, EXACT)
  }

  @Test
  fun allWeight100Synonyms() {
    val result =
      parse(
        "Roboto:thin,extralight,extra-light,ultralight,ultra-light,l,light,r,regular,book,medium,semibold,semi-bold," +
          "demibold,demi-bold,b,bold,extrabold,extra-bold,ultrabold,ultra-bold,black,heavy"
      )
    assertThat(result.fonts.keys()).hasSize(23)
    assertFontEqual(result.fonts["Roboto"].elementAt(0), 100, 100f, NORMAL, EXACT) // thin
    assertFontEqual(result.fonts["Roboto"].elementAt(1), 200, 100f, NORMAL, EXACT) // extralight
    assertFontEqual(result.fonts["Roboto"].elementAt(2), 200, 100f, NORMAL, EXACT) // extra-light
    assertFontEqual(result.fonts["Roboto"].elementAt(3), 200, 100f, NORMAL, EXACT) // ultralight
    assertFontEqual(result.fonts["Roboto"].elementAt(4), 200, 100f, NORMAL, EXACT) // ultra-light
    assertFontEqual(result.fonts["Roboto"].elementAt(5), 300, 100f, NORMAL, EXACT) // l
    assertFontEqual(result.fonts["Roboto"].elementAt(6), 300, 100f, NORMAL, EXACT) // light
    assertFontEqual(result.fonts["Roboto"].elementAt(7), 400, 100f, NORMAL, EXACT) // r
    assertFontEqual(result.fonts["Roboto"].elementAt(8), 400, 100f, NORMAL, EXACT) // regular
    assertFontEqual(result.fonts["Roboto"].elementAt(9), 400, 100f, NORMAL, EXACT) // book
    assertFontEqual(result.fonts["Roboto"].elementAt(10), 500, 100f, NORMAL, EXACT) // medium
    assertFontEqual(result.fonts["Roboto"].elementAt(11), 600, 100f, NORMAL, EXACT) // semibold
    assertFontEqual(result.fonts["Roboto"].elementAt(12), 600, 100f, NORMAL, EXACT) // semi-bold
    assertFontEqual(result.fonts["Roboto"].elementAt(13), 600, 100f, NORMAL, EXACT) // demibold
    assertFontEqual(result.fonts["Roboto"].elementAt(14), 600, 100f, NORMAL, EXACT) // demi-bold
    assertFontEqual(result.fonts["Roboto"].elementAt(15), 700, 100f, NORMAL, EXACT) // b
    assertFontEqual(result.fonts["Roboto"].elementAt(16), 700, 100f, NORMAL, EXACT) // bold
    assertFontEqual(result.fonts["Roboto"].elementAt(17), 800, 100f, NORMAL, EXACT) // extrabold
    assertFontEqual(result.fonts["Roboto"].elementAt(18), 800, 100f, NORMAL, EXACT) // extra-bold
    assertFontEqual(result.fonts["Roboto"].elementAt(19), 800, 100f, NORMAL, EXACT) // ultrabold
    assertFontEqual(result.fonts["Roboto"].elementAt(20), 800, 100f, NORMAL, EXACT) // ultra-bold
    assertFontEqual(result.fonts["Roboto"].elementAt(21), 900, 100f, NORMAL, EXACT) // black
    assertFontEqual(result.fonts["Roboto"].elementAt(22), 900, 100f, NORMAL, EXACT) // heavy
  }

  @Test
  fun robotoWidth() {
    val result = parse("Roboto:100:wdth90,wght200:wdth110")
    assertThat(result.fonts.keys()).hasSize(2)
    assertFontEqual(result.fonts["Roboto"].elementAt(0), 100, 90f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(1), 200, 110f, NORMAL, EXACT)
  }

  @Test
  fun allItalics() {
    val result = parse("Roboto:200i,300italic,ital0.0,ital0.5,ital1.0,italic,i,bolditalic,bi")
    assertThat(result.fonts.keys()).hasSize(9)
    assertFontEqual(result.fonts["Roboto"].elementAt(0), 200, 100f, ITALICS, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(1), 300, 100f, ITALICS, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(2), 400, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(3), 400, 100f, 0.5f, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(4), 400, 100f, ITALICS, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(5), 400, 100f, ITALICS, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(6), 400, 100f, ITALICS, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(7), 700, 100f, ITALICS, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(8), 700, 100f, ITALICS, EXACT)
  }

  @Test
  fun openSans() {
    val result = parse("Open+Sans")
    assertThat(result.fonts.keys()).hasSize(1)
    assertFontEqual(result.fonts["Open Sans"].first(), 400, 100f, NORMAL, EXACT)
  }

  @Test
  fun openSansWithMultipleWeights() {
    val result = parse("Open+Sans:300,600,700")
    assertThat(result.fonts.keys()).hasSize(3)
    assertFontEqual(result.fonts["Open Sans"].elementAt(0), 300, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Open Sans"].elementAt(1), 600, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Open Sans"].elementAt(2), 700, 100f, NORMAL, EXACT)
  }

  @Test
  fun picaItalics() {
    val result = parse("IM+Fell+DW+Pica:italic")
    assertThat(result.fonts.keys()).hasSize(1)
    assertFontEqual(result.fonts["IM Fell DW Pica"].first(), 400, 100f, ITALICS, EXACT)
  }

  @Test
  fun droidSansBoldItalic() {
    val result = parse("Droid+Sans:bolditalic")
    assertThat(result.fonts.keys()).hasSize(1)
    assertFontEqual(result.fonts["Droid Sans"].first(), 700, 100f, ITALICS, EXACT)
  }

  @Test
  fun droidSansBoldItalicShortCut() {
    val result = parse("Droid+Sans:bi")
    assertThat(result.fonts.keys()).hasSize(1)
    assertFontEqual(result.fonts["Droid Sans"].first(), 700, 100f, ITALICS, EXACT)
  }

  @Test
  fun droidSansBoldItalicAndBold() {
    val result = parse("Droid+Sans:bolditalic,b")
    assertThat(result.fonts.keys()).hasSize(2)
    assertFontEqual(result.fonts["Droid Sans"].elementAt(0), 700, 100f, ITALICS, EXACT)
    assertFontEqual(result.fonts["Droid Sans"].elementAt(1), 700, 100f, NORMAL, EXACT)
  }

  @Test
  fun multiple() {
    val result = parse("Tangerine|Inconsolata")
    assertThat(result.fonts.keys()).hasSize(2)
    assertFontEqual(result.fonts["Tangerine"].first(), 400, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Inconsolata"].first(), 400, 100f, NORMAL, EXACT)
  }

  @Test
  fun multipleWithStyles() {
    val result = parse("Tangerine:b|Inconsolata:r,400i")
    assertThat(result.fonts.keys()).hasSize(3)
    assertFontEqual(result.fonts["Tangerine"].first(), 700, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Inconsolata"].elementAt(0), 400, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Inconsolata"].elementAt(1), 400, 100f, ITALICS, EXACT)
  }

  @Test
  fun multipleWithStyles2() {
    val result = parse("Tangerine:b|Inconsolata:r,400:ital0.8")
    assertThat(result.fonts.keys()).hasSize(3)
    assertFontEqual(result.fonts["Tangerine"].first(), 700, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Inconsolata"].elementAt(0), 400, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Inconsolata"].elementAt(1), 400, 100f, 0.8f, EXACT)
  }

  @Test
  fun moreMultipleWithStyles() {
    val result = parse("Open+Sans:400,700|Roboto:700|Slabo+27px:400")
    assertThat(result.fonts.keys()).hasSize(4)
    assertFontEqual(result.fonts["Open Sans"].elementAt(0), 400, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Open Sans"].elementAt(1), 700, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(0), 700, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Slabo 27px"].elementAt(0), 400, 100f, NORMAL, EXACT)
  }

  @Test
  fun tangerineWithVariant() {
    val result = parse("Tangerine:r,b")
    assertThat(result.fonts.keys()).hasSize(2)
    assertFontEqual(result.fonts["Tangerine"].elementAt(0), 400, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Tangerine"].elementAt(1), 700, 100f, NORMAL, EXACT)
  }

  @Test
  fun robotoWithVariant() {
    val result = parse("Roboto:300,400,500,600,700,800,900,900italic")
    assertThat(result.fonts.keys()).hasSize(8)
    assertFontEqual(result.fonts["Roboto"].elementAt(0), 300, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(1), 400, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(2), 500, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(3), 600, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(4), 700, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(5), 800, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(6), 900, 100f, NORMAL, EXACT)
    assertFontEqual(result.fonts["Roboto"].elementAt(7), 900, 100f, ITALICS, EXACT)
  }

  @Test
  fun nearestVersusExact() {
    val result = parse("Tangerine:600:nearest,800")
    assertThat(result.fonts.keys()).hasSize(2)
    assertFontEqual(result.fonts["Tangerine"].elementAt(0), 600, 100f, NORMAL, BEST_EFFORT)
    assertFontEqual(result.fonts["Tangerine"].elementAt(1), 800, 100f, NORMAL, EXACT)
  }
}

internal fun parse(query: String): DownloadableParseResult {
  val result = QueryResolver.parseDownloadableFont(GOOGLE_FONT_AUTHORITY, query)
  assertThat(result.authority).isEqualTo(GOOGLE_FONT_AUTHORITY)
  return result
}

internal fun assertFontEqual(
  font: MutableFontDetail,
  expectedWeight: Int,
  expectedWidth: Float,
  expectedItalics: Float,
  expectedMatching: FontMatching,
) {
  assertThat(font.weight).isEqualTo(expectedWeight)
  assertThat(font.width).isEqualTo(expectedWidth)
  assertThat(font.italics).isEqualTo(expectedItalics)
  assertThat(font.exact).isEqualTo(expectedMatching == EXACT)
}
