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

package com.android.tools.uicheck.checks

import com.android.tools.visuallint.VisualLintErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTextCheckTest {

  /** Builds a test `Text` node with the given [value], [width], and [height] in pixels. */
  private fun text(value: String, width: Int = 1780, height: Int = 30) =
    node("Text", bounds(20, 200, 20 + width, 200 + height), semantics = mapOf("text" to value))

  /** Verifies that a single-line text node wider and longer than 120 characters is reported. */
  @Test
  fun reportsSingleLineLongerThan120Characters() {
    val issue = LongTextCheck.check(context(text("A".repeat(145)), densityDpi = 240)).single()

    assertEquals(VisualLintErrorType.LONG_TEXT, issue.errorType)
    assertEquals("Text has lines containing more than 120 characters", issue.message)
  }

  /** Verifies that text up to 120 characters is not flagged. */
  @Test
  fun ignoresTextUpTo120Characters() {
    assertTrue(LongTextCheck.check(context(text("A".repeat(120)), densityDpi = 240)).isEmpty())
  }

  /** Verifies that single-line ellipsized text inside a narrow column (300dp = 450px at 240dpi) is not reported. */
  @Test
  fun ignoresNarrowSingleLineEllipsizedText() {
    assertTrue(LongTextCheck.check(context(text("A".repeat(200), width = 450, height = 30), densityDpi = 240)).isEmpty())
  }

  /** Verifies that a 200-character paragraph followed by a short trailing line on a wide screen is reported. */
  @Test
  fun reportsMultiParagraphTextWithLongFirstParagraph() {
    val multiParagraph = "A".repeat(200) + "\nShort"

    assertEquals(1, LongTextCheck.check(context(text(multiParagraph, width = 1780, height = 80), densityDpi = 240)).size)
  }

  /** Verifies that long text wrapping into short lines inside a narrow 400dp (600px at 240dpi) container is ignored. */
  @Test
  fun ignoresLongTextThatWrapsIntoShortLines() {
    assertTrue(LongTextCheck.check(context(text("A".repeat(300), width = 600, height = 300), densityDpi = 240)).isEmpty())
  }

  /**
   * Verifies that a classic XML `TextView` exceeding 120 characters per line is reported, while `fontScale = 2.0f` reduces line capacity.
   */
  @Test
  fun reportsXmlTextViewAndRespectsFontScale() {
    val xmlText = node("android.widget.TextView", bounds(20, 200, 1800, 230), semantics = mapOf("text" to "A".repeat(145)))

    assertEquals(
      listOf("TextView has lines containing more than 120 characters"),
      LongTextCheck.check(context(xmlText, densityDpi = 240)).map { it.message },
    )
    assertTrue(LongTextCheck.check(context(xmlText, densityDpi = 240, fontScale = 2.0f)).isEmpty())
  }

  /** Verifies that `fontSize` in `parameters` adjusts character capacity when present. */
  @Test
  fun respectsFontSizeParameter() {
    val largeFont =
      node(
        "Text",
        bounds(20, 200, 1800, 260),
        semantics = mapOf("text" to "A".repeat(145)),
        parameters = mapOf("fontSize" to "24.0.sp"),
      )
    assertTrue(LongTextCheck.check(context(largeFont, densityDpi = 240)).isEmpty())
  }

  /**
   * Verifies that word-wrapping at spaces prevents false positives when each wrapped line is <= 120 characters, unless `softWrap = false`.
   */
  @Test
  fun simulatesWordWrappingUnlessSoftWrapDisabled() {
    // At 240dpi with 7.5dp avg char width (11.25px), 1375px fits ~122 chars per line.
    // 110 + " " + 15 = 126 chars wraps into 110 and 15 chars, neither > 120.
    val wrappedText = "A".repeat(110) + " " + "B".repeat(15)
    assertTrue(LongTextCheck.check(context(text(wrappedText, width = 1375, height = 60), densityDpi = 240)).isEmpty())

    val unwrapped =
      node(
        "Text",
        bounds(20, 200, 20 + 1375, 230),
        semantics = mapOf("text" to wrappedText),
        parameters = mapOf("softWrap" to "false"),
      )
    assertEquals(1, LongTextCheck.check(context(unwrapped, densityDpi = 240)).size)
    assertEquals(1, LongTextCheck.check(context(text("Short " + "A".repeat(125), width = 1780, height = 30), densityDpi = 240)).size)
    assertEquals(
      1,
      LongTextCheck.check(context(text("A".repeat(65) + " " + "B".repeat(60), width = 1780, height = 30), densityDpi = 240)).size,
    )
  }
}
