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

import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test

class DiffChunkTest {
  @Test
  fun testToUnifiedString() {
    val lines = listOf(DiffLine(LineType.CONTEXT, "context"), DiffLine(LineType.REMOVED, "removed"), DiffLine(LineType.ADDED, "added"))
    val chunk = DiffChunk(1, 2, 1, 2, lines)
    val expected = "@@ -1,2 +1,2 @@\n context\n-removed\n+added"
    assertThat(chunk.toUnifiedString()).isEqualTo(expected)
  }

  @Test
  fun testToUnifiedString_singleLine() {
    val chunk = DiffChunk(1, 1, 1, 1, listOf(DiffLine(LineType.CONTEXT, "line")))
    assertThat(chunk.toUnifiedString()).startsWith("@@ -1 +1 @@")
  }

  @Test
  fun testDefaultToString() {
    val chunk = DiffChunk(1, 1, 1, 1, listOf(DiffLine(LineType.CONTEXT, "line")))
    assertThat(chunk.toString()).startsWith("DiffChunk(oldStart=1, oldLength=1, newStart=1, newLength=1")
  }

  @Test
  fun testRenumbered() {
    val chunk = DiffChunk(10, 1, 20, 1, listOf(DiffLine(LineType.CONTEXT, "line")))
    val renumbered = chunk.renumbered(5)
    assertThat(renumbered.oldStart).isEqualTo(15)
    assertThat(renumbered.newStart).isEqualTo(25)
  }

  @Test
  fun testInvert() {
    val lines = listOf(DiffLine(LineType.CONTEXT, "common"), DiffLine(LineType.REMOVED, "old"), DiffLine(LineType.ADDED, "new"))
    val chunk = DiffChunk(10, 2, 10, 2, lines)
    val inverted = chunk.invert()

    assertThat(inverted.oldStart).isEqualTo(10)
    assertThat(inverted.oldLength).isEqualTo(2)
    assertThat(inverted.newStart).isEqualTo(10)
    assertThat(inverted.newLength).isEqualTo(2)
    assertThat(inverted.lines)
      .containsExactly(DiffLine(LineType.CONTEXT, "common"), DiffLine(LineType.ADDED, "old"), DiffLine(LineType.REMOVED, "new"))
  }

  @Test
  fun testParse_noNewline() {
    val diff =
      """
      @@ -1 +1 @@
      -old line
      \ No newline at end of file
      +new line
      \ No newline at end of file
      """
        .trimIndent()

    val chunks = DiffChunk.parse(diff)
    assertThat(chunks).hasSize(1)
    val chunk = chunks[0]
    assertThat(chunk.lines[0].separator).isEqualTo(LineSeparator.NONE)
    assertThat(chunk.lines[1].separator).isEqualTo(LineSeparator.NONE)
  }

  @Test
  fun testToUnifiedString_noNewline() {
    val chunk =
      DiffChunk(
        oldStart = 1,
        oldLength = 1,
        newStart = 1,
        newLength = 1,
        lines = listOf(DiffLine(LineType.REMOVED, "old", LineSeparator.NONE), DiffLine(LineType.ADDED, "new", LineSeparator.NONE)),
      )
    val expected =
      """
      @@ -1 +1 @@
      -old
      \ No newline at end of file
      +new
      \ No newline at end of file
      """
        .trimIndent()
    assertThat(chunk.toUnifiedString()).isEqualTo(expected)
  }

  @Test
  fun testParse_basic() {
    val diff =
      """
      @@ -1,3 +1,4 @@
       line1
      -line2
      +line2 mod
      +line2.5
       line3
      """
        .trimIndent()

    val chunks = DiffChunk.parse(diff)
    assertThat(chunks).hasSize(1)
    val chunk = chunks[0]
    assertThat(chunk.oldStart).isEqualTo(1)
    assertThat(chunk.oldLength).isEqualTo(3)
    assertThat(chunk.newStart).isEqualTo(1)
    assertThat(chunk.newLength).isEqualTo(4)

    assertThat(chunk.lines)
      .containsExactly(
        DiffLine(LineType.CONTEXT, "line1"),
        DiffLine(LineType.REMOVED, "line2"),
        DiffLine(LineType.ADDED, "line2 mod"),
        DiffLine(LineType.ADDED, "line2.5"),
        DiffLine(LineType.CONTEXT, "line3", LineSeparator.NONE),
      )
  }

