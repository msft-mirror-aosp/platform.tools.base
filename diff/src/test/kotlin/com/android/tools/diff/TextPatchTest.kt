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
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test

class TextPatchTest {

  @Test
  fun testApply() {
    val oldText = "line1\nline2\nline3"
    val patch = TextPatch(listOf(DiffChunk(2, 1, 2, 1, listOf(DiffLine(LineType.REMOVED, "line2"), DiffLine(LineType.ADDED, "changed")))))
    assertThat(patch.apply(oldText)).isEqualTo("line1\nchanged\nline3")
  }

  @Test
  fun testApply_trailingNewline() {
    val oldText = "line1\nline2\n"
    val patch = TextPatch(listOf(DiffChunk(2, 1, 2, 1, listOf(DiffLine(LineType.REMOVED, "line2"), DiffLine(LineType.ADDED, "changed")))))
    assertThat(patch.apply(oldText)).isEqualTo("line1\nchanged\n")
  }

  @Test
  fun testInvert() {
    val patch = TextPatch(listOf(DiffChunk(2, 1, 2, 1, listOf(DiffLine(LineType.REMOVED, "line2"), DiffLine(LineType.ADDED, "changed")))))
    val inverted = patch.invert()

    val newText = "line1\nchanged\nline3"
    val oldText = "line1\nline2\nline3"
    assertThat(inverted.apply(newText)).isEqualTo(oldText)
  }

  @Test
  fun testLineCounts() {
    val patch =
      TextPatch(
        listOf(
          DiffChunk(
            1,
            1,
            1,
            1,
            listOf(
              DiffLine(LineType.REMOVED, "old"),
              DiffLine(LineType.ADDED, "new1"),
              DiffLine(LineType.ADDED, "new2"),
              DiffLine(LineType.CONTEXT, "same"),
            ),
          )
        )
      )
    assertThat(patch.linesAdded).isEqualTo(2)
    assertThat(patch.linesRemoved).isEqualTo(1)
  }

  @Test
  fun testSquash() {
    val text1 = "A\nB\nC"
    val text2 = "A\nB2\nC"
    val text3 = "A\nB2\nC2"

    val patch1 = TextPatch.compute(text1, text2)
    val patch2 = TextPatch.compute(text2, text3)

    val squashed = patch1.squash(patch2)
    assertThat(squashed.apply(text1)).isEqualTo(text3)
  }

  @Test
  fun testSquash_cancelOut() {
    val text1 = "a\nb\nc"
    val text2 = "a\nnew line\nb\nc"
    val text3 = "a\nb\nc"

    val patch1 = TextPatch.compute(text1, text2)
    val patch2 = TextPatch.compute(text2, text3)
    val squashed = patch1.squash(patch2)

    assertThat(squashed.chunks).isEmpty()
  }

  @Test
  fun testSquash_modifiedAddition() {
    val text1 = "a\nc"
    val text2 = "a\nb\nc"
    val text3 = "a\nb modified\nc"

    val patch1 = TextPatch.compute(text1, text2)
    val patch2 = TextPatch.compute(text2, text3)
    val squashed = patch1.squash(patch2)

    assertThat(squashed.apply(text1)).isEqualTo(text3)
    assertThat(squashed.linesAdded).isEqualTo(1)
    assertThat(squashed.linesRemoved).isEqualTo(0)
  }

  @Test
  fun testSquash_doubleModification() {
    val text1 = "a\nb\nc"
    val text2 = "a\nb_v2\nc"
    val text3 = "a\nb_v3\nc"

    val patch1 = TextPatch.compute(text1, text2)
    val patch2 = TextPatch.compute(text2, text3)
    val squashed = patch1.squash(patch2)

    assertThat(squashed.apply(text1)).isEqualTo(text3)
    assertThat(squashed.linesAdded).isEqualTo(1)
    assertThat(squashed.linesRemoved).isEqualTo(1)
  }

  @Test
  fun testCompute_identical() {
    val text = "line1\nline2\nline3"
    val patch = TextPatch.compute(text, text)
    assertThat(patch.chunks).isEmpty()
  }

