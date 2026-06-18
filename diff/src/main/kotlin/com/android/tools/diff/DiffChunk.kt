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

internal const val HUNK_HEADER_PREFIX = "@@ -"
internal const val ORIGINAL_FILE_PREFIX = "---"
internal const val MODIFIED_FILE_PREFIX = "+++"
internal const val GIT_DIFF_PREFIX = "diff "
internal const val INDEX_PREFIX = "index "
internal const val META_INSTRUCTION_PREFIX = "\\"
internal const val NO_NEWLINE_MARKER = "No newline at end of file"

internal fun Char.isLineTerminator(): Boolean = this == '\n' || this == '\r'

internal fun String.removeLineTerminators(): String = this.removeSuffix("\n").removeSuffix("\r")

private fun String.isHunkHeader(): Boolean = this.startsWith(HUNK_HEADER_PREFIX)

private fun String.isDiffHeader(): Boolean =
  this.startsWith(ORIGINAL_FILE_PREFIX) ||
    this.startsWith(MODIFIED_FILE_PREFIX) ||
    this.startsWith(GIT_DIFF_PREFIX) ||
    this.startsWith(INDEX_PREFIX)

/**
 * A contiguous block of changes within a [TextPatch].
 *
 * This model follows the standard unified diff format: `@@ -oldStart,oldLength +newStart,newLength @@`
 *
 * **Unified Diff Coordinate Rules:**
 * - Line numbers are 1-indexed.
 * - **Zero-length ranges (pure additions/deletions):** In standard unified diffs, when a range has a length of 0 (e.g., `-0,0` or `+5,0`),
 *   the line number represents the line *after which* the change occurs.
 * - During parsing (`DiffChunk.parse`), our parser automatically adjusts these coordinates to our internal 1-indexed coordinate space
 *   (incrementing starting lines by `1` for zero-length ranges).
 * - During serialization (`DiffChunk.toString`), it correctly maps them back to standard unified diff offset representations.
 */
data class DiffChunk(val oldStart: Int, val oldLength: Int, val newStart: Int, val newLength: Int, val lines: List<DiffLine>) {

  init {
    require(oldStart >= 1) { "oldStart must be positive (1-indexed): $oldStart" }
    require(newStart >= 1) { "newStart must be positive (1-indexed): $newStart" }
    require(oldLength >= 0) { "oldLength must be non-negative: $oldLength" }
    require(newLength >= 0) { "newLength must be non-negative: $newLength" }
  }

  /** Returns a new chunk that reverses the changes in this chunk. */
  fun invert(): DiffChunk {
    val invertedLines =
      lines.map { line ->
        when (line.type) {
          LineType.ADDED -> DiffLine(LineType.REMOVED, line.text, line.separator)
          LineType.REMOVED -> DiffLine(LineType.ADDED, line.text, line.separator)
          LineType.CONTEXT -> line
        }
      }
    return DiffChunk(oldStart = newStart, oldLength = newLength, newStart = oldStart, newLength = oldLength, lines = invertedLines)
  }

  /**
   * Formats this chunk into a standard-compliant Unified Diff hunk string block.
   *
   * **Coordinate Mapping & Formatting:**
   * - Formats the hunk header prefix (`@@ -oldRange +newRange @@`).
   * - Adjusts starting line coordinates back to standard Unified Diff offset representations if they are pure additions/deletions (length
   *   0).
   * - Formats each body line by calling [DiffLine.toUnifiedString].
   * - If a body line lacks a line ending ([LineSeparator.NONE]), appends the standard `\ No newline at end of file` warning immediately
   *   following that line inside the body.
   */
  fun toUnifiedString(): String = buildString {
    fun formatRange(start: Int, length: Int): String {
      val displayStart = if (length == 0) (start - 1).coerceAtLeast(0) else start
      return if (length == 1) "$displayStart" else "$displayStart,$length"
    }
    append("@@ -${formatRange(oldStart, oldLength)} +${formatRange(newStart, newLength)} @@")
    for (line in lines) {
      append("\n")
      append(line.toUnifiedString())
      if (line.separator == LineSeparator.NONE) {
        append("\n$META_INSTRUCTION_PREFIX $NO_NEWLINE_MARKER")
      }
    }
  }

  /** Returns a copy of this chunk with all line numbers shifted by [delta]. */
  fun renumbered(delta: Int): DiffChunk = copy(oldStart = oldStart + delta, newStart = newStart + delta)

