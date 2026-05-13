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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LintReportRendererTest {

  private val sourceText =
    """
    fun main() {
      println("Hello")
      val x = 1 / 0
      println("World")
    }
    """
      .trimIndent()

  @Test
  fun testExtractSourceContext() {
    val location = LintLocation("file.kt", 3, 5) // "val x = 1 / 0" is line 3

    val context = extractSourceContext(location = location, sourceText = sourceText, errorLine2 = "          ~~~~~", contextSize = 1)

    val expected =
      """
      <span class="lineno">    2 </span>  println(&quot;Hello&quot;)
      <span class="caretline"><span class="lineno">    3 </span>  val x = 1 / 0</span>
      <span class="lineno">      </span><span class="warning">          ~~~~~</span>
      <span class="lineno">    4 </span>  println(&quot;World&quot;)

      """
        .trimIndent()

    assertEquals(expected, context)
  }

  @Test
  fun testExtractSourceContext_beginningOfFile() {
    val location = LintLocation("file.kt", 1, 1)

    val context = extractSourceContext(location = location, sourceText = sourceText, errorLine2 = "~~~", contextSize = 1)

    val expected =
      """
      <span class="caretline"><span class="lineno">    1 </span>fun main() {</span>
      <span class="lineno">      </span><span class="warning">~~~</span>
      <span class="lineno">    2 </span>  println(&quot;Hello&quot;)

      """
        .trimIndent()

    assertEquals(expected, context)
  }

  @Test
  fun testExtractSourceContext_endOfFile() {
    val location = LintLocation("file.kt", 5, 1)

    val context = extractSourceContext(location = location, sourceText = sourceText, errorLine2 = "~", contextSize = 1)

    val expected =
      """
      <span class="lineno">    4 </span>  println(&quot;World&quot;)
      <span class="caretline"><span class="lineno">    5 </span>}</span>
      <span class="lineno">      </span><span class="warning">~</span>

      """
        .trimIndent()

    assertEquals(expected, context)
  }

  @Test
  fun testExtractSourceContext_nullSourceText() {
    val location = LintLocation("file.kt", 3, 5)
    val context = extractSourceContext(location = location, sourceText = null, errorLine2 = "~~~~~", contextSize = 1)
    assertNull(context)
  }

  @Test
  fun testExtractSourceContext_emptySourceText() {
    val location = LintLocation("file.kt", 3, 5)
    val context = extractSourceContext(location = location, sourceText = "", errorLine2 = "~~~~~", contextSize = 1)
    assertNull(context)
  }

  @Test
  fun testExtractSourceContext_noErrorLine2() {
    val location = LintLocation("file.kt", 3, 5)
    val context = extractSourceContext(location = location, sourceText = sourceText, errorLine2 = null, contextSize = 1)

    val expected =
      """
      <span class="lineno">    2 </span>  println(&quot;Hello&quot;)
      <span class="caretline"><span class="lineno">    3 </span>  val x = 1 / 0</span>
      <span class="lineno">    4 </span>  println(&quot;World&quot;)

      """
        .trimIndent()

    assertEquals(expected, context)
  }

  @Test
  fun testExtractSourceContext_farOutOfBoundsLine() {
    val location = LintLocation("file.kt", 100, 1)
    val context = extractSourceContext(location = location, sourceText = sourceText, errorLine2 = "~", contextSize = 1)
    assertNull(context)
  }

  @Test
  fun testExtractSourceContext_outOfBoundsByOneLine() {
    val location = LintLocation("file.kt", 6, 1)
    val context = extractSourceContext(location = location, sourceText = sourceText, errorLine2 = "~", contextSize = 1)
    assertNull(context)
  }
}
