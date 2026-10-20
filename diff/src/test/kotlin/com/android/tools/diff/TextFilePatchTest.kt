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
import org.junit.Test

class TextFilePatchTest {

  @Test
  fun testParse_singlePatchWithHeaders() {
    val diff =
      """
      --- path/to/old/file.txt 2026-05-21 19:54:47.000000 +0000 1234
      +++ path/to/new/file.txt 2026-05-21 19:55:00.000000 +0000 5678
      @@ -1,2 +1,2 @@
       line1
      -old
      +new
      """
        .trimIndent()

    val filePatch = TextFilePatch.parse(diff)
    assertThat(filePatch.oldFile.path).isEqualTo("path/to/old/file.txt")
    assertThat(filePatch.oldFile.timestamp).isEqualTo("2026-05-21 19:54:47.000000 +0000")
    assertThat(filePatch.oldFile.stamp).isEqualTo(1234L)

    assertThat(filePatch.newFile.path).isEqualTo("path/to/new/file.txt")
    assertThat(filePatch.newFile.timestamp).isEqualTo("2026-05-21 19:55:00.000000 +0000")
    assertThat(filePatch.newFile.stamp).isEqualTo(5678L)

    assertThat(filePatch.patch.chunks).hasSize(1)
    assertThat(filePatch.isCreate()).isFalse()
  }

  @Test
  fun testParse_multiPatch() {
    val multiDiff =
      """
      --- file1.txt
      +++ file1.txt
      @@ -1 +1 @@
      -old1
      +new1
      --- file2.txt
      +++ file2.txt
      @@ -1 +1 @@
      -old2
      +new2
      """
        .trimIndent()

    val patches = TextFilePatch.parseMultiPatch(multiDiff)
    assertThat(patches).hasSize(2)

    assertThat(patches[0].oldFile.path).isEqualTo("file1.txt")
    assertThat(patches[0].patch.chunks).hasSize(1)
    assertThat(patches[0].patch.chunks[0].lines[0].text).isEqualTo("old1")

    assertThat(patches[1].oldFile.path).isEqualTo("file2.txt")
    assertThat(patches[1].patch.chunks).hasSize(1)
    assertThat(patches[1].patch.chunks[0].lines[0].text).isEqualTo("old2")
  }

  @Test
  fun testToUnifiedString_formatting() {
    val oldHeader = FileHeader("old.txt", "2026-05-21 12:00:00", 100)
    val newHeader = FileHeader("new.txt", "2026-05-21 13:00:00", 200)
    val patch = TextPatch(listOf(DiffChunk(1, 1, 1, 1, listOf(DiffLine(LineType.CONTEXT, "line")))))

    val filePatch = TextFilePatch(oldHeader, newHeader, patch)
    val expected =
      """
      --- old.txt 2026-05-21 12:00:00 100
      +++ new.txt 2026-05-21 13:00:00 200
      @@ -1 +1 @@
       line

      """
        .trimIndent()

    assertThat(filePatch.toUnifiedString()).isEqualTo(expected)
  }

  @Test
  fun testDefaultToString() {
    val filePatch = TextFilePatch(oldFile = FileHeader("old.txt"), newFile = FileHeader("new.txt"))
    assertThat(filePatch.toString()).startsWith("TextFilePatch(oldFile=FileHeader(path=old.txt")
    assertThat(filePatch.oldFile.toString()).isEqualTo("FileHeader(path=old.txt, timestamp=null, stamp=-1)")
  }

  @Test
  fun testIsCreate() {
    val filePatch =
      TextFilePatch(
        oldFile = FileHeader(FileHeader.NO_PATH),
        newFile = FileHeader("created.txt"),
        patch = TextPatch.forFileCreation("content"),
      )
    assertThat(filePatch.isCreate()).isTrue()
  }