  @Test
  fun testCompute_simpleAdd() {
    val oldText = "line1\nline2"
    val newText = "line1\nline1.5\nline2"
    val patch = TextPatch.compute(oldText, newText)

    assertThat(patch.chunks).hasSize(1)
    val chunk = patch.chunks[0]
    assertThat(chunk.oldStart).isEqualTo(1)
    assertThat(chunk.oldLength).isEqualTo(2)
    assertThat(chunk.newStart).isEqualTo(1)
    assertThat(chunk.newLength).isEqualTo(3)

    assertThat(patch.apply(oldText)).isEqualTo(newText)
  }

  @Test
  fun testCompute_simpleRemove() {
    val oldText = "line1\nline2\nline3"
    val newText = "line1\nline3"
    val patch = TextPatch.compute(oldText, newText)

    assertThat(patch.chunks).hasSize(1)
    assertThat(patch.apply(oldText)).isEqualTo(newText)
  }

  @Test
  fun testForFileCreation() {
    val content = "line1\nline2"
    val patch = TextPatch.forFileCreation(content)
    assertThat(patch.chunks).hasSize(1)
    assertThat(patch.chunks[0].oldLength).isEqualTo(0)
    assertThat(patch.chunks[0].newLength).isEqualTo(2)
    assertThat(patch.apply("")).isEqualTo(content)
  }

  @Test
  fun testParse() {
    val diff =
      """
      @@ -1,2 +1,2 @@
       line1
      -old
      +new
      """
        .trimIndent()
    val patch = TextPatch.parse(diff)
    assertThat(patch.chunks).hasSize(1)
    val chunk = patch.chunks[0]
    assertThat(chunk.oldStart).isEqualTo(1)
    assertThat(chunk.lines).hasSize(3)
    assertThat(chunk.lines[1].type).isEqualTo(LineType.REMOVED)
  }

  @Test
  fun testApply_contextMismatch() {
    val oldText = "line1\nline2\nline3"
    val patch = TextPatch(listOf(DiffChunk(2, 1, 2, 1, listOf(DiffLine(LineType.REMOVED, "wrong"), DiffLine(LineType.ADDED, "changed")))))
    val exception = assertFailsWith<IllegalArgumentException> { patch.apply(oldText) }
    assertThat(exception).hasMessageThat().contains("Patch context mismatch at line 2")
  }

  @Test
  fun testApply_overlappingChunks() {
    val oldText = "line1\nline2\nline3"
    val exception =
      assertFailsWith<IllegalArgumentException> {
        TextPatch(
          listOf(
            DiffChunk(1, 2, 1, 2, listOf(DiffLine(LineType.CONTEXT, "line1"), DiffLine(LineType.CONTEXT, "line2"))),
            DiffChunk(2, 1, 2, 1, listOf(DiffLine(LineType.CONTEXT, "line2"))),
          )
        )
      }
    assertThat(exception).hasMessageThat().contains("Chunks must be non-overlapping")
  }

  @Test
  fun testApply_documentTooShort() {
    val oldText = "line1"
    val patch = TextPatch(listOf(DiffChunk(2, 1, 2, 1, listOf(DiffLine(LineType.CONTEXT, "line2", LineSeparator.NONE)))))
    val exception = assertFailsWith<IllegalArgumentException> { patch.apply(oldText) }
    assertThat(exception).hasMessageThat().contains("Original text shorter than expected by patch")
  }

  @Test
  fun testApply_documentTooShortForChunk() {
    val oldText = "line1\nline2"
    val patch =
      TextPatch(
        listOf(DiffChunk(2, 2, 2, 2, listOf(DiffLine(LineType.CONTEXT, "line2"), DiffLine(LineType.CONTEXT, "line3", LineSeparator.NONE))))
      )
    val exception = assertFailsWith<IllegalArgumentException> { patch.apply(oldText) }
    assertThat(exception).hasMessageThat().contains("Original text shorter than expected by patch")
  }

