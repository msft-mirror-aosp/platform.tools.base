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

import com.android.tools.uicheck.ComponentTreeNode
import com.android.tools.uicheck.snapshot.UiCheckIssue
import com.android.tools.visuallint.VisualLintErrorType
import kotlin.math.floor
import kotlin.math.max

/** Finds text with lines longer than 120 characters, which Material Design recommends avoiding on wide screens. */
object LongTextCheck : ComponentTreeCheck {

  private const val MAX_TEXT_LINE_LENGTH = 120
  private const val AVG_CHAR_WIDTH_DP = 7.5f
  private const val AVG_CHAR_WIDTH_TO_FONT_SIZE_RATIO = 0.5f
  private const val MULTI_COLUMN_LAYOUT_URL =
    "https://m3.material.io/foundations/layout/applying-layout/window-size-classes#a9594611-a6d4-4dce-abcb-15e7dd431f8a"

  /** Checks for text components displaying lines exceeding [MAX_TEXT_LINE_LENGTH] characters on wide screens. */
  override fun check(context: CheckContext): List<UiCheckIssue> =
    context
      .visibleNodes()
      .filter { node ->
        if (!node.isTextComponent()) return@filter false
        val text = node.semantics?.get("text") ?: node.parameters?.get("text")
        !text.isNullOrEmpty() && hasLineExceedingMaxLength(text, node, context.densityDpi, context.fontScale)
      }
      .distinctBy { it.bounds }
      .map { createIssueData(it, context) }
      .toList()

  /** Creates a long text line length finding for [node]. */
  private fun createIssueData(
    node: ComponentTreeNode,
    context: CheckContext,
  ): UiCheckIssue {
    val simpleName = node.simpleComponentName()
    return buildIssueData(
      node = node,
      context = context,
      errorType = VisualLintErrorType.LONG_TEXT,
      message = "${node.nameWithId()} has lines containing more than $MAX_TEXT_LINE_LENGTH characters",
      description =
        "$simpleName has lines containing more than $MAX_TEXT_LINE_LENGTH characters in a preview configuration.<br/>" +
          "Material Design recommends reducing the width of TextView or switching to a " +
          "<a href=\"$MULTI_COLUMN_LAYOUT_URL\">multi-column layout</a> for breakpoints >= 600dp.",
    )
  }

  // TODO(b/585627096): Once standalone-render exposes exact max rendered line length on ComponentTreeNode
  //  (from TextView.layout / EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY), replace the fontSize/wrapping
  //  heuristic below with `maxLineLength > MAX_TEXT_LINE_LENGTH` to match LongTextAnalyzer.

  /**
   * Estimates from the longest paragraph, horizontal character capacity (using `fontSize` when available), and word-wrapping whether any
   * rendered line exceeds [MAX_TEXT_LINE_LENGTH] visible characters.
   */
  private fun hasLineExceedingMaxLength(
    text: String,
    node: ComponentTreeNode,
    densityDpi: Int,
    fontScale: Float,
  ): Boolean {
    val avgCharWidthDp = parseFontSizeSp(node.parameters)?.times(AVG_CHAR_WIDTH_TO_FONT_SIZE_RATIO) ?: AVG_CHAR_WIDTH_DP
    val avgCharWidthPx = max(1f, avgCharWidthDp.dpToPx(densityDpi) * fontScale)
    val capacityChars = floor(node.bounds.width.toFloat() / avgCharWidthPx).toInt()
    if (capacityChars <= MAX_TEXT_LINE_LENGTH) return false

    val softWrap = !node.parameters?.get("softWrap").equals("false", ignoreCase = true)
    return text.lines().any { paragraph ->
      paragraph.length > MAX_TEXT_LINE_LENGTH && (!softWrap || wrapsWithLongLine(paragraph, capacityChars))
    }
  }

  /** Simulates greedy word-wrapping of [paragraph] at spaces and returns `true` if any resulting line exceeds [MAX_TEXT_LINE_LENGTH]. */
  private fun wrapsWithLongLine(paragraph: String, capacityChars: Int): Boolean {
    var lineLen = 0
    for (word in paragraph.split(' ')) {
      val wordLen = word.length
      if (wordLen > MAX_TEXT_LINE_LENGTH) return true
      if (lineLen == 0) {
        lineLen = wordLen
      } else if (lineLen + 1 + wordLen <= capacityChars) {
        lineLen += 1 + wordLen
      } else {
        if (lineLen > MAX_TEXT_LINE_LENGTH) return true
        lineLen = wordLen
      }
    }
    return lineLen > MAX_TEXT_LINE_LENGTH
  }

  /** Parses a positive `fontSize` (in `sp`) from [parameters], or returns `null` when unset or invalid. */
  private fun parseFontSizeSp(parameters: Map<String, String>?): Float? {
    val raw = parameters?.get("fontSize")?.trim()?.lowercase() ?: return null
    val numeric = raw.removeSuffix(".sp").removeSuffix("sp").trim()
    return numeric.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
  }
}