  @Test
  fun testParse_headersWithSpaces() {
    // Case A: Git-like paths with spaces, without timestamps
    val spacesDiff =
      """
      --- old path with spaces/file name.txt
      +++ new path with spaces/file name.txt
      @@ -1 +1 @@
      -old
      +new
      """
        .trimIndent()

    val patches = TextFilePatch.parseMultiPatch(spacesDiff)
    assertThat(patches).hasSize(1)
    val patch = patches[0]
    assertThat(patch.oldFile.path).isEqualTo("old path with spaces/file name.txt")
    assertThat(patch.oldFile.timestamp).isNull()
    assertThat(patch.oldFile.stamp).isEqualTo(-1L)

    assertThat(patch.newFile.path).isEqualTo("new path with spaces/file name.txt")

    // Case B: Paths with spaces followed by full timestamps and stamp sizes
    val spacesWithTimesDiff =
      """
      --- old path with spaces/file name.txt 2026-05-21 12:00:00.000000 +0000 1234
      +++ new path with spaces/file name.txt 2026-05-21 13:00:00.000000 +0000 5678
      @@ -1 +1 @@
      -old
      +new
      """
        .trimIndent()

    val patchesWithTimes = TextFilePatch.parseMultiPatch(spacesWithTimesDiff)
    assertThat(patchesWithTimes).hasSize(1)
    val patchWithTime = patchesWithTimes[0]
    assertThat(patchWithTime.oldFile.path).isEqualTo("old path with spaces/file name.txt")
    assertThat(patchWithTime.oldFile.timestamp).isEqualTo("2026-05-21 12:00:00.000000 +0000")
    assertThat(patchWithTime.oldFile.stamp).isEqualTo(1234L)

    assertThat(patchWithTime.newFile.path).isEqualTo("new path with spaces/file name.txt")
    assertThat(patchWithTime.newFile.timestamp).isEqualTo("2026-05-21 13:00:00.000000 +0000")
    assertThat(patchWithTime.newFile.stamp).isEqualTo(5678L)
  }

  @Test
  fun testParse_nonAggressiveTrailingNumbersHeuristic() {
    // Header where the timestamp ends in a number (the day/year) and has no size stamp.
    // It should NOT treat the trailing date numbers as the file size stamp!
    val dateOnlyDiff =
      """
      --- file.txt 2026-05-21
      +++ file.txt 2026-05-22
      @@ -1 +1 @@
      -old
      +new
      """
        .trimIndent()

    val datePatches = TextFilePatch.parseMultiPatch(dateOnlyDiff)
    assertThat(datePatches).hasSize(1)
    val datePatch = datePatches[0]
    assertThat(datePatch.oldFile.path).isEqualTo("file.txt")
    assertThat(datePatch.oldFile.timestamp).isEqualTo("2026-05-21")
    assertThat(datePatch.oldFile.stamp).isEqualTo(-1L) // Not stripped as stamp!

    assertThat(datePatch.newFile.timestamp).isEqualTo("2026-05-22")
    assertThat(datePatch.newFile.stamp).isEqualTo(-1L)

    // Header where the timestamp ends in a time token and has no size stamp.
    // It should NOT treat the time portion as the file size stamp!
    val timeOnlyDiff =
      """
      --- file.txt 2026-05-21 12:00:00
      +++ file.txt 2026-05-22 13:00:00
      @@ -1 +1 @@
      -old
      +new
      """
        .trimIndent()

    val timePatches = TextFilePatch.parseMultiPatch(timeOnlyDiff)
    assertThat(timePatches).hasSize(1)
    val timePatch = timePatches[0]
    assertThat(timePatch.oldFile.timestamp).isEqualTo("2026-05-21 12:00:00")
    assertThat(timePatch.oldFile.stamp).isEqualTo(-1L) // Not stripped as stamp!
  }

  @Test
  fun testParse_loneNumberStamps() {
    // Header with a lone number remainder (e.g., size stamp and no timestamp).
    // It should correctly identify it as a file size stamp!
    val loneNumberDiff =
      """
      --- file.txt 123456
      +++ file.txt 789012
      @@ -1 +1 @@
      -old
      +new
      """
        .trimIndent()

    val patches = TextFilePatch.parseMultiPatch(loneNumberDiff)
    assertThat(patches).hasSize(1)
    val patch = patches[0]
    assertThat(patch.oldFile.path).isEqualTo("file.txt")
    assertThat(patch.oldFile.timestamp).isNull() // No timestamp!
    assertThat(patch.oldFile.stamp).isEqualTo(123456L) // Correctly parsed!

    assertThat(patch.newFile.path).isEqualTo("file.txt")
    assertThat(patch.newFile.timestamp).isNull()
    assertThat(patch.newFile.stamp).isEqualTo(789012L)
  }

