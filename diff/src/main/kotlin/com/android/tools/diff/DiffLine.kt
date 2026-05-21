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
package com.android.tools.diff

/**
 * Supported line separators for binary-exact diffing.
 *
 * Line separators are preserved to support binary-exact patching in low-level or non-IDE environments where platform normalization is not
 * available.
 */
enum class LineSeparator(val value: String) {
  LF("\n"),
  CRLF("\r\n"),
  CR("\r"),
  NONE("");

  companion object {
    /**
     * Matches the raw string terminator sequence (e.g., "\r\n", "\n") to a [LineSeparator], defaulting to [NONE] if the sequence is
     * unrecognized or empty.
     */
    fun from(s: String): LineSeparator = entries.find { it.value == s } ?: NONE
  }
}

/**
 * A single line within a [DiffChunk].
 *
 * @property type The type of the line (ADDED, REMOVED, or CONTEXT).
 * @property text The content of the line, excluding the prefix character and separator.
 * @property separator The line separator sequence used in the source file.
 */
data class DiffLine(val type: LineType, val text: String, val separator: LineSeparator = LineSeparator.LF) {
  companion object {
    /**
     * Constructs a [DiffLine] from a raw string line by extracting its trailing line separator sequence (supporting standard "\r\n", "\n",
     * and "\r" endings).
     *
     * Uses highly optimized $O(1)$ endsWith checks to avoid character iteration overhead.
     */
    fun fromRawLine(rawLine: String, type: LineType): DiffLine {
      val terminator =
        when {
          rawLine.endsWith("\r\n") -> "\r\n"
          rawLine.endsWith("\n") -> "\n"
          rawLine.endsWith("\r") -> "\r"
          else -> ""
        }
      return DiffLine(type, rawLine.removeSuffix(terminator), LineSeparator.from(terminator))
    }
  }

  /**
   * Formats this single line into standard Unified Diff line format (`prefix + text`).
   *
   * Note: It strictly follows the unified diff standard (context lines start with a space prefix). It does NOT include the trailing line
   * separator sequence to keep chunk formatting clean.
   */
  fun toUnifiedString(): String = "${type.prefix}$text"
}
