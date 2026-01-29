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
package com.android.tools.lint.checks.fx.result

import org.jetbrains.uast.UElement

// everything in this file is just for internal debugging

internal fun Int.subscript(): String =
    when {
      this < 0 -> "₋${(-this).subscript()}"
      else -> {
        val r = "₀₁₂₃₄₅₆₇₈₉"[this % 10].toString()
        val q = this / 10
        if (q > 0) "${q.subscript()}$r" else r
      }
    }

private fun String.abbrev(maxLen: Int = 30): String =
    when {
      length <= maxLen -> this
      else -> "${substring(0, maxLen)}…"
    }

internal fun UElement.renderAbbrev(maxLen: Int = 30): String = "⟪${asSourceString().abbrev(maxLen)}⟫"

internal fun String.bold() = map { it.bold() }.joinToString("")

private fun Char.bold(): String {
  val boldLowers = "𝐚𝐛𝐜𝐝𝐞𝐟𝐠𝐡𝐢𝐣𝐤𝐥𝐦𝐧𝐨𝐩𝐪𝐫𝐬𝐭𝐮𝐯𝐰𝐱𝐲𝐳"
  val boldUppers = "𝐀𝐁𝐂𝐃𝐄𝐅𝐆𝐇𝐈𝐉𝐊𝐋𝐌𝐍𝐎𝐏𝐐𝐑𝐒𝐓𝐔𝐕𝐖𝐗𝐘𝐙"
  val boldDigits = "𝟎𝟏𝟐𝟑𝟒𝟓𝟔𝟕𝟖𝟗"
  fun String.unicodeAt(i: Int) = substring(2 * i, 2 * i + 2)
  return when (this) {
    in 'a'..'z' -> boldLowers.unicodeAt(this - 'a')
    in 'A'..'Z' -> boldUppers.unicodeAt(this - 'A')
    in '0'..'9' -> boldDigits.unicodeAt(this - '0')
    else -> "$this"
  }
}