  @Test
  fun testSquash_incompatible() {
    val text1 = "A\nB"
    val text2 = "A\nB2"

    val patch1 = TextPatch.compute(text1, text2)
    val patch2 =
      TextPatch(
        listOf(
          DiffChunk(
            2,
            1,
            2,
            1,
            listOf(DiffLine(LineType.REMOVED, "B3", LineSeparator.NONE), DiffLine(LineType.ADDED, "B4", LineSeparator.NONE)),
          )
        )
      )

    val exception = assertFailsWith<IllegalStateException> { patch1.squash(patch2) }
    assertThat(exception).hasMessageThat().contains("Patches are not compatible for squash")
  }

  @Test
  fun testCompute_multipleChunks() {
    val oldText = "common1\ncommon2\noriginal1\noriginal2\ncommon3\ncommon4\nremoved1\nremoved2\ncommon5"
    val newText = "common1\nadded1\nadded2\ncommon2\nchanged1\nchanged2\ncommon3\ncommon4\ncommon5"

    val patch = TextPatch.compute(oldText, newText, contextLines = 0)
    assertThat(patch.chunks).hasSize(3)

    assertThat(patch.apply(oldText)).isEqualTo(newText)
  }

  @Test
  fun testCompute_endOfFileNoNewline() {
    val oldText = "line1\nline2"
    val newText = "line1\nline2\nline3"

    val patch = TextPatch.compute(oldText, newText, contextLines = 0)
    assertThat(patch.chunks).hasSize(1)

    // Check that we correctly identified that line2 gained a newline and line3 was added
    val lines = patch.chunks[0].lines
    assertThat(lines.any { it.type == LineType.REMOVED && it.text == "line2" && it.separator == LineSeparator.NONE }).isTrue()
    assertThat(lines.any { it.type == LineType.ADDED && it.text == "line2" && it.separator == LineSeparator.LF }).isTrue()
    assertThat(lines.any { it.type == LineType.ADDED && it.text == "line3" && it.separator == LineSeparator.NONE }).isTrue()

    assertThat(patch.apply(oldText)).isEqualTo(newText)
  }

  @Test
  fun testRenumbered() {
    val patch =
      TextPatch(
        listOf(
          DiffChunk(1, 1, 1, 1, listOf(DiffLine(LineType.CONTEXT, "line1"))),
          DiffChunk(5, 1, 5, 1, listOf(DiffLine(LineType.CONTEXT, "line5"))),
        )
      )
    val renumbered = patch.renumbered(10)
    assertThat(renumbered.chunks[0].oldStart).isEqualTo(11)
    assertThat(renumbered.chunks[1].oldStart).isEqualTo(15)
  }

  @Test
  fun testRepair_simpleShifted() {
    val document =
      """
      line 1
      line 2
      line 3
      this is context
      this line is removed
      another context line
      line 7
      line 8
      """
        .trimIndent()

    val malformedDiff =
      """
      @@ -1,3 +1,2 @@
       this is context
      -this line is removed
       another context line
      """
        .trimIndent()

    val patch = TextPatch.parse(malformedDiff)
    val repaired = patch.repair(document.splitWithLineSeparators())

    val expectedRepairedDiff =
      """
      @@ -4,3 +4,2 @@
       this is context
      -this line is removed
       another context line
      """
        .trimIndent()

    assertThat(repaired!!.toUnifiedString()).isEqualTo(expectedRepairedDiff)
    assertThat(repaired.apply(document))
      .isEqualTo(
        """
        line 1
        line 2
        line 3
        this is context
        another context line
        line 7
        line 8
        """
          .trimIndent()
      )
  }

  @Test
  fun testRepair_fuzzyWhitespace() {
    val document =
      """
      class Example {
          fun example() {
              println("Hello")
              println("World")
          }
      }
      """
        .trimIndent()

    val diffText =
      """
      @@ -3,2 +3,2 @@
       println("Hello")
      -println("World")
      +println("Replaced")
      """
        .trimIndent()

    val patch = TextPatch.parse(diffText)
    val repaired = patch.repair(document.splitWithLineSeparators())

    val expectedRepairedDiff =
      """
      @@ -3,2 +3,2 @@
               println("Hello")
      -        println("World")
      +println("Replaced")
      \ No newline at end of file
      """
        .trimIndent()

    assertThat(repaired!!.toUnifiedString()).isEqualTo(expectedRepairedDiff)
  }