  @Test
  fun testParse_strictMode() {
    val malformedDiff =
      """
      @@ -1,2 +1,3 @@
       line1
      malformed line lacking prefix
      +line2
      """
        .trimIndent()

    // Resilient mode (strict = false) handles it cleanly as CONTEXT fallback
    val resilientChunks = DiffChunk.parse(malformedDiff, strict = false)
    assertThat(resilientChunks).hasSize(1)
    assertThat(resilientChunks[0].lines[1].type).isEqualTo(LineType.CONTEXT)

    // Strict mode (strict = true) throws IllegalArgumentException and asserts its exact message
    val exception = assertFailsWith<IllegalArgumentException> { DiffChunk.parse(malformedDiff, strict = true) }
    assertThat(exception).hasMessageThat().contains("Malformed diff line: unrecognized or missing prefix 'm'")
  }

  @Test
  fun testParse_zeroLengthHeaderCoordinates() {
    // @@ -0,0 +1,2 @@ represents a pure addition of 2 lines at line 1 (start of empty file).
    // The parser should adjust the internal oldStart from 0 to 1 because length is 0.
    val addDiff =
      """
      @@ -0,0 +1,2 @@
      +line1
      +line2
      """
        .trimIndent()

    val chunks = DiffChunk.parse(addDiff)
    assertThat(chunks).hasSize(1)
    val chunk = chunks[0]
    assertThat(chunk.oldStart).isEqualTo(1)
    assertThat(chunk.oldLength).isEqualTo(0)
    assertThat(chunk.newStart).isEqualTo(1)
    assertThat(chunk.newLength).isEqualTo(2)

    // @@ -2,2 +2,0 @@ represents a pure deletion of 2 lines after line 2.
    // The parser should adjust the internal newStart from 2 to 3 because length is 0.
    val deleteDiff =
      """
      @@ -2,2 +2,0 @@
      -line1
      -line2
      """
        .trimIndent()

    val deleteChunks = DiffChunk.parse(deleteDiff)
    assertThat(deleteChunks).hasSize(1)
    val delChunk = deleteChunks[0]
    assertThat(delChunk.oldStart).isEqualTo(2)
    assertThat(delChunk.oldLength).isEqualTo(2)
    assertThat(delChunk.newStart).isEqualTo(3)
    assertThat(delChunk.newLength).isEqualTo(0)
  }

  @Test
  fun testParse_strictModeEmptyLine() {
    val emptyLineDiff =
      listOf(
          "@@ -1,3 +1,3 @@",
          " line1",
          "", // completely empty prefix-less line in the middle
          " line2",
        )
        .joinToString("\n")

    // Resilient mode (strict = false) parses completely empty line as CONTEXT fallback
    val resilientChunks = DiffChunk.parse(emptyLineDiff, strict = false)
    assertThat(resilientChunks).hasSize(1)
    assertThat(resilientChunks[0].lines[1].type).isEqualTo(LineType.CONTEXT)
    assertThat(resilientChunks[0].lines[1].text).isEmpty()

    // Strict mode (strict = true) throws IllegalArgumentException on prefix-less empty line and asserts its exact message
    val exception = assertFailsWith<IllegalArgumentException> { DiffChunk.parse(emptyLineDiff, strict = true) }
    assertThat(exception).hasMessageThat().contains("Malformed diff line: completely blank line inside hunk body")
  }

  @Test
  fun testParse_resilientHunkBoundary() {
    // Hunk containing a line that starts with "@@" (e.g., an annotation like @@Test).
    // In resilient mode, this should be parsed successfully as context rather than
    // mistaking it for a hunk header boundary.
    val annotationDiff =
      """
      @@ -1,3 +1,3 @@
       line1
      @@Test
       line2
      """
        .trimIndent()

    val chunks = DiffChunk.parse(annotationDiff, strict = false)
    assertThat(chunks).hasSize(1)
    val chunk = chunks[0]
    assertThat(chunk.lines).hasSize(3)
    assertThat(chunk.lines[1].text).isEqualTo("@@Test")
    assertThat(chunk.lines[1].type).isEqualTo(LineType.CONTEXT)
  }

