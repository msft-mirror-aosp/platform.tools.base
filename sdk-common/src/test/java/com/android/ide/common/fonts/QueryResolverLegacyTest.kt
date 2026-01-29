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

/** Test the v11 of the Query resolver. Use the API for QueryResolver to call QueryResolverLegacy.getSpecsForQuery. */
class QueryResolverLegacyTest {
  @Test
  fun openSansV11() {
    val result = parse("name=Open Sans&weight=600&width=110&italic=1")
    assertThat(result.fonts.keys()).hasSize(1)
    assertFontEqual(result.fonts["Open Sans"].first(), 600, 110f, ITALICS, EXACT)
  }

  @Test
  fun openSansWithExactMatchV11() {
    val result = parse("name=Open Sans&weight=600&italic=1.0&besteffort=false")
    assertThat(result.fonts.keys()).hasSize(1)
    assertFontEqual(result.fonts["Open Sans"].first(), 600, 100f, ITALICS, EXACT)
  }

  @Test
  fun openSansWithBestEffortMatchV11() {
    val result = parse("name=Open Sans&weight=800&width=90.0&besteffort=true")
    assertThat(result.fonts.keys()).hasSize(1)
    assertFontEqual(result.fonts["Open Sans"].first(), 800, 90f, NORMAL, BEST_EFFORT)
  }
}
