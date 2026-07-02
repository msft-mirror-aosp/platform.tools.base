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
import com.android.zipflinger.ZipSource
import org.junit.Assert
import org.junit.Test

class FileMergerOutputsTest {

  class MockSourceMergeOutputWriter : SourceMergeOutputWriter {
    var createZipSourceCalled = false
    var createInputStreamCalled = false
    var openCalled = false
    var closeCalled = false

    override fun create(path: String, source: com.android.zipflinger.ZipSource) {
      createZipSourceCalled = true
    }

    override fun create(path: String, data: java.io.InputStream, compress: Boolean) {
      createInputStreamCalled = true
    }

    override fun replace(path: String, source: com.android.zipflinger.ZipSource) {}

    override fun replace(path: String, data: java.io.InputStream, compress: Boolean) {}

    override fun remove(path: String) {}

    override fun open() {
      openCalled = true
    }

    override fun close() {
      closeCalled = true
    }
  }

  @Test
  fun testFromAlgorithmAndWriterWithJavaResZipSourceWriter() {
    val algorithm = JavaResZipSourceMerger(ParsedPackagingOptions(emptyList(), emptyList(), emptyList()))
    val writer = MockSourceMergeOutputWriter()
    val output = FileMergerOutputs.fromAlgorithmAndWriter(algorithm, writer)

    output.open()
    output.use {
      val input = FileMergerTestInput("i0")
      input.use {
        input.open()
        input.add("path")
        output.create("path", listOf(input), true)
      }
      // Since FileMergerTestInput.openAsZipSource returns a ZipSource,
      // FileMergerOutputs will call writer.create(path, zipSource)
      Assert.assertTrue(writer.createZipSourceCalled)
    }
  }

  @Test
  fun testFromAlgorithmAndWriterWithJavaResZipSourceWriterMerged() {
    // Force MERGE action to trigger InputStream fallback (returns ByteArray from merger)
    val algorithm = JavaResZipSourceMerger(ParsedPackagingOptions(emptyList(), emptyList(), listOf("path")))
    val writer = MockSourceMergeOutputWriter()
    val output = FileMergerOutputs.fromAlgorithmAndWriter(algorithm, writer)

    output.open()
    output.use {
      val input = FileMergerTestInput("i0")
      input.add("path")
      output.create("path", listOf(input), true)
      // Since it's a MERGE action, JavaResZipSourceMerger returns a ByteArray,
      // and FileMergerOutputs will call writer.create(path, inputStream, compress)
      Assert.assertTrue(writer.createInputStreamCalled)
    }
  }

  @Test
  fun testFromAlgorithmAndWriterWithFallbackToInputStream() {
    val algorithm = JavaResZipSourceMerger(ParsedPackagingOptions(emptyList(), emptyList(), emptyList()))
    val writer = MockSourceMergeOutputWriter()
    val output = FileMergerOutputs.fromAlgorithmAndWriter(algorithm, writer)

    output.open()
    output.use {
      val input =
        object : FileMergerInput {
          override fun getName() = "i0"

          override fun getAllPaths() = setOf("path")

          override fun open() {}

          override fun close() {}

          override fun openPath(path: String) = java.io.ByteArrayInputStream(byteArrayOf())
        }
      output.create("path", listOf(input), true)

      // Should fall back to InputStream
      Assert.assertTrue(writer.createInputStreamCalled)
      Assert.assertFalse(writer.createZipSourceCalled)
    }
  }
}