  companion object {
    /**
     * Groups a list of parsed [diffLines] into contiguous hunk blocks ([DiffChunk]s), keeping up to [contextLines] unmodified context lines
     * surrounding each edit block.
     */
    internal fun createChunks(diffLines: List<DiffLine>, contextLines: Int): List<DiffChunk> {
      val chunks = mutableListOf<DiffChunk>()
      if (diffLines.isEmpty()) return chunks

      // Reuses SingleLineDiffChunk to track absolute offsets during chunk generation
      val flattened = mutableListOf<SingleLineDiffChunk>()
      var currentOld = 1
      var currentNew = 1
      for (line in diffLines) {
        flattened.add(SingleLineDiffChunk(line.type, line.text, line.separator, currentOld, 1, currentNew, 1))
        if (line.type != LineType.ADDED) currentOld++
        if (line.type != LineType.REMOVED) currentNew++
      }

      var overallIdx = 0
      while (overallIdx < flattened.size) {
        var firstChange = overallIdx
        while (firstChange < flattened.size && flattened[firstChange].type == LineType.CONTEXT) {
          firstChange++
        }
        if (firstChange == flattened.size) break

        var lastChange = firstChange
        // Group all contiguous changes (both additions and deletions) in this edit block
        while (lastChange < flattened.size && flattened[lastChange].type != LineType.CONTEXT) {
          lastChange++
        }

        val startOffset = firstChange.toLong() - contextLines
        val effectiveStart = startOffset.coerceAtLeast(0L).toInt()
        var effectiveEnd = (lastChange.toLong() + contextLines).coerceAtMost(flattened.size.toLong()).toInt()

        var lastConsideredEnd = lastChange
        while (true) {
          var nextChange = lastConsideredEnd
          while (nextChange < flattened.size && flattened[nextChange].type == LineType.CONTEXT) {
            nextChange++
          }
          if (nextChange == flattened.size) break
          val nextChangeOffset = nextChange.toLong() - contextLines
          if (effectiveEnd.toLong() >= nextChangeOffset) {
            var nextChangeEnd = nextChange
            // Group subsequent edit blocks that are within the context line threshold
            while (nextChangeEnd < flattened.size && flattened[nextChangeEnd].type != LineType.CONTEXT) {
              nextChangeEnd++
            }
            effectiveEnd = (nextChangeEnd.toLong() + contextLines).coerceAtMost(flattened.size.toLong()).toInt()
            lastConsideredEnd = nextChangeEnd
          } else {
            break
          }
        }

        val chunkLines = mutableListOf<DiffLine>()
        val oldStart = flattened[effectiveStart].oldStart
        val newStart = flattened[effectiveStart].newStart
        var oldLength = 0
        var newLength = 0

        for (k in effectiveStart until effectiveEnd) {
          val fLine = flattened[k]
          chunkLines.add(DiffLine(fLine.type, fLine.text, fLine.separator))
          if (fLine.type != LineType.ADDED) oldLength++
          if (fLine.type != LineType.REMOVED) newLength++
        }
        chunks.add(DiffChunk(oldStart, oldLength, newStart, newLength, chunkLines))
        overallIdx = effectiveEnd
      }
      return chunks
    }

    /**
     * Matches a standard unified diff hunk header (e.g., "@@ -1,5 +1,6 @@"). Capturing Groups: 1: oldStart, 2: oldLength (optional,
     * defaults to 1), 3: newStart, 4: newLength (optional, defaults to 1).
     */
    val CHUNK_HEADER_REGEX = Regex("""^@@\s*-(\d+)(?:,(\d+))?\s*\+(\d+)(?:,(\d+))?\s*@@.*""")

    /**
     * Parses a unified diff string into a list of [DiffChunk]s.
     *
     * This parser is resilient against missing diff headers and skips any leading file headers or metadata until it finds the first chunk
     * header (`@@`).
     *
     * @param strict If `true`, throws [IllegalArgumentException] when encountering any line inside a chunk that lacks a standard prefix
     *   character (' ', '+', '-', or '\'). Defaults to `false` for resilient context-fallback parsing (ideal for LLM outputs).
     */
    fun parse(diffText: String, strict: Boolean = false): List<DiffChunk> = buildList {
      val inputLines = diffText.splitWithLineSeparators()
      var i = 0
      while (i < inputLines.size) {
        val lineRaw = inputLines[i]
        val line = lineRaw.removeLineTerminators()
        val match = CHUNK_HEADER_REGEX.find(line)
        i++
        // Skip leading metadata (e.g. email headers, git command lines, commit logs)
        // until we find the first unified diff chunk boundary (indicated by '@@').
        if (match == null) continue

        val oldStartStr = match.groupValues[1]
        val oldLength = match.groupValues[2].ifEmpty { "1" }.toInt()
        val oldStartParsed = oldStartStr.toInt()
        // Unified Diff Rule: When oldLength is 0 (pure addition), the header start represents
        // the line after which the change occurs. Adjust to our internal 1-indexed space (+ 1).
        val oldStart = if (oldLength == 0) oldStartParsed + 1 else oldStartParsed

        val newStartStr = match.groupValues[3]
        val newLength = match.groupValues[4].ifEmpty { "1" }.toInt()
        val newStartParsed = newStartStr.toInt()
        // Unified Diff Rule: When newLength is 0 (pure deletion), the header start represents
        // the line after which the change occurs. Adjust to our internal 1-indexed space (+ 1).
        val newStart = if (newLength == 0) newStartParsed + 1 else newStartParsed

        val chunkLines = mutableListOf<DiffLine>()
        while (i < inputLines.size && !inputLines[i].startsWith(HUNK_HEADER_PREFIX)) {
          val chunkLineRaw = inputLines[i]
          val chunkLine = chunkLineRaw.removeLineTerminators()
          val prefix = chunkLine.getOrNull(0)

          if (chunkLine.isEmpty()) {
            var lookAheadIdx = i + 1
            while (lookAheadIdx < inputLines.size && inputLines[lookAheadIdx].trim().isEmpty()) {
              lookAheadIdx++
            }
            val nextNonBlankLine = inputLines.getOrNull(lookAheadIdx)?.trim() ?: ""

            if (nextNonBlankLine.isHunkHeader() || nextNonBlankLine.isDiffHeader()) break

            require(!strict) { "Malformed diff line: completely blank line inside hunk body in strict mode on line: $chunkLineRaw" }
            // OPTIMIZATION: Consume current and subsequent consecutive blank lines in one step,
            // then jump index pointer 'i' forward to maintain strictly linear O(N) complexity!
            val terminator = chunkLineRaw.takeLastWhile { it.isLineTerminator() }
            chunkLines.add(DiffLine(LineType.CONTEXT, "", LineSeparator.from(terminator)))
            for (idx in (i + 1) until lookAheadIdx) {
              val blankLineRaw = inputLines[idx]
              val blankTerminator = blankLineRaw.takeLastWhile { it.isLineTerminator() }
              chunkLines.add(DiffLine(LineType.CONTEXT, "", LineSeparator.from(blankTerminator)))
            }
            i = lookAheadIdx // Jump index pointer forward!
            continue // Skip to next iteration immediately
          }

          if (chunkLine.isDiffHeader()) break
          if (chunkLine.startsWith(META_INSTRUCTION_PREFIX)) {
            if (chunkLine.contains(NO_NEWLINE_MARKER) && chunkLines.isNotEmpty()) {
              // Retroactively update the last parsed line to indicate it lacks a trailing newline
              val last = chunkLines.removeAt(chunkLines.size - 1)
              chunkLines.add(last.copy(separator = LineSeparator.NONE))
            }
            i++
            // Skip other diff meta-instructions starting with '\' (only 'No newline' is currently supported).
            continue
          }

          val lineType = prefix?.let { LineType.fromPrefix(it) }
          val text = if (chunkLine.isEmpty()) "" else chunkLine.substring(1)
          val terminator = chunkLineRaw.takeLastWhile { it.isLineTerminator() }

          if (lineType != null) {
            chunkLines.add(DiffLine(lineType, text, LineSeparator.from(terminator)))
          } else {
            require(!strict) { "Malformed diff line: unrecognized or missing prefix '${prefix ?: "none"}' on line: $chunkLine" }
            // Resiliently handle lines without standard prefix as context
            chunkLines.add(DiffLine(LineType.CONTEXT, chunkLine, LineSeparator.from(terminator)))
          }
          i++
        }

        val addedOrContext = chunkLines.count { it.type != LineType.REMOVED }
        val removedOrContext = chunkLines.count { it.type != LineType.ADDED }

        // Resilient length alignment for LLM outputs (strict = false):
        // Large Language Models are notoriously bad at calculating coordinate lengths inside
        // hunk headers (@@ -oldStart,oldLength +newStart,newLength @@) even when they write
        // the correct code edits in the hunk body.
        //
        // In resilient mode, instead of failing the parse on coordinate mismatches, we dynamically
        // align 'oldLength' and 'newLength' to match the actual counts of parsed lines. This prevents
        // coordinate hallucinations from throwing exceptions while ensuring the constructed DiffChunk
        // remains 100% mathematically consistent for downstream patch applications.
        val finalOldLength = if (strict) oldLength else removedOrContext
        val finalNewLength = if (strict) newLength else addedOrContext

        // In strict mode, we strictly validate that the parsed lines match the header declarations
        if (strict) {
          require(addedOrContext == finalNewLength) {
            "Parsed ADDED/CONTEXT lines ($addedOrContext) does not match newLength ($finalNewLength) in chunk: $line"
          }
          require(removedOrContext == finalOldLength) {
            "Parsed REMOVED/CONTEXT lines ($removedOrContext) does not match oldLength ($finalOldLength) in chunk: $line"
          }
        }

        add(DiffChunk(oldStart, finalOldLength, newStart, finalNewLength, chunkLines))
      }
    }
  }
}