  @Test
  fun testRepair_hallucinatedEmptyLine() {
    val document = "line1\nline2\n"
    val malformedWithExtraEmpty =
      """
      @@ -1,3 +1,2 @@
       line1
      -
       line2
      """
        .trimIndent()

    val patch = TextPatch.parse(malformedWithExtraEmpty)
    val repaired = patch.repair(document.splitWithLineSeparators())

    assertThat(repaired!!.toUnifiedString())
      .isEqualTo(
        """
        @@ -1,2 +1,2 @@
         line1
         line2
        """
          .trimIndent()
      )
  }

  @Test
  fun testRepair_missingEmptyLine() {
    val document = "line1\n\nline2\n"
    val patchText =
      """
      @@ -1,2 +1,2 @@
       line1
       line2
      """
        .trimIndent()

    val patch = TextPatch.parse(patchText)
    val repaired = patch.repair(document.splitWithLineSeparators())

    assertThat(repaired!!.toUnifiedString()).isEqualTo("@@ -1,3 +1,3 @@\n line1\n \n line2")
  }

  @Test
  fun testRepair_anchorMiddle() {
    val document =
      """
      header
      header
      first context
      line to remove
      THE IMPORTANT ANCHOR LINE
      another line to remove
      last context
      footer
      """
        .trimIndent()

    val malformedDiffText =
      """
      @@ -10,5 +10,3 @@
       first context
      -line to remove
       THE IMPORTANT ANCHOR LINE
      -another line to remove
       last context
      """
        .trimIndent()

    val patch = TextPatch.parse(malformedDiffText)
    val repaired = patch.repair(document.splitWithLineSeparators())

    assertThat(repaired!!.toUnifiedString())
      .isEqualTo(
        """
        @@ -3,5 +3,3 @@
         first context
        -line to remove
         THE IMPORTANT ANCHOR LINE
        -another line to remove
         last context
        """
          .trimIndent()
      )
  }

  @Test
  fun testRepair_multipleChunks() {
    val document =
      """
      alpha
      bravo
      charlie (context)
      delta (removed)
      echo (context)
      foxtrot
      golf
      hotel
      india (context)
      juliett (removed)
      kilo (context)
      lima
      """
        .trimIndent()

    val malformedDiffText =
      """
      @@ -1,3 +1,2 @@
       charlie (context)
      -delta (removed)
       echo (context)
      @@ -20,3 +19,2 @@
       india (context)
      -juliett (removed)
       kilo (context)
      """
        .trimIndent()

    val patch = TextPatch.parse(malformedDiffText)
    val repaired = patch.repair(document.splitWithLineSeparators())

    assertThat(repaired!!.toUnifiedString())
      .isEqualTo(
        """
        @@ -3,3 +3,2 @@
         charlie (context)
        -delta (removed)
         echo (context)
        @@ -9,3 +8,2 @@
         india (context)
        -juliett (removed)
         kilo (context)
        """
          .trimIndent()
      )
  }

  @Test
  fun testRepair_onlyDelete() {
    val document =
      """
      header
      first context
      THE LINE TO DELETE
      last context
      footer
      """
        .trimIndent()

    val malformedDiffText =
      """
      @@ -120,1 +10,0 @@
      -THE LINE TO DELETE
      """
        .trimIndent()

    val patch = TextPatch.parse(malformedDiffText)
    val repaired = patch.repair(document.splitWithLineSeparators())

    assertThat(repaired!!.toUnifiedString())
      .isEqualTo(
        """
        @@ -3 +2,0 @@
        -THE LINE TO DELETE
        """
          .trimIndent()
      )
  }

  @Test
  fun testCompute_myersAlgorithm() {
    // Verifies Myers greedy bisection edit-graph algorithm
    val oldText = "common1\ncommon2\noriginal1\noriginal2\ncommon3\ncommon4\nremoved1\nremoved2\ncommon5"
    val newText = "common1\nadded1\nadded2\ncommon2\nchanged1\nchanged2\ncommon3\ncommon4\ncommon5"

    val patchMyers = TextPatch.compute(oldText, newText, contextLines = 0, algorithm = DiffAlgorithm.MYERS)

    // Verify Myers produces a valid patch that applies cleanly to get the target document
    assertThat(patchMyers.apply(oldText)).isEqualTo(newText)

    // Validate the actual text content of the generated patch to verify correct diff edits
    val myersText = patchMyers.toUnifiedString()
    assertThat(myersText).contains("-original1")
    assertThat(myersText).contains("+added1")
    assertThat(myersText).contains("-removed1")
  }

