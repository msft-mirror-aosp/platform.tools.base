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

import com.android.tools.diff.TextPatch.Companion.splitWithLineSeparators

/**
 * Represents one file state in the diff header.
 *
 * @property path The absolute or relative file path (defaults to [NO_PATH] for creation/deletion).
 * @property timestamp Optional text timestamp (e.g., from git diff or patch metadata).
 * @property stamp Optional file size or version hash stamp (represented as a Long, defaults to -1).
 */
data class FileHeader(val path: String = NO_PATH, val timestamp: String? = null, val stamp: Long = -1L) {
  /** Formats this file header into standard unified diff path-timestamp suffix format. */
  fun format(): String {
    val timeStr = if (timestamp != null) " $timestamp" else ""
    val stampStr = if (stamp >= 0) " $stamp" else ""
    return "$path$timeStr$stampStr".trim()
  }

  companion object {
    /** Standard unified diff marker for null/non-existent files (e.g. during creation/deletion). */
    const val NO_PATH = "/dev/null"
  }
}

/**
 * Represents a unified diff for a single file, including standard `---` and `+++` file headers.
 *
 * This class supports the standard multi-file unified diff file format and wraps [TextPatch].
 *
 * @property oldFile Header details for the original file.
 * @property newFile Header details for the modified file.
 * @property patch The mathematical patch core containing the chunks.
 */
data class TextFilePatch(
  val oldFile: FileHeader = FileHeader(),
  val newFile: FileHeader = FileHeader(),
  val patch: TextPatch = TextPatch(emptyList()),
) {

  /**
   * Formats this patch into standard Unified Diff file representation, including the `---` and `+++` file headers if modified, followed by
   * all the hunk blocks.
   *
   * Guarantee standard trailing newlines at the very end of the output.
   */
  fun toUnifiedString(): String = buildString {
    if (oldFile.path != FileHeader.NO_PATH || newFile.path != FileHeader.NO_PATH) {
      append("--- ")
      append(oldFile.format())
      append("\n")
      append("+++ ")
      append(newFile.format())
      if (!patch.isEmpty()) {
        append("\n")
      }
    }
    append(patch.toUnifiedString())
    // Guarantee a trailing newline at the end of the output string for standard-compliant unified diff files
    if (length > 0 && this[length - 1] != '\n') {
      append("\n")
    }
  }

  /** Returns true if this patch represents a file creation. */
  fun isCreate(): Boolean = oldFile.path == FileHeader.NO_PATH

  companion object {
    private val FILE_HEADER_REGEX = Regex("""^(---|\+\+\+)\s+(.+?)(?:\s+(\d{4}-\d{2}-\d{2}(?:\s+\d{2}:\d{2}:\d{2}.*)?|\d+))?$""")

    /**
     * Parses a single unified diff string with headers into a [TextFilePatch].
     *
     * Resiliently returns an empty [TextFilePatch] if no diff chunks are found.
     *
     * @param strict If `true`, throws [IllegalArgumentException] when encountering malformed lines lacking standard prefix characters
     *   inside any chunk. Defaults to `false`.
     */
    fun parse(diffText: String, strict: Boolean = false): TextFilePatch {
      val patches = parseMultiPatch(diffText, strict)
      return patches.firstOrNull() ?: TextFilePatch()
    }

    /**
     * Parses a multi-file unified diff string into a list of individual [TextFilePatch]es.
     *
     * **Algorithmic Details:**
     * - Streams through the input lines, tracking boundary markers (`---` and `+++`).
     * - Accumulates chunk lines under `currentChunkText` and flushes them into a [TextFilePatch] block immediately before a new file block
     *   starts or at the end of the document.
     * - Strips trailing long stamp values from headers if they are present, separating them from the timestamp portion.
     *
     * @param strict If `true`, throws [IllegalArgumentException] when encountering malformed lines.
     */
    fun parseMultiPatch(diffText: String, strict: Boolean = false): List<TextFilePatch> = buildList {
      val inputLines = diffText.splitWithLineSeparators()
      var i = 0

      var oldFile = FileHeader()
      var newFile = FileHeader()
      val currentChunkText = StringBuilder()

      fun flushCurrentPatch() {
        val chunkStr = currentChunkText.toString()
        if (chunkStr.isNotBlank()) {
          val patch = TextPatch.parse(chunkStr, strict)
          add(TextFilePatch(oldFile, newFile, patch))
        }
        currentChunkText.clear() // Always clear to prevent cross-file leakage
      }

      while (i < inputLines.size) {
        val lineRaw = inputLines[i]
        val line = lineRaw.removeSuffix("\n").removeSuffix("\r")

        val headerMatch = FILE_HEADER_REGEX.find(line)
        if (headerMatch != null) {
          // Encountered a new file header! Flush the accumulated patch block from the previous file.
          flushCurrentPatch()

          val marker = headerMatch.groupValues[1]
          val path = headerMatch.groupValues[2].trim()
          val remainder = headerMatch.groupValues[3].trim()

          // Extract stamp and timestamp: split tokens and verify if the last one is a valid file size stamp.
          // To prevent misinterpreting trailing numbers in timestamps (e.g. a year or day) as a stamp,
          // we only capture the stamp if the preceding token looks like a timezone offset or time token.
          val tokens = remainder.split(" ").filter { it.isNotBlank() }
          val lastToken = tokens.lastOrNull()
          val parsedStamp = lastToken?.toLongOrNull() ?: -1L
          val timestamp =
            if (parsedStamp >= 0) {
              if (tokens.size > 1) {
                val secondToLast = tokens[tokens.size - 2]
                val isMetadataSeparator =
                  secondToLast.startsWith("+") ||
                    secondToLast.startsWith("-") ||
                    secondToLast.contains(":") ||
                    secondToLast == "UTC" ||
                    secondToLast == "GMT"
                if (isMetadataSeparator) {
                  tokens.dropLast(1).joinToString(" ").takeIf { it.isNotBlank() }
                } else {
                  remainder.takeIf { it.isNotBlank() }
                }
              } else {
                // Case C: Lone number remainder (e.g. "--- file.txt 1234").
                // It is a file size stamp / git stamp, not a timestamp date!
                null
              }
            } else {
              remainder.takeIf { it.isNotBlank() }
            }
          val stamp = if (timestamp == remainder) -1L else parsedStamp

          val header = FileHeader(path, timestamp, stamp)
          if (marker == "---") {
            oldFile = header
          } else if (marker == "+++") {
            newFile = header
          }
          i++
        } else if (line.startsWith("@@ -") || currentChunkText.isNotEmpty()) {
          // Collect chunk lines
          currentChunkText.append(lineRaw)
          i++
        } else {
          // Skip raw headers or unrecognized trailing text (like git index metadata) before chunks
          i++
        }
      }
      // Flush the final patch block
      flushCurrentPatch()
    }
  }
}
