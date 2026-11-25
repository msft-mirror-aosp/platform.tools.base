/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tools.render

import com.android.tools.render.common.ResolvedScreenshotLayout
import com.android.tools.render.compose.ComposeScreenshot
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class LayoutResultIdAllocatorTest {

  private fun allocateAll(displayNames: List<String?>, baseResultId: String = "Card"): List<String> {
    val screenshot =
      ComposeScreenshot(
        methodFQN = "com.example.HomeKt.$baseResultId",
        methodParams = emptyList(),
        previewParams = emptyMap(),
        previewId = "com.example.HomeKt.$baseResultId",
      )
    val layouts = displayNames.map { ResolvedScreenshotLayout("<View />", displayName = it) }
    val allocator = LayoutResultIdAllocator(screenshot, layouts, baseResultId)
    var index = 0
    return layouts.map { layout -> allocator.nextResultId(layout) { index++ } }
  }

  @Test
  fun distinctDisplayNamesPreserveOriginalCasing() {
    assertThat(allocateAll(listOf("Dark", "Light", null, "   "))).containsExactly("Card_Dark", "Card_Light", "Card_2", "Card_3").inOrder()
  }

  @Test
  fun caseOnlyDuplicateDisplayNamesReceiveDistinctIndicesAndPreserveCasing() {
    val resultIds = allocateAll(listOf("Dark", "dark", "DARK"))

    assertThat(resultIds).containsExactly("Card_Dark_0", "Card_dark_1", "Card_DARK_2").inOrder()
    assertThat(resultIds.map { it.lowercase(Locale.ROOT) }).containsNoDuplicates()
  }

  @Test
  fun sanitizedCaseOnlyDuplicatesAreDisambiguated() {
    val resultIds = allocateAll(listOf("Dark Mode", "dark/mode", "_DARK:MODE_"))

    assertThat(resultIds).containsExactly("Card_Dark_Mode_0", "Card_dark_mode_1", "Card_DARK_MODE_2").inOrder()
    assertThat(resultIds.map { it.lowercase(Locale.ROOT) }).containsNoDuplicates()
  }

  @Test
  fun explicitSuffixCollidingCaseInsensitivelyWithGeneratedSuffixIsDisambiguated() {
    // Explicit uppercase DARK_1 after case-colliding Dark/dark must not reuse dark_1.
    val afterCollision = allocateAll(listOf("Dark", "dark", "DARK_1"))
    assertThat(afterCollision).containsExactly("Card_Dark_0", "Card_dark_1", "Card_DARK_1_2").inOrder()
    assertThat(afterCollision.map { it.lowercase(Locale.ROOT) }).containsNoDuplicates()

    // Explicit uppercase DARK_1 before case-colliding Dark/dark forces Dark's generated Dark_1 to disambiguate.
    val beforeCollision = allocateAll(listOf("DARK_1", "Dark", "dark"))
    assertThat(beforeCollision).containsExactly("Card_DARK_1", "Card_Dark_1_1", "Card_dark_2").inOrder()
    assertThat(beforeCollision.map { it.lowercase(Locale.ROOT) }).containsNoDuplicates()
  }

  @Test
  fun chainedExplicitSuffixCollisionsStayCaseInsensitivelyUnique() {
    val resultIds = allocateAll(listOf("DARK_2_2", "DARK_2", "Dark", "dark"))

    assertThat(resultIds).containsExactly("Card_DARK_2_2", "Card_DARK_2", "Card_Dark_2_2_1", "Card_dark_3").inOrder()
    assertThat(resultIds.map { it.lowercase(Locale.ROOT) }).containsNoDuplicates()
  }

  @Test
  fun blankDisplayNameCollidingWithExplicitNumericSuffixStaysUnique() {
    val resultIds = allocateAll(listOf("2_2", "2", null))

    assertThat(resultIds).containsExactly("Card_2_2", "Card_2", "Card_2_2_1").inOrder()
    assertThat(resultIds.map { it.lowercase(Locale.ROOT) }).containsNoDuplicates()
  }

  @Test
  fun caseFoldingUsesLocaleRootUnderTurkishDefaultLocale() {
    val previousLocale = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    try {
      val resultIds = allocateAll(listOf("I", "i", "TITLE", "title"))

      assertThat(resultIds).containsExactly("Card_I_0", "Card_i_1", "Card_TITLE_2", "Card_title_3").inOrder()
      assertThat(resultIds.map { it.lowercase(Locale.ROOT) }).containsNoDuplicates()
    } finally {
      Locale.setDefault(previousLocale)
    }
  }

  @Test
  fun baseResultIdIndexTrackerSharesCountersAcrossCaseOnlyCollisions() {
    val previousLocale = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    try {
      val tracker = BaseResultIdIndexTracker()

      assertThat(tracker.nextIndex("Card")).isEqualTo(0)
      assertThat(tracker.nextIndex("card")).isEqualTo(1)
      assertThat(tracker.nextIndex("CARD")).isEqualTo(2)
      assertThat(tracker.nextIndex("Other")).isEqualTo(0)
      assertThat(tracker.nextIndex("IC_LOGO")).isEqualTo(0)
      assertThat(tracker.nextIndex("ic_logo")).isEqualTo(1)
    } finally {
      Locale.setDefault(previousLocale)
    }
  }
}
