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
package com.android.ide.common.util

import com.android.ide.common.util.Diffs.Companion.parseDiff
import org.junit.Assert.assertEquals
import org.junit.Test

class DiffsTest {

  private fun diff(originalText: String, newText: String, windowSize: Int = 3, trimEnds: Boolean = false): String {
    return Diffs.diff(originalText, newText, windowSize, trimEnds)
  }

  @Test
  fun testEmptyStrings() {
    assertEquals("", diff("", ""))
  }

  @Test
  fun testIdenticalStrings() {
    val text = "This is a test.\nAnother line."
    assertEquals("", diff(text, text))
  }

  @Test
  fun testSimpleAddition() {
    val original = "Line 1"
    val new = "Line 1\nLine 2"
    val expectedDiff =
      """
      @@ -1 +1,2 @@
      -Line 1
      +Line 1
      +Line 2
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new))

    val original2 = "Line 1\n"
    val new2 = "Line 1\nLine 2\n"
    val expectedDiff2 =
      """
      @@ -1 +1,2 @@
       Line 1
      +Line 2
      """
        .trimIndent()
    assertEquals(expectedDiff2, diff(original2, new2))
  }

  @Test
  fun testSimpleDeletion() {
    val original = "Line 1\nLine 2\n"
    val new = "Line 1\n"
    val expectedDiff =
      """
      @@ -1,2 +1 @@
       Line 1
      -Line 2
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new))

    val original2 = "Line 1\nLine 2"
    val new2 = "Line 1"
    val expectedDiff2 =
      """
      @@ -1,2 +1 @@
      -Line 1
      -Line 2
      +Line 1
      """
        .trimIndent()
    assertEquals(expectedDiff2, diff(original2, new2))
  }

  @Test
  fun testSimpleChange() {
    val original = "Line 1\nOld Line\nLine 3"
    val new = "Line 1\nNew Line\nLine 3"
    val expectedDiff =
      """
      @@ -1,3 +1,3 @@
       Line 1
      -Old Line
      +New Line
       Line 3
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new))
  }

  @Test
  fun testContextLines() {
    val original = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"
    val new = "Line 1\nLine 2\nChanged\nLine 4\nLine 5"
    // Expect 2 context lines (default is 3, but here it's limited by actual surrounding lines)
    val expectedDiff =
      """
      @@ -1,5 +1,5 @@
       Line 1
       Line 2
      -Line 3
      +Changed
       Line 4
       Line 5
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new, windowSize = 2))

    val expectedDiff1Context =
      """
      @@ -2,3 +2,3 @@
       Line 2
      -Line 3
      +Changed
       Line 4
      """
        .trimIndent()
    assertEquals(expectedDiff1Context, diff(original, new, windowSize = 1))
  }

  @Test
  fun testAdditionAtBeginning() {
    val original = "Line 2\nLine 3"
    val new = "Line 1\nLine 2\nLine 3"
    val expectedDiff =
      """
      @@ -1,2 +1,3 @@
      +Line 1
       Line 2
       Line 3
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new))
  }

  @Test
  fun testAdditionAtEnd() {
    val original = "Line 1\nLine 2"
    val new = "Line 1\nLine 2\nLine 3"
    val expectedDiff =
      """
      @@ -1,2 +1,3 @@
       Line 1
      -Line 2
      +Line 2
      +Line 3
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new))

    val original2 = "Line 1\nLine 2\n"
    val new2 = "Line 1\nLine 2\nLine 3\n"
    val expectedDiff2 =
      """
      @@ -1,2 +1,3 @@
       Line 1
       Line 2
      +Line 3
      """
        .trimIndent()
    assertEquals(expectedDiff2, diff(original2, new2))
  }

  @Test
  fun testDeletionAtBeginning() {
    val original = "Line 1\nLine 2\nLine 3"
    val new = "Line 2\nLine 3"
    val expectedDiff =
      """
      @@ -1,3 +1,2 @@
      -Line 1
       Line 2
       Line 3
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new))
  }

  @Test
  fun testDeletionAtEnd() {
    val original = "Line 1\nLine 2\nLine 3\n"
    val new = "Line 1\nLine 2\n"
    val expectedDiff =
      """
      @@ -1,3 +1,2 @@
       Line 1
       Line 2
      -Line 3
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new))

    val original2 = "Line 1\nLine 2\nLine 3"
    val new2 = "Line 1\nLine 2"
    val expectedDiff2 =
      """
      @@ -1,3 +1,2 @@
       Line 1
      -Line 2
      -Line 3
      +Line 2
      """
        .trimIndent()
    assertEquals(expectedDiff2, diff(original2, new2))
  }

  @Test
  fun testMultipleChanges() {
    val original = "Line A\nLine B\nLine C\nLine D\nLine E"
    val new = "Line A\nLine X\nLine C\nLine Y\nLine E"
    val expectedDiff =
      """
      @@ -1,5 +1,5 @@
       Line A
      -Line B
      +Line X
       Line C
      -Line D
      +Line Y
       Line E
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new, windowSize = 1))
  }

  @Test
  fun testOriginalEmpty() {
    val original = ""
    val new = "Line 1\nLine 2"
    val expectedDiff =
      """
      @@ -0,0 +1,2 @@
      +Line 1
      +Line 2
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new))
  }

  @Test
  fun testNewEmpty() {
    val original = "Line 1\nLine 2"
    val new = ""
    val expectedDiff =
      """
      @@ -1,2 +0,0 @@
      -Line 1
      -Line 2
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new))
  }

  @Test
  fun testNoContextLines() {
    val original = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"
    val new = "Line 1\nLine 2\nChanged\nLine 4\nLine 5"
    val expectedDiff =
      """
      @@ -3 +3 @@
      -Line 3
      +Changed
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new, windowSize = 0))
  }

  @Test
  fun testGitExample1() {
    val original =
      """
      Git is a distributed version control system.
      It was created by Linus Torvalds in 2005.
      It is used for tracking changes in source code during software development.
      It is free and open-source.
      """
        .trimIndent()
    val new =
      """
      Git is a distributed version control system.
      It was created by Linus Torvalds in 2005 for Linux kernel development.
      It is used for tracking changes in source code during software development.
      It is free and open-source, available under the GPL.
      """
        .trimIndent()
    val expectedDiff =
      """
      @@ -1,4 +1,4 @@
       Git is a distributed version control system.
      -It was created by Linus Torvalds in 2005.
      +It was created by Linus Torvalds in 2005 for Linux kernel development.
       It is used for tracking changes in source code during software development.
      -It is free and open-source.
      +It is free and open-source, available under the GPL.
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new, windowSize = 1))
  }

  @Test
  fun testCompletelyDifferentText() {
    val original = "This is the first document.\nIt has two lines."
    val new = "This is an entirely new document.\nWith different content."
    val expectedDiff =
      """
      @@ -1,2 +1,2 @@
      -This is the first document.
      -It has two lines.
      +This is an entirely new document.
      +With different content.
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new))
  }

  @Test
  fun testHunkBoundariesAtFileStart() {
    val original = "line1\nline2\nline3"
    val new = "changed1\nline2\nline3"
    val expectedDiff =
      """
      @@ -1,3 +1,3 @@
      -line1
      +changed1
       line2
       line3
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new, windowSize = 3))
  }

  @Test
  fun testHunkBoundariesAtFileEnd() {
    val original = "line1\nline2\nline3"
    val new = "line1\nline2\nchanged3"
    val expectedDiff =
      """
      @@ -1,3 +1,3 @@
       line1
       line2
      -line3
      +changed3
      """
        .trimIndent()
    assertEquals(expectedDiff, diff(original, new, windowSize = 3))
  }

  @Test
  fun parse_simpleDiff() {
    val diffText =
      """
      @@ -1,3 +1,4 @@
      -old line 1
      -old line 2
      -old line 3
      +new line 1
      +new line 2
      +new line 3
      +new line 4
      """
        .trimIndent()
    val hunks = parseDiff(diffText)

    assertEquals(1, hunks.size)
    val hunk = hunks[0]
    assertEquals(1, hunk.originalFileStartLine)
    assertEquals(3, hunk.originalFileLines)
    assertEquals(1, hunk.newFileStartLine)
    assertEquals(4, hunk.newFileLines)
    assertEquals("@@ -1,3 +1,4 @@", hunk.header)
    assertEquals(
      """
      -old line 1
      -old line 2
      -old line 3
      +new line 1
      +new line 2
      +new line 3
      +new line 4
      """
        .trimIndent()
        .lines()
        .joinToString("\n"),
      hunk.content.trim(),
    )
  }

  @Test
  fun testNoTrimEnds() {
    val original = "Line 1  \nLine 2\nLine 3\n"
    val new = "Line 1  \nLine 1b  \nLine 2\nLine 3\n"
    val expectedDiff = "@@ -1,3 +1,4 @@\n" + " Line 1  \n" + "+Line 1b  \n" + " Line 2\n" + " Line 3"
    assertEquals(expectedDiff, diff(original, new, trimEnds = false))
  }

  @Test
  fun testTrimEnds() {
    val original = "Line 1  \nLine 2\nLine 3  \n"
    val new = "Line 1  \nLine 1b  \nLine 2\nLine 3  \n"
    val expectedDiff = "@@ -1,3 +1,4 @@\n" + " Line 1\n" + "+Line 1b\n" + " Line 2\n" + " Line 3"
    assertEquals(expectedDiff, diff(original, new, trimEnds = true))
  }

  @Test
  fun testGitDifference() {
    val original =
      """
      deleted line `
      line 2
      line 3
      line 4
      line 5
      line 6
      line 7
      repeated line
      deleted line 1
      deleted line 2
      repeated line
      deleted line 5
      line 8
      deleted line 6
      deleted line 7
      """
        .trimIndent()
    val new =
      """
      new line 1
      line 2
      line 3
      line 4
      line 5
      line 6
      line 7
      repeated line
      line 8
      new line 2
      """
        .trimIndent()

    // This is the output of this diff produced by git diff;
    // it seems to favor keeping the first repeated line occurrence
    // instead of the second.
    @Suppress("unused", "UnusedVariable")
    val gitDiff =
      """
      @@ -1,4 +1,4 @@
      -deleted line `
      +new line 1
       line 2
       line 3
       line 4
      @@ -6,10 +6,5 @@
       line 6
       line 7
       repeated line
      -deleted line 1
      -deleted line 2
      -repeated line
      -deleted line 5
       line 8
      -deleted line 6
      -deleted line 7
      +new line 2
      """
        .trimIndent()

    val expectedDiff =
      """
      @@ -1,15 +1,10 @@
      -deleted line `
      +new line 1
       line 2
       line 3
       line 4
       line 5
       line 6
       line 7
      -repeated line
      -deleted line 1
      -deleted line 2
       repeated line
      -deleted line 5
       line 8
      -deleted line 6
      -deleted line 7
      +new line 2
      """
        .trimIndent()

    assertEquals(expectedDiff, diff(original, new, trimEnds = true))
  }
}
