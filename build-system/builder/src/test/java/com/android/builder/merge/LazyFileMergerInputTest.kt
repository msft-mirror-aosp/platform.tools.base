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

import com.android.zipflinger.BytesSource
import com.android.zipflinger.ZipArchive
import com.google.common.io.ByteStreams
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LazyFileMergerInputTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun testLazyFileMergerInput() {
    val jarFile = File(temporaryFolder.root, "test.jar")
    ZipArchive(jarFile.toPath()).use { archive ->
      archive.add(BytesSource("content1".toByteArray(StandardCharsets.UTF_8), "file1", Deflater.NO_COMPRESSION))
      archive.add(BytesSource("content2".toByteArray(StandardCharsets.UTF_8), "dir/file2", Deflater.DEFAULT_COMPRESSION))
    }

    val input = LazyFileMergerInput("testInput", jarFile)

    assertThat(input.getName()).isEqualTo("testInput")
    assertThat(input.getAllPaths()).containsExactly("file1", "dir/file2")

    input.open()
    input.use { input ->
      input.openPath("file1").use { isr -> assertThat(String(ByteStreams.toByteArray(isr), StandardCharsets.UTF_8)).isEqualTo("content1") }
      input.openPath("dir/file2").use { isr ->
        assertThat(String(ByteStreams.toByteArray(isr), StandardCharsets.UTF_8)).isEqualTo("content2")
      }
    }
  }
}
