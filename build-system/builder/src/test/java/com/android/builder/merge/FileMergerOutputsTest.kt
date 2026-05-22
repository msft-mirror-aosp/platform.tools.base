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

package com.android.builder.merge

import com.android.builder.packaging.ParsedPackagingOptions
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class FileMergerOutputsTest {

  @Test
  fun testFromAlgorithmAndWriter() {
    val mergedStream = ByteArrayInputStream(byteArrayOf(1))
    val algorithm = InputStreamMerger(ParsedPackagingOptions(emptyList(), emptyList(), emptyList()))
    val writer = mock(MergeOutputWriter::class.java)
    val output = FileMergerOutputs.fromAlgorithmAndWriter(algorithm, writer)

    writer.open()
    writer.use {
      output.open()
      output.use {
        val input = FileMergerTestInput("i0")
        input.add("path")
        output.create("path", listOf(input), true)
        verify(writer).create(eq("path"), any(InputStream::class.java), eq(true))
      }
    }
  }
}