  @Test
  fun testParse_coordinateLengthMismatch() {
    // Hunk header claims 2 lines of context and 2 final lines,
    // but the hunk body actually contains 3 lines (hallucinated coordinates!).
    val mismatchedDiff =
      """
      @@ -1,2 +1,2 @@
       line1
      -old line
      +new line
      +another added line
      """
        .trimIndent()

    // Resilient mode (strict = false) recovers gracefully and overrides coordinate lengths
    val resilientChunks = DiffChunk.parse(mismatchedDiff, strict = false)
    assertThat(resilientChunks).hasSize(1)
    val chunk = resilientChunks[0]
    // Recalculated oldLength: context + removed = 1 + 1 = 2
    assertThat(chunk.oldLength).isEqualTo(2)
    // Recalculated newLength: context + added = 1 + 2 = 3 (overridden from 2!)
    assertThat(chunk.newLength).isEqualTo(3)
    assertThat(chunk.lines).hasSize(4)

    // Strict mode (strict = true) fails fast on coordinate mismatches
    val exception = assertFailsWith<IllegalArgumentException> { DiffChunk.parse(mismatchedDiff, strict = true) }
    assertThat(exception).hasMessageThat().contains("Parsed ADDED/CONTEXT lines (3) does not match newLength (2)")
  }

  @Test
  fun testCreateChunks_largeContextOverflowProtection() {
    // Simple list of diff lines containing one edit
    val diffLines =
      listOf(
        DiffLine(LineType.CONTEXT, "context1"),
        DiffLine(LineType.REMOVED, "removed"),
        DiffLine(LineType.ADDED, "added"),
        DiffLine(LineType.CONTEXT, "context2"),
      )

    // Requesting infinite context lines (Int.MAX_VALUE) represents a full-file replacement.
    // This test verifies that our Long safe math successfully prevents 32-bit integer overflow
    // and correctly bounds the hunk coordinates without throwing IndexOutOfBoundsException.
    val chunks = DiffChunk.createChunks(diffLines, contextLines = Int.MAX_VALUE)

    assertThat(chunks).hasSize(1)
    val chunk = chunks[0]
    assertThat(chunk.oldStart).isEqualTo(1)
    assertThat(chunk.oldLength).isEqualTo(3) // 2 context + 1 removed
    assertThat(chunk.newStart).isEqualTo(1)
    assertThat(chunk.newLength).isEqualTo(3) // 2 context + 1 added
  }

  @Test
  fun testParse_resilientMetadataAndBlankLineBoundaryExit() {
    val diffText =
      """
      @@ -1,1 +1,1 @@
      -old line
      +new line


      @@ -10,1 +10,1 @@
      -another old
      +another new
      """
        .trimIndent()

    // This verifies that the resilient lookahead correctly:
    // 1. Skips both blank lines between hunks as boundary separators.
    // 2. Does not consume them as context lines inside the first hunk.
    // 3. Parses exactly two distinct chunks.
    val chunks = DiffChunk.parse(diffText, strict = false)
    assertThat(chunks).hasSize(2)

    val chunk1 = chunks[0]
    assertThat(chunk1.lines).hasSize(2) // only REMOVED and ADDED, no blank CONTEXT lines!
    assertThat(chunk1.lines[0].type).isEqualTo(LineType.REMOVED)
    assertThat(chunk1.lines[1].type).isEqualTo(LineType.ADDED)

    val chunk2 = chunks[1]
    assertThat(chunk2.lines).hasSize(2)
  }

  @Test
  fun testParse_resilientBlankLineInsideHunkBody() {
    val diffText =
      """
      @@ -1,3 +1,4 @@
       context before
      -old line
      +new line

       context after
      """
        .trimIndent()

    // This verifies that a completely blank line occurring strictly INSIDE the hunk body
    // is successfully parsed resiliently as a CONTEXT line and does not cut the hunk short!
    val chunks = DiffChunk.parse(diffText, strict = false)
    assertThat(chunks).hasSize(1)
    val chunk = chunks[0]
    assertThat(chunk.lines).hasSize(5) // context + removed + added + blank context + context = 5!
    assertThat(chunk.lines[3].type).isEqualTo(LineType.CONTEXT)
    assertThat(chunk.lines[3].text).isEmpty()
  }

  @Test
  fun testParse_metadataHeaderBoundaryExit() {
    val diffText =
      """
      --- a/first.txt
      +++ b/first.txt
      @@ -1,1 +1,1 @@
      -old line
      +new line
      --- a/second.txt
      +++ b/second.txt
      """
        .trimIndent()

    // This verifies that encountering a new file header metadata block (---) immediately
    // exits the hunk loop cleanly, treating the trailing file header as a boundary!
    val chunks = DiffChunk.parse(diffText, strict = false)
    assertThat(chunks).hasSize(1)
    val chunk = chunks[0]
    assertThat(chunk.lines).hasSize(2) // only REMOVED and ADDED, no trailing '---' lines!
  }
}