  @Test
  fun testParse_ignoresLeadingMetadata() {
    // standard git format-patch file content with commit headers and git index details before hunks
    val gitPatch =
      """
      From 5cfa42d... Mon Sep 17 00:00:00 2001
      From: Kurt Dresner <kdresner@google.com>
      Date: Thu May 21 ...
      Subject: [PATCH] Add diff module

      This is a commit message.
      ---
       diff/src/main/kotlin/com/android/tools/diff/TextPatch.kt | 10 +++
       1 file changed, 10 insertions(+)

      diff --git a/diff/src/main/kotlin/com/android/tools/diff/TextPatch.kt b/diff/src/main/kotlin/com/android/tools/diff/TextPatch.kt
      index 1234..5678 100644
      --- a/diff/src/main/kotlin/com/android/tools/diff/TextPatch.kt
      +++ b/diff/src/main/kotlin/com/android/tools/diff/TextPatch.kt
      @@ -1 +1 @@
      -old
      +new
      """
        .trimIndent()

    val patches = TextFilePatch.parseMultiPatch(gitPatch)
    assertThat(patches).hasSize(1)
    val patch = patches[0]
    assertThat(patch.oldFile.path).isEqualTo("a/diff/src/main/kotlin/com/android/tools/diff/TextPatch.kt")
    assertThat(patch.newFile.path).isEqualTo("b/diff/src/main/kotlin/com/android/tools/diff/TextPatch.kt")
    assertThat(patch.patch.chunks).hasSize(1)
  }

  @Test
  fun testParse_noNewlineAtEndOfFile() {
    // File patch containing standard \ No newline warnings on both deleted and added lines
    val noNewlineDiff =
      """
      --- old_file.txt
      +++ new_file.txt
      @@ -1 +1 @@
      -old text
      \ No newline at end of file
      +new text
      \ No newline at end of file
      """
        .trimIndent()

    val patches = TextFilePatch.parseMultiPatch(noNewlineDiff)
    assertThat(patches).hasSize(1)
    val patch = patches[0]
    assertThat(patch.oldFile.path).isEqualTo("old_file.txt")
    assertThat(patch.newFile.path).isEqualTo("new_file.txt")

    assertThat(patch.patch.chunks).hasSize(1)
    val chunk = patch.patch.chunks[0]
    assertThat(chunk.lines).hasSize(2)
    assertThat(chunk.lines[0].separator).isEqualTo(LineSeparator.NONE)
    assertThat(chunk.lines[1].separator).isEqualTo(LineSeparator.NONE)

    // Re-serialize and verify it perfectly preserves both No Newline warnings
    val expectedFormatted =
      """
      --- old_file.txt
      +++ new_file.txt
      @@ -1 +1 @@
      -old text
      \ No newline at end of file
      +new text
      \ No newline at end of file

      """
        .trimIndent()
    assertThat(patch.toUnifiedString()).isEqualTo(expectedFormatted)
  }

  @Test
  fun testParse_noHeaders() {
    val diff = TextFilePatch.parse("")
    assertThat(diff.toUnifiedString()).isEmpty()
  }

  @Test
  fun testParseMultiPatch_noChunksDiff() {
    val diffText =
      """
      --- oldPath 2020-03-07 00:11:22.111111 +0000 100
      +++ newPath 2025-01-02 00:01:02.222222 +0000 200
      --- oldPath 2020-03-07 00:11:22.333333 +0000 200
      +++ newPath 2025-01-02 00:01:02.444444 +0000 300
      @@ -1,3 +1,3 @@
       line 1: unchanged
      -line 2: to be changed
      +line 2: has been changed
       line 3: unchanged
      \ No newline at end of file
      --- oldPath 2020-03-07 00:11:22.555555 +0000 300
      +++ newPath 2025-01-02 00:01:02.666666 +0000 400
      --- oldPath 2020-03-07 00:11:22.777777 +0000 400
      +++ newPath 2025-01-02 00:01:02.888888 +0000 500

      """
        .trimIndent()

    val patches = TextFilePatch.parseMultiPatch(diffText)
    assertThat(patches).hasSize(4)

    val first = patches[0]
    assertThat(first.oldFile.path).isEqualTo("oldPath")
    assertThat(first.newFile.path).isEqualTo("newPath")
    assertThat(first.oldFile.stamp).isEqualTo(100L)
    assertThat(first.newFile.stamp).isEqualTo(200L)
    assertThat(first.patch.chunks).isEmpty()

    val second = patches[1]
    assertThat(second.oldFile.stamp).isEqualTo(200L)
    assertThat(second.newFile.stamp).isEqualTo(300L)
    assertThat(second.patch.chunks).isNotEmpty()

    val third = patches[2]
    assertThat(third.oldFile.stamp).isEqualTo(300L)
    assertThat(third.newFile.stamp).isEqualTo(400L)
    assertThat(third.patch.chunks).isEmpty()

    val fourth = patches[3]
    assertThat(fourth.oldFile.stamp).isEqualTo(400L)
    assertThat(fourth.newFile.stamp).isEqualTo(500L)
    assertThat(fourth.patch.chunks).isEmpty()
  }