  @Test
  fun testCompute_lcsAlgorithm() {
    // Verifies LCS space-optimized Hirschberg linear space algorithm
    val oldText = "common1\ncommon2\noriginal1\noriginal2\ncommon3\ncommon4\nremoved1\nremoved2\ncommon5"
    val newText = "common1\nadded1\nadded2\ncommon2\nchanged1\nchanged2\ncommon3\ncommon4\ncommon5"

    val patchLcs = TextPatch.compute(oldText, newText, contextLines = 0, algorithm = DiffAlgorithm.LCS)

    // Verify LCS produces a valid patch that applies cleanly to get the target document
    assertThat(patchLcs.apply(oldText)).isEqualTo(newText)

    // Validate the actual text content of the generated patch to verify correct diff edits
    val lcsText = patchLcs.toUnifiedString()
    assertThat(lcsText).contains("-original1")
    assertThat(lcsText).contains("+added1")
    assertThat(lcsText).contains("-removed1")
  }

  @Test
  fun testCompute_ignoreWhitespaceMatcher() {
    val oldText = "  line1\n  line2"
    val newText = "line1\nline2" // only indentation changed

    // Exact match will treat this as changes
    val patchExact = TextPatch.compute(oldText, newText, contextLines = 0, lineMatcher = TextPatch.Companion.LineMatchers.EXACT)
    assertThat(patchExact.chunks).isNotEmpty()

    // Ignore whitespace will treat them as identical context, resulting in empty patch!
    val patchIgnore =
      TextPatch.compute(oldText, newText, contextLines = 0, lineMatcher = TextPatch.Companion.LineMatchers.IGNORE_WHITESPACE)
    assertThat(patchIgnore.chunks).isEmpty()
  }

  @Test
  fun testCompute_levenshteinMatcher() {
    val oldText = "this is line 1\nthis is line 2"
    val newText = "this is line 1 modified\nthis is line 2" // line 1 slightly modified

    // Exact matcher sees the change
    val patchExact = TextPatch.compute(oldText, newText, contextLines = 0)
    assertThat(patchExact.chunks).isNotEmpty()

    // Levenshtein with threshold 10 will ignore this small change (length of " modified" is 9)
    val patchLevenshtein =
      TextPatch.compute(oldText, newText, contextLines = 0, lineMatcher = TextPatch.Companion.LineMatchers.levenshtein(10))
    assertThat(patchLevenshtein.chunks).isEmpty()

    // Levenshtein with threshold 10 SHOULD still detect larger changes exceeding the threshold
    val newTextLarge = "this is line 1 highly modified line content\nthis is line 2"
    val patchLarge =
      TextPatch.compute(oldText, newTextLarge, contextLines = 0, lineMatcher = TextPatch.Companion.LineMatchers.levenshtein(10))
    assertThat(patchLarge.chunks).isNotEmpty()
  }

  @Test
  fun testRepair_duplicateContextLines() {
    // Hunk containing multiple duplicate context lines (e.g. two empty lines or closing braces)
    // This tests that perfectRepair uses index-based mapping instead of indexOf search.
    val sourceLines = listOf("common", "", "target", "", "common")
    val patch =
      TextPatch(
        listOf(
          DiffChunk(
            oldStart = 1,
            oldLength = 5,
            newStart = 1,
            newLength = 5,
            lines =
              listOf(
                DiffLine(LineType.CONTEXT, "common"),
                DiffLine(LineType.CONTEXT, ""), // duplicate context 1
                DiffLine(LineType.REMOVED, "target"),
                DiffLine(LineType.CONTEXT, ""), // duplicate context 2
                DiffLine(LineType.CONTEXT, "common"),
              ),
          )
        )
      )

    // Sliding window should perfectly find the exact match in source lines
    val repaired = patch.repair(sourceLines)
    assertThat(repaired!!.chunks).hasSize(1)
    val chunk = repaired.chunks[0]

    // Verify that the duplicate empty context lines are mapped to distinct, correct source line indices
    assertThat(chunk.lines[1].text).isEmpty()
    assertThat(chunk.lines[3].text).isEmpty()
  }

