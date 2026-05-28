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

class DiffLineTest {
  @Test
  fun testToUnifiedString() {
    assertThat(DiffLine(LineType.CONTEXT, "text").toUnifiedString()).isEqualTo(" text")
    assertThat(DiffLine(LineType.ADDED, "text").toUnifiedString()).isEqualTo("+text")
    assertThat(DiffLine(LineType.REMOVED, "text").toUnifiedString()).isEqualTo("-text")
  }

  @Test
  fun testDefaultToString() {
    val line = DiffLine(LineType.ADDED, "text", LineSeparator.CRLF)
    assertThat(line.toString()).isEqualTo("DiffLine(type=ADDED, text=text, separator=CRLF)")
  }

  @Test
  fun testFromRawLine_malformedEndings() {
    // Valid standard line separator extractions
    val lfLine = DiffLine.fromRawLine("hello\n", LineType.CONTEXT)
    assertThat(lfLine.text).isEqualTo("hello")
    assertThat(lfLine.separator).isEqualTo(LineSeparator.LF)

    val crlfLine = DiffLine.fromRawLine("hello\r\n", LineType.ADDED)
    assertThat(crlfLine.text).isEqualTo("hello")
    assertThat(crlfLine.separator).isEqualTo(LineSeparator.CRLF)

    val crLine = DiffLine.fromRawLine("hello\r", LineType.REMOVED)
    assertThat(crLine.text).isEqualTo("hello")
    assertThat(crLine.separator).isEqualTo(LineSeparator.CR)

    // Malformed \n\r line separator extraction:
    // It should only extract the trailing \r as separator, leaving the malformed \n
    // cleanly preserved inside the text content!
    val malformedLine = DiffLine.fromRawLine("hello\n\r", LineType.CONTEXT)
    assertThat(malformedLine.text).isEqualTo("hello\n") // \n is preserved in text!
    assertThat(malformedLine.separator).isEqualTo(LineSeparator.CR) // \r is mapped as separator
  }
}