/**
 * Represents an atomic, single-line operation inside a chunk, carrying absolute coordinates in both the original and new document spaces.
 *
 * Unified with the internal diff line representation to prevent redundant allocations.
 */
internal data class SingleLineDiffChunk(
  val type: LineType,
  val text: String,
  val separator: LineSeparator,
  val oldStart: Int,
  val oldLength: Int, // 0 or 1
  val newStart: Int,
  val newLength: Int, // 0 or 1
) {
  /** Returns true if this single line operation occurs strictly before the [other] operation in coordinates space. */
  fun isStrictlyBefore(other: SingleLineDiffChunk): Boolean =
    newStart < other.oldStart ||
      (newStart == other.oldStart && newLength < other.oldLength) ||
      (newStart == other.oldStart && newLength == 0 && other.oldLength == 0)

  /**
   * Merges this single-line operation with an overlapping [other] single-line operation.
   *
   * **Squash Coordinate Merging:**
   * - Combines the original source index ([oldStart]) and original length ([oldLength]) from this operation (patch 1).
   * - Combines the target final index ([newStart]) and target length ([newLength]) from the other operation (patch 2).
   * - Merges overlapping line types (CONTEXT, ADDED, REMOVED) based on squashing combination rules.
   *
   * @return A squashed, merged [SingleLineDiffChunk], or `null` if the operations cancel each other out.
   */
  fun merge(other: SingleLineDiffChunk): SingleLineDiffChunk? {
    val mergedType = mergeLineTypes(type, other.type) ?: return null
    return SingleLineDiffChunk(
      type = mergedType,
      text = text,
      separator = separator,
      oldStart = oldStart,
      oldLength = oldLength,
      newStart = other.newStart,
      newLength = other.newLength,
    )
  }

  companion object {
    /**
     * Merges overlapping line change states. Out of 9 possible combinations, only 4 are mathematically possible:
     * - CONTEXT + CONTEXT -> CONTEXT
     * - CONTEXT + REMOVED -> REMOVED
     * - ADDED + CONTEXT -> ADDED
     * - ADDED + REMOVED -> null (Cancel out)
     */
    private fun mergeLineTypes(type1: LineType, type2: LineType): LineType? {
      return when {
        type1 == LineType.CONTEXT && type2 == LineType.CONTEXT -> LineType.CONTEXT
        type1 == LineType.CONTEXT && type2 == LineType.REMOVED -> LineType.REMOVED
        type1 == LineType.ADDED && type2 == LineType.CONTEXT -> LineType.ADDED
        type1 == LineType.ADDED && type2 == LineType.REMOVED -> null // cancel out
        else -> {
          // Note: Thrown as IllegalStateException (rather than IllegalArgumentException) because
          // mergeLineTypes is private. Any mismatch here represents an internal logic/state
          // invariant corruption in our squashing algorithm, not a caller argument error.
          throw IllegalStateException("Mathematically impossible squash combination: $type1 + $type2")
        }
      }
    }
  }
}