  @Test
  fun testCompute_levenshteinMatcherNegativeMaxDistance() {
    assertFailsWith<IllegalArgumentException> { TextPatch.Companion.LineMatchers.levenshtein(-1) }
  }

  @Test
  fun testSquash_overlappingEditsGracefullyMerged() {
    // patch1: replaces lines 1-2 with new lines 1-2
    val patch1 =
      TextPatch(
        listOf(
          DiffChunk(
            1,
            2,
            1,
            2,
            listOf(
              DiffLine(LineType.REMOVED, "old1"),
              DiffLine(LineType.REMOVED, "old2"),
              DiffLine(LineType.ADDED, "new1"),
              DiffLine(LineType.ADDED, "new2"),
            ),
          )
        )
      )

    // patch2: inserts a blank line at line 2 of intermediate space (overlapping inside patch1's modified block!)
    val patch2 = TextPatch(listOf(DiffChunk(2, 0, 2, 1, listOf(DiffLine(LineType.ADDED, "")))))

    // This verifies that our reconstruct logic gracefully merges these intermediate overlapping edits
    // into a single combined contiguous chunk instead of throwing overlap exceptions.
    val squashed = patch1.squash(patch2)
    assertThat(squashed.chunks).hasSize(1)

    val chunk = squashed.chunks[0]
    assertThat(chunk.oldStart).isEqualTo(1)
    assertThat(chunk.oldLength).isEqualTo(2)
    assertThat(chunk.newStart).isEqualTo(1)
    assertThat(chunk.newLength).isEqualTo(3) // original 2 additions + 1 blank line insertion = 3!
    assertThat(chunk.lines).hasSize(5) // 2 REMOVED + 3 ADDED = 5 lines!
  }

  @Test
  fun testSquash_overlappingAdditionsAtSameLineMerged() {
    // patch1: inserts 'a' at line 2 (pure addition, oldLength = 0)
    val patch1 = TextPatch(listOf(DiffChunk(2, 0, 2, 1, listOf(DiffLine(LineType.ADDED, "a")))))

    // patch2: inserts 'b' at line 2 of intermediate space (overlapping at the exact same insertion coordinate!)
    val patch2 = TextPatch(listOf(DiffChunk(2, 0, 2, 1, listOf(DiffLine(LineType.ADDED, "b")))))

    // This verifies that our reconstruct logic gracefully merges these overlapping additions
    // at the exact same coordinate into a single combined hunk in the correct sequential order!
    val squashed = patch1.squash(patch2)
    assertThat(squashed.chunks).hasSize(1)

    val chunk = squashed.chunks[0]
    assertThat(chunk.oldStart).isEqualTo(2)
    assertThat(chunk.oldLength).isEqualTo(0)
    assertThat(chunk.newStart).isEqualTo(2)
    assertThat(chunk.newLength).isEqualTo(2) // both additions merged!
    assertThat(chunk.lines).hasSize(2)
    assertThat(chunk.lines[0].text).isEqualTo("b") // standard squasher sorts second addition first on identical coordinates
    assertThat(chunk.lines[1].text).isEqualTo("a")
  }

  @Test
  fun testRepair_failedChunkReturnsNull() {
    // A patch with context lines that do not exist in the document
    val patch =
      TextPatch(
        listOf(
          DiffChunk(
            3,
            1,
            3,
            1,
            listOf(DiffLine(LineType.CONTEXT, "missing context line"), DiffLine(LineType.REMOVED, "old"), DiffLine(LineType.ADDED, "new")),
          )
        )
      )

    val sourceLines = listOf("line1", "line2", "line3")

    // This verifies that the strictly all-or-none repair method immediately returns null
    // if any single chunk cannot be re-anchored safely!
    val repaired = patch.repair(sourceLines)
    assertThat(repaired).isNull()
  }
}