  @Test
  fun testParse_fullHeadersOnEmptyChunkDiff() {
    val diff =
      TextFilePatch.parse(
        """
        --- oldPath 2020-03-07 00:11:22.123456 -0800 100
        +++ newPath 2025-01-02 00:01:02.345678 -0800 999
        """
          .trimIndent()
      )

    assertThat(diff.oldFile.path).isEqualTo("oldPath")
    assertThat(diff.oldFile.timestamp).isEqualTo("2020-03-07 00:11:22.123456 -0800")
    assertThat(diff.oldFile.stamp).isEqualTo(100L)

    assertThat(diff.newFile.path).isEqualTo("newPath")
    assertThat(diff.newFile.timestamp).isEqualTo("2025-01-02 00:01:02.345678 -0800")
    assertThat(diff.newFile.stamp).isEqualTo(999L)
    assertThat(diff.patch.chunks).isEmpty()
  }

  @Test
  fun testParse_noStamps() {
    val diff =
      TextFilePatch.parse(
        """
        --- oldPath 2020-03-07 00:11:22.123456 -0800
        +++ newPath 2025-01-02 00:01:02.345678 +0000
        """
          .trimIndent()
      )

    assertThat(diff.oldFile.path).isEqualTo("oldPath")
    assertThat(diff.oldFile.timestamp).isEqualTo("2020-03-07 00:11:22.123456 -0800")
    assertThat(diff.oldFile.stamp).isEqualTo(-1L)

    assertThat(diff.newFile.path).isEqualTo("newPath")
    assertThat(diff.newFile.timestamp).isEqualTo("2025-01-02 00:01:02.345678 +0000")
    assertThat(diff.newFile.stamp).isEqualTo(-1L)
  }

  @Test
  fun testParse_noTimeOrStamp() {
    val diff =
      TextFilePatch.parse(
        """
        --- oldPath
        +++ newPath
        """
          .trimIndent()
      )
    assertThat(diff.oldFile.path).isEqualTo("oldPath")
    assertThat(diff.oldFile.timestamp).isNull()
    assertThat(diff.oldFile.stamp).isEqualTo(-1L)

    assertThat(diff.newFile.path).isEqualTo("newPath")
    assertThat(diff.newFile.timestamp).isNull()
    assertThat(diff.newFile.stamp).isEqualTo(-1L)
  }

  @Test
  fun testForFileCreation_withAndWithoutTrailingNewline() {
    val withNewline = "a\nb\nc\n"
    val filePatchWithNewline =
      TextFilePatch(
        oldFile = FileHeader(FileHeader.NO_PATH),
        newFile = FileHeader("new/file"),
        patch = TextPatch.forFileCreation(withNewline),
      )
    assertThat(filePatchWithNewline.toUnifiedString().trimEnd())
      .isEqualTo(
        """
        --- /dev/null
        +++ new/file
        @@ -0,0 +1,3 @@
        +a
        +b
        +c
        """
          .trimIndent()
      )
    assertThat(filePatchWithNewline.patch.apply("")).isEqualTo(withNewline)

    val withoutNewline = "a\nb\nc"
    val filePatchWithoutNewline =
      TextFilePatch(
        oldFile = FileHeader(FileHeader.NO_PATH),
        newFile = FileHeader("new/file"),
        patch = TextPatch.forFileCreation(withoutNewline),
      )
    assertThat(filePatchWithoutNewline.toUnifiedString().trimEnd())
      .isEqualTo(
        """
        --- /dev/null
        +++ new/file
        @@ -0,0 +1,3 @@
        +a
        +b
        +c
        \ No newline at end of file
        """
          .trimIndent()
      )
    assertThat(filePatchWithoutNewline.patch.apply("")).isEqualTo(withoutNewline)
  }
}
