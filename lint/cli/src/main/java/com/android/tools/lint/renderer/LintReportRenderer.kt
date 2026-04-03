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

package com.android.tools.lint.renderer

import com.android.tools.lint.renderer.data.LintLocation
import com.google.common.html.HtmlEscapers

/**
 * Extracts a formatted HTML code snippet around a specific lint issue location.
 *
 * @param location The location of the issue containing the target line number.
 * @param sourceText The full source text of the file where the issue occurred.
 * @param errorLine2 An optional secondary error line (e.g., showing a caret or underline) to display below the target line.
 * @param contextSize The number of lines to include before and after the target line to provide context.
 * @return An HTML-formatted string containing the source context, or null if the source text or line number is unavailable.
 */
fun extractSourceContext(location: LintLocation, sourceText: CharSequence?, errorLine2: String?, contextSize: Int): String? {
  if (sourceText.isNullOrEmpty() || location.line == null) return null
  val lines = sourceText.lines()

  if (location.line > lines.size) return null

  val startLine = (location.line - contextSize).coerceAtLeast(1)
  val endLine = (location.line + contextSize).coerceAtMost(lines.size)

  if (startLine > endLine) return null

  val lineNumWidth = endLine.toString().length.coerceAtLeast(5)
  val errorLineSpacing = " ".repeat(lineNumWidth + 1)

  val sb = StringBuilder()
  for (i in startLine..endLine) {
    val isTargetLine = i == location.line
    val lineText = lines[i - 1]
    val formattedLineNum = i.toString().padStart(lineNumWidth)
    if (isTargetLine) {
      sb.append("<span class=\"caretline\">")
    }
    sb.append("<span class=\"lineno\">").append(formattedLineNum).append(" </span>")
    sb.append(HtmlEscapers.htmlEscaper().escape(lineText))
    if (isTargetLine) {
      sb.append("</span>")
      if (errorLine2 != null) {
        sb.append("\n<span class=\"lineno\">").append(errorLineSpacing).append("</span>")
        sb.append("<span class=\"warning\">").append(HtmlEscapers.htmlEscaper().escape(errorLine2)).append("</span>")
      }
    }
    sb.append("\n")
  }
  return sb.toString()
}
