/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ide.common.pagealign

import com.android.ide.common.pagealign.AlignmentProblem.LoadSectionNotAligned
import com.android.ide.common.pagealign.AlignmentProblem.RelroEndNotAligned
import com.android.ide.common.pagealign.AlignmentProblem.RelroStartNotAligned
import com.android.ide.common.pagealign.AlignmentProblem.ZipEntryNotAligned
import com.android.ide.common.pagealign.PageAlignUtilsTest.ZipBuilder.ZipEntryOptions
import com.android.ide.common.pagealign.PageAlignUtilsTest.ZipBuilder.ZipEntryOptions.AlignedCompressed
import com.android.ide.common.pagealign.PageAlignUtilsTest.ZipBuilder.ZipEntryOptions.AlignedUncompressed
import com.android.ide.common.pagealign.PageAlignUtilsTest.ZipBuilder.ZipEntryOptions.UnalignedCompressed
import com.android.ide.common.pagealign.PageAlignUtilsTest.ZipBuilder.ZipEntryOptions.UnalignedUncompressed
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.TreeMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import kotlin.collections.find
import kotlin.math.max
import kotlin.reflect.KClass
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Test

// First bytes of
// ndk/28.0.12433566/toolchains/llvm/prebuilt/linux-x86_64/lib/aarch64-unknown-linux-musl/libc++abi.so
val SO_FILE_16K_ALIGNED =
  byteArrayOf(
    127,
    69,
    76,
    70,
    2,
    1,
    1,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    3,
    0,
    -73,
    0,
    1,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    64,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    56,
    92,
    7,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    64,
    0,
    56,
    0,
    10,
    0,
    64,
    0,
    35,
    0,
    33,
    0,
    6,
    0,
    0,
    0,
    4,
    0,
    0,
    0,
    64,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    64,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    64,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    48,
    2,
    0,
    0,
    0,
    0,
    0,
    0,
    48,
    2,
    0,
    0,
    0,
    0,
    0,
    0,
    8,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    4,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    -4,
    -7,
    1,
    0,
    0,
    0,
    0,
    0,
    -4,
    -7,
    1,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    5,
    0,
    0,
    0,
    -4,
    -7,
    1,
    0,
    0,
    0,
    0,
    0,
    -4,
    -7,
    2,
    0,
    0,
    0,
    0,
    0,
    -4,
    -7,
    2,
    0,
    0,
    0,
    0,
    0,
    -124,
    -89,
    2,
    0,
    0,
    0,
    0,
    0,
    -124,
    -89,
    2,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    6,
    0,
    0,
    0,
    -128,
    -95,
    4,
    0,
    0,
    0,
    0,
    0,
    -128,
    -95,
    6,
    0,
    0,
    0,
    0,
    0,
    -128,
    -95,
    6,
    0,
    0,
    0,
    0,
    0,
    56,
    61,
    0,
    0,
    0,
    0,
    0,
    0,
    -128,
    62,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    6,
    0,
    0,
    0,
    -72,
    -34,
    4,
    0,
    0,
    0,
    0,
    0,
    -72,
    -34,
    7,
    0,
    0,
    0,
    0,
    0,
    -72,
    -34,
    7,
    0,
    0,
    0,
    0,
    0,
    104,
    3,
    0,
    0,
    0,
    0,
    0,
    0,
    -88,
    14,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    0,
    0,
    7,
    0,
    0,
    0,
    4,
    0,
    0,
    0,
    -128,
    -95,
    4,
    0,
    0,
    0,
    0,
    0,
    -128,
    -95,
    5,
    0,
    0,
    0,
    0,
    0,
    -128,
    -95,
    5,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    16,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    8,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    2,
    0,
    0,
    0,
    6,
    0,
    0,
    0,
    -8,
    -37,
    4,
    0,
    0,
    0,
    0,
    0,
    -8,
    -37,
    6,
    0,
    0,
    0,
    0,
    0,
    -8,
    -37,
    6,
    0,
    0,
    0,
    0,
    0,
    -112,
    1,
    0,
    0,
    0,
    0,
    0,
    0,
    -112,
    1,
    0,
    0,
    0,
    0,
    0,
    0,
    8,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    82,
    -27,
    116,
    100,
    4,
    0,
    0,
    0,
    -128,
    -95,
    4,
    0,
    0,
    0,
    0,
    0,
    -128,
    -95,
    6,
    0,
    0,
    0,
    0,
    0,
    -128,
    -95,
    6,
    0,
    0,
    0,
    0,
    0,
    56,
    61,
    0,
    0,
    0,
    0,
    0,
    0,
    -128,
    62,
    0,
    0,
    0,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    80,
    -27,
    116,
    100,
    4,
    0,
    0,
    0,
    92,
    106,
    1,
    0,
    0,
    0,
    0,
    0,
    92,
    106,
    1,
    0,
    0,
    0,
    0,
    0,
    92,
    106,
    1,
    0,
    0,
    0,
    0,
    0,
    116,
    17,
    0,
    0,
    0,
    0,
    0,
    0,
    116,
    17,
    0,
    0,
    0,
    0,
    0,
    0,
    4,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    81,
    -27,
    116,
    100,
    6,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    32,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
  )

val SO_FILE_NOT_16K_ALIGNED =
  byteArrayOf(
    127,
    69,
    76,
    70,
    2,
    1,
    1,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    3,
    0,
    62,
    0,
    1,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    64,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    120,
    -34,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    64,
    0,
    56,
    0,
    6,
    0,
    64,
    0,
    33,
    0,
    31,
    0,
    6,
    0,
    0,
    0,
    4,
    0,
    0,
    0,
    64,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    64,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    64,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    80,
    1,
    0,
    0,
    0,
    0,
    0,
    0,
    80,
    1,
    0,
    0,
    0,
    0,
    0,
    0,
    8,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    5,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    112,
    -54,
    0,
    0,
    0,
    0,
    0,
    0,
    112,
    -54,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    16,
    0,
    0,
    0,
    0,
    0,
    0,
    1,
    0,
    0,
    0,
    6,
    0,
    0,
    0,
    0,
    -48,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    -48,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    -48,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    12,
    0,
    0,
    0,
    0,
    0,
    0,
    -55,
    14,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    16,
    0,
    0,
    0,
    0,
    0,
    0,
    2,
    0,
    0,
    0,
    6,
    0,
    0,
    0,
    0,
    -48,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    -48,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    -48,
    0,
    0,
    0,
    0,
    0,
    0,
    96,
    2,
    0,
    0,
    0,
    0,
    0,
    0,
    96,
    2,
    0,
    0,
    0,
    0,
    0,
    0,
    8,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    80,
    -27,
    116,
    100,
    4,
    0,
    0,
    0,
    -92,
    -62,
    0,
    0,
    0,
    0,
    0,
    0,
    -92,
    -62,
    0,
    0,
    0,
    0,
    0,
    0,
    -92,
    -62,
    0,
    0,
    0,
    0,
    0,
    0,
    -52,
    7,
    0,
    0,
    0,
    0,
    0,
    0,
    -52,
    7,
    0,
    0,
    0,
    0,
    0,
    0,
    4,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    81,
    -27,
    116,
    100,
    6,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
  )

class PageAlignUtilsTest {
  enum class PageAlignCheckResult {
    NotElf,
    IsNotAligned64bitElf,
    IsAligned64BitElf,
  }

  private fun checkPageAlign(input: InputStream): PageAlignCheckResult {
    // Keep the magic number check as is, as it's still public
    if (!hasElfMagicNumber(input)) return PageAlignCheckResult.NotElf

    // Reset input stream after magic number check logic (as hasElfMagicNumber consumes)
    input.reset()

    // Helper to simulate the full check on a single raw ELF stream
    // by wrapping it in a temporary Zip entry.
    val content = input.readBytes()
    val result = findElfFile16kAlignmentInfo(ZipBuilder().addFile("test.so", content, AlignedUncompressed).build())

    // If the parser didn't find valid 64-bit ELF structures, hasElfFiles is false.
    // The old test logic treated "Not 64 bit" (readElf returning -1L) as IsNotAligned
    // (because -1L < 16k is true). We reproduce that behavior here.
    if (!result.hasElfFiles) {
      return PageAlignCheckResult.IsNotAligned64bitElf
    }

    val problems = result.alignmentProblems["test.so"] ?: emptyList()

    if (problems.any { it is LoadSectionNotAligned }) return PageAlignCheckResult.IsNotAligned64bitElf

    return PageAlignCheckResult.IsAligned64BitElf
  }

  private fun checkZipPageAlign(content: ByteArray, options: ZipEntryOptions): List<AlignmentProblem> {
    val result = findElfFile16kAlignmentInfo(ZipBuilder().addFile("lib/arm64-v8a/elf.so", content, options).build())
    assertThat(result.hasElfFiles).isTrue()
    return result.alignmentProblems["lib/arm64-v8a/elf.so"] ?: emptyList()
  }

  private fun byteArrayOf(vararg ints: Int): ByteArray = ints.map { it.toByte() }.toByteArray()

  private fun recordingInputStreamOf(file: File) = recordingInputStreamOf(file.readBytes())

  private fun recordingInputStreamOf(bytes: ByteArray) = RecordingInputStream(ByteArrayInputStream(bytes))

  private fun playbackInputStreamOf(content: String) = PlaybackInputStream(content)

  // This repro doesn't work correctly for aac. I'm not sure why, but I think it might have
  // something to do with this warning from readelf:
  //   readelf: Warning: Size of section 10 is larger than the entire file!
  // The ELF itself is not LOAD aligned and our ELF parser reports this.
  //
  // Example found at,
  // cn.rongcloud.sdk:im_libcore:5.1.4
  // https://jcenter.bintray.com/cn/rongcloud/sdk/im_libcore/5.1.4/im_libcore-5.1.4.aar
  // jni/x86_64/libRongIMLib.so
  @Test
  fun `repro Warning Size of section 10 is larger than the entire file!`() {
    val stream =
      playbackInputStreamOf(
        """
        00000000: 7f 45 4c 46 02 01
        00000020: 40 ....... 20 96 01 ......... 40 . 38 . 07 . 40 . 1b . 1a . 06 ... 04 ... 40 ....... 40 ....... 40 ....... 88 01 ...... 88 01 ...... 08 ....... 01 ... 05
        00000098: 65 94 07 ..... 65 94 07 ...... 10 ...... 01 ... 06 ... 80 a5 07 ..... 80 45 12 ..... 80 45 12 ..... 20 dd ...... f8 4b 02 ...... 10 ...... 02 ... 06 ... d8 54 08 ..... d8 f4 12 ..... d8 f4 12 ..... 40 02 ...... 40 02 ...... 08 ....... 50 e5 74 64 04 ... fc 20 07 ..... fc b0 11 ..... fc b0 11 ..... 84 79 ...... 84 79 ...... 04 ....... 51 e5 74 64 06
        00000190: 52 e5 74 64 06 ... 80 a5 07 ..... 80 45 12 ..... 80 45 12 ..... 80 ba ...... 80 ba ...... 40
        00019638: 20 96 01
        00019670: c8 01 ...... c8 01 ...... 88 11
        000196b0: 50 13 ...... 50 13 ...... 31 08
        000196f0: 88 1b ...... 88 1b ....... 05
        00019730: 88 20 ...... 88 20 ...... 76 01
        00019771: 22 ....... 22 ...... 1c
        000197b0: 1c 22 ...... 1c 22 ...... 40
        000197f0: 60 22 ...... 60 22 ...... 18 65 01
        00019830: 78 87 01 ..... 78 87 01 ..... a0 0e
        00019870: 20 a6 01 ..... 20 96 01 ..... d0 09
        000198b1: b0 01 ...... a0 01 ..... 48 59 0b
        000198f0: 80 09 0d ..... 80 f9 0c ..... 64 ab 01
        00019930: e4 b4 0e ..... e4 a4 0e ..... a0 37
        00019970: 88 ec 0e ..... 88 dc 0e ..... 74 c4 02
        000199b0: fc b0 11 ..... fc a0 11 ..... 84 79
        000199f0: 80 45 12 ..... 80 25 12 ..... 20 77
        00019a30: a0 bc 12 ..... a0 9c 12 ..... 10
        00019a70: b0 bc 12 ..... b0 9c 12 ..... 68
        00019ab0: 40 bd 12 ..... 40 9d 12 ..... 98 37
        00019af0: d8 f4 12 ..... d8 54 08 ..... 40 02
        00019b30: 18 f7 12 ..... 18 d7 12 ..... d8 03
        00019b70: f0 fa 12 ..... f0 da 12 ..... f8 04
        00019bb2: 13 ...... e0 12 ..... a0 22
        00019bf0: c0 22 13 ..... a0 02 13 ..... b8 6e 01
        00019c31: 10 ...... a0 02 13 ..... 4d
        00019c71: 10 ...... e0 9c 01 ..... 1c
        00019cb1: 10 ...... fc 9c 01 ..... 12 01
        """
          .trimIndent()
      )
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)!!
    assertThat(problems[0] is LoadSectionNotAligned).isTrue()
  }

  @Test
  fun `direct readElfAlignmentProblems on 16KB aligned ELF`() {
    val stream =
      playbackInputStreamOf(
        """
        00000000: 7f 45 4c 46 02 01
        00000020: 40 ....... 68 c5 .......... 40 . 38 . 09 . 40 . 1c . 1b . 01 ... 04
        00000060: f0 04 ...... f0 04 ....... 40 ...... 01 ... 05
        """
          .trimIndent()
      )
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)
    assertThat(problems).isEmpty()
  }

  // Example found at: ndk/20.1.5948944/toolchains/llvm/prebuilt/linux-x86_64/lib64/libOptRemarks.so
  @Test
  fun `direct readElfAlignmentProblems on unaligned RELRO start`() {
    val stream =
      playbackInputStreamOf(
        """
        00000000: 7f 45 4c 46 02 01
        00000020: 40 ....... e8 39 .......... 40 . 38 . 08 . 40 . 1d . 1b . 06 ... 04 ... 40 ....... 40 ....... 40 ....... c0 01 ...... c0 01 ...... 08 ....... 01 ... 04
        00000098: 3c 05 ...... 3c 05 ....... 10 ...... 01 ... 05 .... 10 ....... 10 ....... 10 ...... 90 01 ...... 90 01 ....... 10 ...... 01 ... 06 .... 20 ....... 20 ....... 20 ...... 50 12 ...... 10 20 ....... 10 ...... 02 ... 06 ... 28 30 ...... 28 30 ...... 28 30 ....... 02 ....... 02 ...... 08 ....... 52 e5 74 64 04 .... 30 ....... 30 ....... 30 ...... 50 02 ....... 10 ...... 01 ....... 50 e5 74 64 04 ... f0 04 ...... f0 04 ...... f0 04 ...... 14 ....... 14 ....... 04 ....... 51 e5 74 64 06
        00003a39: 02 ....... 02 ...... a8
        00003a78: a8 02 ...... a8 02 ...... 0e
        00003ab8: b8 02 ...... b8 02 ...... 38
        00003af8: f0 02 ...... f0 02 ...... 20
        00003b38: 10 03 ...... 10 03 ...... 20
        00003b78: 30 03 ...... 30 03 ...... 16 01
        00003bb8: 48 04 ...... 48 04 ...... 90
        00003bf8: d8 04 ...... d8 04 ...... 18
        00003c38: f0 04 ...... f0 04 ...... 14
        00003c78: 08 05 ...... 08 05 ...... 34
        00003cb9: 10 ....... 10 ...... 43 01
        00003cf8: 44 11 ...... 44 11 ...... 18
        00003d38: 5c 11 ...... 5c 11 ...... 0e
        00003d78: 70 11 ...... 70 11 ...... 20
        00003db9: 20 ....... 20
        00003df9: 20 ....... 20 ...... 08
        00003e38: 08 20 ...... 08 20 ...... 20
        00003e79: 30 ....... 30 ...... 10
        00003eb8: 10 30 ...... 10 30 ...... 10
        00003ef8: 20 30 ...... 20 30 ...... 08
        00003f38: 28 30 ...... 28 30 ....... 02
        00003f78: 28 32 ...... 28 32 ...... 28
        00003fb9: 40 ...... 50 32 ...... 10
        00004000: 50 32 ...... 18
        00004040: 68 32 ...... 83 01
        00004080: f0 33 ...... 18 03
        000040c0: 08 37 ....... 01
        00004100: 08 38 ...... e0 01
        """
          .trimIndent()
      )
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)!!

    assertThat(problems).isNotEmpty()
    val problem = problems.find { it is RelroStartNotAligned }
    assertThat(problem).isNotNull()
    assertThat(problem.toString()).contains("RELRO is not a prefix and its start is not 16 KB aligned")
  }

  // Example found at:
  // ndk/23.2.8568313/toolchains/llvm/prebuilt/linux-x86_64/python3/lib/libpython3.so
  @Test
  fun `direct readElfAlignmentProblems on unaligned RELRO end`() {
    val stream =
      playbackInputStreamOf(
        """
        00000000: 7f 45 4c 46 02 01
        00000020: 40 ....... 20 11 .......... 40 . 38 . 06 . 40 . 18 . 17 . 01 ... 05
        00000060: 44 06 ...... 44 06 ........ 20 ..... 01 ... 06 ... f0 0d ...... f0 0d 20 ..... f0 0d 20 ..... 30 02 ...... 38 02 ........ 20 ..... 02 ... 06 ... 08 0e ...... 08 0e 20 ..... 08 0e 20 ..... d0 01 ...... d0 01 ...... 08 ....... 04 ... 04 ... 90 01 ...... 90 01 ...... 90 01 ...... 24 ....... 24 ....... 04 ....... 51 e5 74 64 06
        00000150: 10 ....... 52 e5 74 64 04 ... f0 0d ...... f0 0d 20 ..... f0 0d 20 ..... 10 02 ...... 10 02 ...... 01
        00001170: 90 01 ...... 90 01 ...... 24
        000011b0: b8 01 ...... b8 01 ...... 38
        000011f0: f0 01 ...... f0 01 ...... 20 01
        00001230: 10 03 ...... 10 03 ...... e4
        00001270: f4 03 ...... f4 03 ...... 18
        000012b0: 10 04 ...... 10 04 ...... 20
        000012f0: 30 04 ...... 30 04 ...... c0
        00001330: f0 04 ...... f0 04 ...... 1a
        00001370: 10 05 ...... 10 05 ...... 10
        000013b0: 20 05 ...... 20 05 ...... 10
        000013f0: 30 05 ...... 30 05 ....... 01
        00001430: 30 06 ...... 30 06 ...... 09
        00001470: 40 06 ...... 40 06 ...... 04
        000014b0: f0 0d 20 ..... f0 0d ...... 08
        000014f0: f8 0d 20 ..... f8 0d ...... 08
        00001531: 0e 20 ...... 0e ...... 08
        00001570: 08 0e 20 ..... 08 0e ...... d0 01
        000015b0: d8 0f 20 ..... d8 0f ...... 28
        000015f1: 10 20 ...... 10 ...... 18
        00001630: 18 10 20 ..... 18 10 ...... 08
        00001670: 20 10 20 ..... 20 10 ...... 08
        000016b8: 20 10 ...... 35
        000016f8: 55 10 ...... c6
        """
          .trimIndent()
      )
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)!!

    assertThat(problems).isNotEmpty()
    val problem = problems.find { it is RelroEndNotAligned }
    assertThat(problem).isNotNull()
    assertThat(problem.toString()).contains("RELRO is not a suffix and its end is not 16 KB aligned")
  }

  // A corrupted ELF file found in a real-world AAR.
  // This file, when passed to aac tool, causes a crash:
  //    length_error was thrown in -fno-exceptions mode with message "vector"
  // Example found at,
  // com.antonkarpenko:ffmpeg-kit-min:1.0.2
  // https://jcenter.bintray.com/com/antonkarpenko/ffmpeg-kit-min/1.0.2/ffmpeg-kit-min-1.0.2.aar
  // jni/x86_64/libswresample.so
  @Test
  fun `corrupted ELF doesn't cause crash 1`() {
    val stream =
      playbackInputStreamOf(
        """
        00000000: 7f 45 4c 46 02 01
        00000020: 40 ....... 18 26 01 ........... 38 . 09 . 40 . 16 ... 06 ............... 40
        0000006a: ef bf bd 01 ...... 08
        000000a6: ef bf bd 6a ....... 40 .............. ef bf bd 6a
        000000d8: bd ef bf bd ...... 20 ef bf bd
        000000fe: 01
        00000110: d0 88 01 ..... d0 88 01 ..... 70 1c ............... 40
        0000014a: ef bf bd 01 ..... ef bf bd 01
        0000016c: 08
        00000180: bd 08 01 ..... d0 88 01 ..... d0 88 01 ............. 30 27
        000001ba: ef bf bd 4f ...... ef bf bd 4f ................ ef bf bd 03
        000001f4: 51 ef bf bd 74 64 06
        0000022e: 04 ... 04 ... 38 02
        00012629: c7 83 04 01 ........... bd 04 2e .. 0f ef bf
        00012668: bd ef bf bd 08 2e .......... 0e .. ef bf bd ef bf
        000126a8: bd ef bf bd 48 63 04 ef ........ bf bd ef bf bd 44 ef bf
        000126e8: bd ef bf bd 68 ef bf bd ........ bd ef bf bd ef bf bd 36
        00012728: 0e .. 48 ef bf bd ef .......... 31 ef bf bd 45 ef
        00012768: ef bf bd ef bf bd 02 ef ........ bf bd ef bf bd 45 31 ef
        000127a8: ef bf bd 42 0f 10 14 ef ........ bf bd ef bf bd 0f 58 ef
        000127e8: 5c ef bf bd ef bf bd 0f ........ ef bf bd ef bf bd ef bf
        00012828: ef bf bd 4c 01 ef bf bd ........ 02 .. eb 99 83 ef bf
        00012868: bf bd ef bf bd 0f 11 ef ........ 13 ef bf bd 0c 01
        000128a8: 79 7c .. 49 ef bf bd ........ 36 49 63 14 04 ef bf bd
        000128e8: 3b ef bf bd ef bf bd ........... 49 ef bf bd ef bf
        00012928: bd ef bf bd ef bf bd ef ........ bd 15 19 1e ef bf bd ef
        00012968: bd ef bf bd ef bf bd 44 ........ ef bf bd ef bf bd 48 63
        000129a8: bd ef bf bd 45 35 .......... ef bf bd .. 48 ef bf
        000129e8: bd 0c .. 48 ef bf bd ........ ef bf bd 45 ef bf bd ef
        00012a28: bf bd 6f 01 .. 45 31 ........ 74 11 ef bf bd 42 0f 5a
        00012a68: ef bf bd ef bf bd . 02 ........ 44 ef bf bd ef bf bd 41
        00012aa8: ef bf bd .. 4c ef bf .......... 48 ef bf bd ef bf
        00012ae8: bf bd 0f ef bf bd 26 0c ........ bd 68 3f .. 31 ef bf
        00012b28: 39 ef bf bd 0f ef bf bd ........ bd 4c 39 ef bf bd 74 11
        00012b68: 4c 01 ef bf bd 48 ef bf ........ ef bf bd ef bf bd 6a 04
        """
          .trimIndent()
      )
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)!!
    assertThat(problems).isEmpty()
  }

  // A corrupted ELF file found in a real-world AAR.
  // This file, when passed to aac tool, causes a crash:
  //    libc++abi: terminating due to uncaught exception of type std::bad_alloc: std::bad_alloc
  // Example found at,
  // com.antonkarpenko:ffmpeg-kit-min:1.0.2
  // https://jcenter.bintray.com/com/antonkarpenko/ffmpeg-kit-min/1.0.2/ffmpeg-kit-min-1.0.2.aar
  // jni/x86_64/libavutil.so
  @Test
  fun `corrupted ELF doesn't cause crash 2`() {
    val stream =
      playbackInputStreamOf(
        """
        00000000: 7f 45 4c 46 02 01
        00000020: 40 ....... 20 08 08 ........... 38 . 0a . 40 . 19 ... 06 ............... 40
        00000068: 30 02 ...... 08 ....... 01
        000000a0: 64 10 03 ...... 40 ...... 01 ............... 70 50 03
        000000dc: ef bf bd ef bf bd 03 ...... 40 .............. 20 ef bf bd 06
        00000112: ef bf bd 07 01 ..... ef bf bd 10 01
        00000132: 06 ... ef bf
        0000014c: ef bf bd ef bf bd 08 ..... 48
        0000016b: 40
        00000184: d0 81 08 ..... d0 81 08
        000001a4: 08
        000001b8: bf bd 06 ..... 20 7f 07 ..... 20 7f 07 ............... ef bf bd 10 01
        000001f0: 74 64 04 ... ef bf bd 0a 02 ..... ef bf bd 0a ................ ef bf bd 22 ................ 04 ....... 51 ef bf bd 74 64 06
        0000026a: 04 ... 04
        00080830: 0f 29 ef bf bd 24 . 01 ........ 19 ef bf bd ef bf bd
        00080871: 66 0f 28 1d ef bf bd ........ 0f 10 ef bf bd 66 0f 29
        000808b0: bd ef bf bd ef bf bd 0f ........ bd . 66 0f 29 ef bf bd
        000808f0: 66 0f 28 ef bf bd 66 0f ........ bf bd 66 0f 70 ef bf bd
        00080930: bf bd 24 ef bf bd .......... bf bd ... 66 0f 70
        00080973: 49 ef bf bd ef ........ 10 4d ef bf bd 7e 08 49
        000809b0: ef bf bd ... 66 0f ........... 44 3b 54 24 0c
        000809f0: bf bd 48 0f ef bf bd ef ........ 04 ef bf bd 48 0f ef bf
        00080a30: ef bf bd 0f 59 ef bf bd ........ bf bd 44 0f 59 ef bf bd
        00080a70: bd ef bf bd 0f 58 ef bf ........ bd ef bf bd 18 01
        00080ab0: 44 0f 28 ef bf bd 24 10 ........ bf bd 24 20 01 .. 66
        00080af0: 28 ef bf bd 66 41 0f ef ........ 0f 58 ef bf bd 66 0f 28
        00080b30: 66 45 0f ef bf bd ef bf ........ ef bf bd 01 66 0f 28 ef
        00080b70: bd 66 41 0f 28 ef bf bd ........ 41 0f 58 ef bf bd 66 41
        00080bb0: bd 66 0f 5c ef bf bd 66 ........ 59 ef bf bd 66 45 0f 28
        00080bf0: bd 02 66 44 0f 59 ef bf ........ 66 44 0f 28 ef bf bd 24
        00080c30: bf bd 24 ef bf bd .......... 41 0f 59 ef bf bd 66 0f
        00080c70: 0f 28 ef bf bd 24 ef bf ........ ef bf bd 66 0f 58 ef bf
        00080cb0: 44 0f 58 ef bf bd 66 0f ........ ef bf bd 66 44 0f 58 ef
        00080cf0: 58 ef bf bd 66 41 0f 11 ........ bd ef bf bd 0f 10 ef bf
        00080d30: ef bf bd 66 44 0f 59 7c ........ bd 66 44 0f 59 ef bf bd
        00080d70: bd 66 41 0f 5c ef bf bd ........ 41 0f 28 ef bf bd ef bf
        00080db0: bf bd 66 41 0f 11 24 03 ........ ef bf bd 74 24 40 66 0f
        00080df0: ef bf bd ef bf bd 04 49 ........ ef bf bd ef bf bd 1c 41
        00080e30: 24 04 48 ef bf bd 44 24 ........ bd 49 ef bf bd ef bf bd
        """
          .trimIndent()
      )
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)!!
    assertThat(problems).isEmpty()
  }

  // This ELF demonstrates a valid edge case where the PT_GNU_RELRO segment extends
  // beyond the declared memory size (memsz) of its containing PT_LOAD segment.
  //
  // Specifically:
  //   - The RW PT_LOAD segment ends at 0x39E8 (not page aligned).
  //   - The PT_GNU_RELRO segment extends to 0x4000 (page aligned).
  //
  // This RELRO is valid because the OS allocates the full page up to 0x4000. The linker
  // extended the RELRO segment into the zero-filled padding to satisfy the requirement
  // that memory protection changes (mprotect) occur at page boundaries.
  //
  // We expect:
  //   1. LoadSectionNotAligned: True (The binary is 4KB aligned, not 16KB).
  //   2. RelroStart/EndNotAligned: False (The RELRO segment itself is correctly positioned).
  //
  // Example found at:
  // io.github.tans5:tmediaplayer:1.4.1
  // https://jcenter.bintray.com/io/github/tans5/tmediaplayer/1.4.1/tmediaplayer-1.4.1.aar
  // jni/x86_64/libtmediaframeloader.so
  @Test
  fun `relro extends past load end into padding`() {
    val stream =
      playbackInputStreamOf(
        """
        00000000: 7f 45 4c 46 02 01
        00000020: 40 ....... 70 2b .......... 40 . 38 . 09 . 40 . 15 . 14 . 06 ... 04 ... 40 ....... 40 ....... 40 ....... f8 01 ...... f8 01 ...... 08 ....... 01 ... 05
        00000098: 30 26 ...... 30 26 ....... 10 ...... 01 ... 06 ... 30 26 ...... 30 36 ...... 30 36 ...... b8 03 ...... b8 03 ....... 10 ...... 02 ... 06 ... 48 26 ...... 48 36 ...... 48 36 ...... 50 02 ...... 50 02 ...... 08 ....... 52 e5 74 64 04 ... 30 26 ...... 30 36 ...... 30 36 ...... b8 03 ...... d0 09 ...... 01 ....... 50 e5 74 64 04 ... 9c 16 ...... 9c 16 ...... 9c 16 ...... ac ....... ac ....... 04 ....... 51 e5 74 64 06
        000001c8: 04 ... 04 ... 38 02 ...... 38 02 ...... 38 02 ...... 98 ....... 98 ....... 02 ....... 04 ... 04 ... d0 02 ...... d0 02 ...... d0 02 ...... 24 ....... 24 ....... 04
        00002bc0: 38 02 ...... 38 02 ...... 98
        00002c00: d0 02 ...... d0 02 ...... 24
        00002c40: f8 02 ...... f8 02 ...... 98 04
        00002c80: 90 07 ...... 90 07 ...... 62
        00002cc0: f4 07 ...... f4 07 ...... a0
        00002d00: 98 08 ...... 98 08 ...... 74
        00002d40: 0c 09 ...... 0c 09 ...... 01 07
        00002d80: 10 10 ...... 10 10 ...... 48
        00002dc0: 58 10 ...... 58 10 ...... a8 03
        00002e01: 14 ....... 14 ...... 9c 02
        00002e40: 9c 16 ...... 9c 16 ...... ac
        00002e80: 48 17 ...... 48 17 ...... cc 02
        00002ec0: 20 1a ...... 20 1a ...... 90 09
        00002f00: b0 23 ...... b0 23 ...... 80 02
        00002f40: 30 36 ...... 30 26 ...... 08
        00002f80: 38 36 ...... 38 26 ...... 10
        00002fc0: 48 36 ...... 48 26 ...... 50 02
        00003000: 98 38 ...... 98 28 ...... 50 01
        00003048: e8 29 ...... b1
        00003088: 99 2a ...... d6
        """
          .trimIndent()
      )
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)!!

    assertThat(problems).isNotEmpty()
    assertThat(problems.any { it is LoadSectionNotAligned }).isTrue()
    assertThat(problems.any { it is RelroStartNotAligned }).isFalse()
    assertThat(problems.any { it is RelroEndNotAligned }).isFalse()
  }

  // Example found at:
  // io.agora.rtc:full-sdk:3.1.2 (and others)
  // jni/arm64-v8a/libagora_segmentation_extension.so
  //
  // This ELF is a standard failure case.
  // The RELRO segment is misaligned (ends at a non-page boundary) and does NOT
  // qualify for the "Identity Exemption" (it is likely a partial suffix, not
  // covering the entire LOAD segment).
  //
  // We expect:
  //   1. LoadSectionNotAligned: True (Alignment is 0x1000, need 0x4000).
  //   2. RelroEndNotAligned: True (Real failure, rejected by aac).
  @Test
  fun `misaligned relro suffix fails`() {
    val stream =
      playbackInputStreamOf(
        """
        00000000: 7f 45 4c 46 02 01
        00000020: 40 ....... a0 22 .......... 40 . 38 . 0b . 40 . 17 . 16 . 06 ... 04 ... 40 ....... 40 ....... 40 ....... a0 02 ...... a0 02 ...... 08 ....... 03 ... 04 ... e0 02 ...... e0 02 ...... e0 02 ...... 13 ....... 13 ....... 01 ....... 01 ... 04
        000000d0: f4 05 ...... f4 05 ....... 10 ...... 01 ... 05 .... 10 ....... 10 ....... 10 ...... 90 ....... 90 ........ 10 ...... 01 ... 06 .... 20 ....... 20 ....... 20 ...... c0 01 ...... c0 01 ....... 10 ...... 02 ... 06 ... 18 20 ...... 18 20 ...... 18 20 ...... 80 01 ...... 80 01 ...... 08 ....... 52 e5 74 64 04 .... 20 ....... 20 ....... 20 ...... c0 01 ....... 10 ...... 01 ....... 50 e5 74 64 04 ... 28 05 ...... 28 05 ...... 28 05 ...... 34 ....... 34 ....... 04 ....... 51 e5 74 64 06
        00000238: 04 ... 04 ... f4 02 ...... f4 02 ...... f4 02 ...... 98 ....... 98 ....... 02 ....... 04 ... 04 ... 8c 03 ...... 8c 03 ...... 8c 03 ...... 24 ....... 24 ....... 04
        000022f0: e0 02 ...... e0 02 ...... 13
        00002330: f4 02 ...... f4 02 ...... 98
        00002370: 8c 03 ...... 8c 03 ...... 24
        000023b0: b0 03 ...... b0 03 ...... 48
        000023f0: f8 03 ...... f8 03 ...... 06
        00002431: 04 ....... 04 ...... 20
        00002470: 20 04 ...... 20 04 ...... 1c
        000024b0: 3c 04 ...... 3c 04 ...... 20
        000024f0: 5c 04 ...... 5c 04 ...... 4d
        00002530: b0 04 ...... b0 04 ...... 48
        00002570: f8 04 ...... f8 04 ...... 30
        000025b0: 28 05 ...... 28 05 ...... 34
        000025f0: 60 05 ...... 60 05 ...... 94
        00002631: 10 ....... 10 ...... 58
        00002670: 60 10 ...... 60 10 ...... 30
        000026b1: 20 ....... 20 ...... 08
        000026f0: 08 20 ...... 08 20 ...... 10
        00002730: 18 20 ...... 18 20 ...... 80 01
        00002770: 98 21 ...... 98 21 ...... 28
        000027b0: c0 21 ...... c0 21
        000027f1: 30 ...... c0 21
        00002838: c0 21 ...... de
        """
          .trimIndent()
      )
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)!!

    assertThat(problems).isNotEmpty()

    // Both checks should fail
    assertThat(problems.any { it is LoadSectionNotAligned }).isTrue()
    assertThat(problems.any { it is RelroEndNotAligned }).isTrue()
    assertThat(problems.any { it is RelroStartNotAligned }).isFalse()
  }

  @Test
  fun `direct readElfAlignmentProblems on garbage data`() {
    val problems = readElfAlignmentProblems(ByteArrayInputStream(byteArrayOf(0, 1, 2, 3)))
    assertThat(problems).isNull()
  }

  @Test
  fun `APK with no ELF files`() {
    val apk = findElfFile16kAlignmentInfo(ZipBuilder().build())
    assertThat(apk.hasElfFiles).isFalse()
  }

  @Test
  fun `so file that is 16 KB aligned`() {
    assertThat(checkPageAlign(ByteArrayInputStream(SO_FILE_16K_ALIGNED))).isEqualTo(PageAlignCheckResult.IsAligned64BitElf)
  }

  @Test
  fun `so file with 16KB and 4KB LOAD sections`() {
    val stream = this::class.java.classLoader.getResourceAsStream("testData/pagealign/lib-16kb-4kb.so")!!
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)!!

    assertThat(problems).hasSize(1)
    val problem = problems[0]
    assertThat(problem).isInstanceOf(LoadSectionNotAligned::class.java)
    assertThat(problem.toString()).contains("4 KB LOAD section alignment, but 16 KB is required")
  }

  @Test
  fun `Repro 425337033 - corrupted APK`() {
    val zipBytes = ZipBuilder().addFile("lib/arm64-v8a/elf.so", SO_FILE_16K_ALIGNED, AlignedUncompressed).toByteArray()
    // Set each byte to zero and try findElfFile16kAlignmentInfo
    // Before the fix, this would throw ZipException or IOException depending on where in the
    // zip body the zero was injected.
    for (i in zipBytes.indices) {
      val save = zipBytes[i]
      zipBytes[i] = 0
      findElfFile16kAlignmentInfo(ZipArchiveInputStream(ByteArrayInputStream(zipBytes)))
      zipBytes[i] = save
    }
  }

  @Test
  fun `APK with so file that is 16 KB aligned LOAD sections`() {
    // .so file is compressed and not 16k aligned
    assertThat(checkZipPageAlign(SO_FILE_16K_ALIGNED, UnalignedCompressed)).isEmpty()

    // .so file is uncompressed and not 16k aligned
    assertThat(getProblemClasses(checkZipPageAlign(SO_FILE_16K_ALIGNED, UnalignedUncompressed))).containsExactly(ZipEntryNotAligned::class)

    // .so file is uncompressed and not 16k aligned
    assertThat(checkZipPageAlign(SO_FILE_16K_ALIGNED, AlignedCompressed)).isEmpty()

    // .so file is uncompressed and not 16k aligned
    assertThat(checkZipPageAlign(SO_FILE_16K_ALIGNED, AlignedUncompressed)).isEmpty()
  }

  @Test
  fun `APK with so file that is not 16 KB aligned LOAD sections`() {
    // .so file is compressed and not 16k aligned
    assertThat(getProblemClasses(checkZipPageAlign(SO_FILE_NOT_16K_ALIGNED, UnalignedCompressed)))
      .containsExactly(LoadSectionNotAligned::class)

    // .so file is uncompressed and not 16k aligned
    assertThat(getProblemClasses(checkZipPageAlign(SO_FILE_NOT_16K_ALIGNED, UnalignedUncompressed)))
      .containsExactly(LoadSectionNotAligned::class, ZipEntryNotAligned::class)

    // .so file is uncompressed and not 16k aligned
    assertThat(getProblemClasses(checkZipPageAlign(SO_FILE_NOT_16K_ALIGNED, AlignedCompressed)))
      .containsExactly(LoadSectionNotAligned::class)

    // .so file is uncompressed and not 16k aligned
    assertThat(getProblemClasses(checkZipPageAlign(SO_FILE_NOT_16K_ALIGNED, AlignedUncompressed)))
      .containsExactly(LoadSectionNotAligned::class)
  }

  @Test
  fun `so that is not 16 KB aligned`() {
    val input =
      playbackInputStreamOf(
        """
        00000000: 7f 45 4c 46 7f 45 4c 46 02 01 01 ......... 03 . 3e . 01 ........... 40 ....... 68 35 .......... 40 . 38 . 09 . 40 . 1c . 1b . 01 ... 04
        00000064: f0 04 ...... f0 04 ....... 10
        """
          .trimIndent()
      )
    assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
  }

  // First bytes of
  // 21.4.7075529/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/arm-linux-androideabi/29/libc++.so
  @Test
  fun `so file is actually a linker script`() {
    val input = ByteArrayInputStream(byteArrayOf(73, 78, 80, 85, 84, 40, 45, 108, 99, 43, 43))
    assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.NotElf)
  }

  @Test
  fun `not ELF file`() {
    assertThat(checkPageAlign(ByteArrayInputStream(byteArrayOf()))).isEqualTo(PageAlignCheckResult.NotElf)
    assertThat(checkPageAlign(ByteArrayInputStream(byteArrayOf(0)))).isEqualTo(PageAlignCheckResult.NotElf)
    assertThat(checkPageAlign(ByteArrayInputStream(byteArrayOf(0, 0)))).isEqualTo(PageAlignCheckResult.NotElf)
    assertThat(checkPageAlign(ByteArrayInputStream(byteArrayOf(0, 0, 0)))).isEqualTo(PageAlignCheckResult.NotElf)
    assertThat(checkPageAlign(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))).isEqualTo(PageAlignCheckResult.NotElf)
    assertThat(checkPageAlign(ByteArrayInputStream(byteArrayOf(127, 0, 0, 0)))).isEqualTo(PageAlignCheckResult.NotElf)
    assertThat(checkPageAlign(ByteArrayInputStream(byteArrayOf(127, 69, 0, 0)))).isEqualTo(PageAlignCheckResult.NotElf)
    assertThat(checkPageAlign(ByteArrayInputStream(byteArrayOf(127, 69, 76, 0)))).isEqualTo(PageAlignCheckResult.NotElf)
  }

  // First bytes of build-tools/30.0.3/renderscript/lib/packaged/x86/librsjni.so
  @Test
  fun `so file is 32 bit`() {
    val stream = playbackInputStreamOf("00000000: 7f 45 4c 46 01 01")
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)
    assertThat(problems).isNull()
  }

  // Synthetic big-endian
  @Test
  fun `so file is big-endian`() {
    val input = ByteArrayInputStream(byteArrayOf(127, 69, 76, 70, 1, 2, 1, 0, 0, 0, 0, 0))
    assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
  }

  // Synthetic unknown endian-ness
  @Test
  fun `so file is unknown-endian`() {
    val input = ByteArrayInputStream(byteArrayOf(127, 69, 76, 70, 1, 0, 1, 0, 0, 0, 0, 0))
    assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
  }

  // Synthetic wrong ELF version
  @Test
  fun `so file has wrong ELF version`() {
    val input = ByteArrayInputStream(byteArrayOf(127, 69, 76, 70, 1, 1, 0, 0, 0, 0, 0, 0))
    assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
  }

  // Synthetic wrong bitness
  @Test
  fun `so file has wrong bitness`() {
    val input = ByteArrayInputStream(byteArrayOf(127, 69, 76, 70, 3, 1, 1, 0, 0, 0, 0, 0))
    assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
  }

  // Required bytes from build-tools/26.0.3/lib64/libc++.so
  @Test
  fun `program header flags are in a different position for 64-bit`() {
    val stream =
      playbackInputStreamOf(
        """
        00000000: 7f 45 4c 46 02 01
        00000020: 40 ....... f0 e1 10 ......... 40 . 38 . 08 . 40 . 22 . 1f . 06 ... 04 ... 40 ....... 40 ....... 40 ....... c0 01 ...... c0 01 ...... 08 ....... 01 ... 05
        00000098: 60 59 10 ..... 60 59 10 ...... 10 ...... 01 ... 06 ... f0 66 10 ..... f0 76 10 ..... f0 76 10 ..... 58 79 ...... b8 ae ....... 10 ...... 02 ... 06 ... 20 ba 10 ..... 20 ca 10 ..... 20 ca 10 ..... 60 02 ...... 60 02 ...... 08 ....... 50 e5 74 64 04 ... 4c 14 10 ..... 4c 14 10 ..... 4c 14 10 ..... 14 45 ...... 14 45 ...... 04 ....... 51 e5 74 64 06
        00000190: 07 ... 04 ... f0 66 10 ..... f0 76 10 ..... f0 76 10 ............. 10 ....... 08 ....... 52 e5 74 64 06 ... f0 66 10 ..... f0 76 10 ..... f0 76 10 ..... 10 79 ...... 10 79 ...... 10
        0010e241: 02 ....... 02 ...... 78 de
        0010e280: 78 e0 ...... 78 e0 ...... 79 a2 01
        0010e2c0: f8 82 02 ..... f8 82 02 ..... 04 4b
        0010e300: fc cd 02 ..... fc cd 02 ..... 8a 12
        0010e340: 88 e0 02 ..... 88 e0 02 ..... 1c
        0010e380: a4 e0 02 ..... a4 e0 02 ..... f0
        0010e3c0: 98 e1 02 ..... 98 e1 02 ..... 38 d3
        0010e400: d0 b4 03 ..... d0 b4 03 ..... 38 58
        0010e440: 08 0d 04 ..... 08 0d 04 ..... 18
        0010e480: 20 0d 04 ..... 20 0d 04 ..... e0 3a
        0010e4c1: 48 04 ...... 48 04 ..... ec 94 09
        0010e500: ec dc 0d ..... ec dc 0d ..... 0e
        0010e541: dd 0d ...... dd 0d ..... 87 58
        0010e580: 88 35 0e ..... 88 35 0e ...... b0
        0010e5c0: 88 e5 0e ..... 88 e5 0e ..... c4 2e 01
        0010e600: 4c 14 10 ..... 4c 14 10 ..... 14 45
        0010e640: f0 76 10 ..... f0 66 10 ..... 10
        0010e680: f0 76 10 ..... f0 66 10 ..... 10
        0010e6c1: 77 10 ...... 67 10 ..... 10
        0010e700: 10 77 10 ..... 10 67 10 ..... 08
        0010e740: 20 77 10 ..... 20 67 10 ..... f8 52
        0010e780: 18 ca 10 ..... 18 ba 10 ..... 08
        0010e7c0: 20 ca 10 ..... 20 ba 10 ..... 60 02
        0010e800: 80 cc 10 ..... 80 bc 10 ..... f8 05
        0010e840: 78 d2 10 ..... 78 c2 10 ..... 80 1d
        0010e881: f0 10 ...... e0 10 ..... 44
        0010e8c0: 48 f0 10 ..... 48 e0 10
        0010e900: 50 f0 10 ..... 48 e0 10 ..... 58 35
        0010e948: 48 e0 10 ..... 4d
        0010e988: 98 e0 10 ..... 1c
        0010e9c8: b4 e0 10 ..... 3c 01
        0010ea08: 70 ea 10 ..... e0 e5
        0010ea48: 50 d0 11 ..... 65 a8 01
        """
          .trimIndent()
      )
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)!!
    assertThat(problems[0] is LoadSectionNotAligned).isTrue()
  }

  @Test
  fun `RELRO before first LOAD segment is not ignored`() {
    val stream =
      playbackInputStreamOf(
        """
        00000000: 7f 45 4c 46 02 01
        00000020: 40
        00000036: 38 . 02 . 40 ..... 52 e5 74 64 .............. 01
        00000069: 10 ...... 01 ....... 01
        0000008a: 02
        000000a1: 10 ........ 01
        """
          .trimIndent()
      )
    assertThat(hasElfMagicNumber(stream)).isTrue()
    val problems = readElfAlignmentProblems(stream)!!
    assertThat(problems).hasSize(1)
    assertThat(problems[0] is RelroStartNotAligned).isTrue()
  }

  // Helper to make assertions on the list of problems easier
  private fun getProblemClasses(problems: List<AlignmentProblem>): List<KClass<out AlignmentProblem>> {
    return problems.map { it::class }
  }

  class RecordingInputStream(private val delegate: InputStream) : InputStream() {
    private var globalPosition: Long = 0
    private val recordedBytes = TreeMap<Long, Byte>()

    override fun read(): Int {
      val b = delegate.read()
      if (b != -1) {
        record(globalPosition, b.toByte())
        globalPosition++
      }
      return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      val readLen = delegate.read(b, off, len)
      if (readLen > 0) {
        for (i in 0 until readLen) {
          record(globalPosition + i, b[off + i])
        }
        globalPosition += readLen
      }
      return readLen
    }

    override fun skip(n: Long): Long {
      if (n <= 8) {
        repeat(n.toInt()) {
          val b = delegate.read()
          if (b != -1) {
            record(globalPosition, 0)
            globalPosition++
          }
        }
        return n
      }
      val skipped = delegate.skip(n)
      if (skipped > 0) globalPosition += skipped
      return skipped
    }

    private fun record(pos: Long, byte: Byte) {
      if (byte != 0.toByte()) {
        recordedBytes[pos] = byte
      }
    }

    fun dump(): String {
      if (recordedBytes.isEmpty()) return ""

      val sb = StringBuilder()
      val iterator = recordedBytes.iterator()

      if (!iterator.hasNext()) return ""

      var entry = iterator.next()
      var currentBlockStart = entry.key
      var lastAddress = entry.key

      val currentLineTokens = ArrayList<String>()
      currentLineTokens.add("%02x".format(entry.value))

      // Threshold: If gap <= 16, use dots "....". If > 16, new line.
      val gapThreshold = 16

      while (iterator.hasNext()) {
        entry = iterator.next()
        val currentAddress = entry.key
        when (val gapSize = currentAddress - lastAddress - 1) {
          0L -> {
            currentLineTokens.add("%02x".format(entry.value))
            lastAddress = currentAddress
          }
          in 1..gapThreshold -> {
            // Collapse the zeroes into a single token of dots
            currentLineTokens.add(".".repeat(gapSize.toInt()))

            currentLineTokens.add("%02x".format(entry.value))
            lastAddress = currentAddress
          }
          else -> {
            // Gap too large, start new block
            flushBlock(sb, currentBlockStart, currentLineTokens)

            currentBlockStart = currentAddress
            lastAddress = currentAddress
            currentLineTokens.clear()
            currentLineTokens.add("%02x".format(entry.value))
          }
        }
      }

      flushBlock(sb, currentBlockStart, currentLineTokens)
      return sb.toString()
    }

    private fun flushBlock(sb: StringBuilder, startAddr: Long, tokens: List<String>) {
      sb.append("%08x: ".format(startAddr))
      sb.append(tokens.joinToString(" "))
      sb.append("\n")
    }

    // Standard delegations
    override fun available() = delegate.available()

    override fun close() = delegate.close()

    override fun mark(readlimit: Int) = delegate.mark(readlimit)

    override fun reset() = delegate.reset()

    override fun markSupported() = delegate.markSupported()
  }

  class PlaybackInputStream(dump: String) : InputStream() {
    private val sparseData = HashMap<Long, Byte>()
    private var size: Long = 0
    private var position: Long = 0

    init {
      parseDump(dump)
    }

    private fun parseDump(dump: String) {
      dump.lineSequence().forEach { line ->
        if (line.isBlank()) return@forEach

        val parts = line.split(':', limit = 2)
        if (parts.size < 2) return@forEach

        val baseAddress = parts[0].trim().toLongOrNull(16) ?: return@forEach
        val dataTokens = parts[1].trim().split(Regex("\\s+"))

        var currentOffset = 0L

        for (token in dataTokens) {
          if (token.isEmpty()) continue

          // Check if token is a run of dots (e.g. "....") representing implicit zeros
          if (token.all { it == '.' }) {
            currentOffset += token.length
          } else {
            // Standard Hex parsing
            val byteVal = token.toInt(16).toByte()
            val absoluteAddress = baseAddress + currentOffset

            sparseData[absoluteAddress] = byteVal
            size = max(size, absoluteAddress + 1)

            currentOffset++
          }
        }
      }
    }

    override fun read(): Int {
      if (position >= size) return -1
      val byte = sparseData[position]?.toInt() ?: 0
      position++
      return byte and 0xFF
    }

    override fun reset() {
      position = 0
    }
  }

  class ZipBuilder {

    private val outputStream = ByteArrayOutputStream()
    private val zipOutputStream = ZipArchiveOutputStream(outputStream)

    // Use an enum to make the tests read more transparently.
    enum class ZipEntryOptions {

      UnalignedCompressed,
      UnalignedUncompressed,
      AlignedCompressed,
      AlignedUncompressed,
    }

    fun addFile(filename: String, contents: ByteArray, options: ZipEntryOptions): ZipBuilder {
      val entry = ZipArchiveEntry(filename)
      val compressed = options == UnalignedCompressed || options == AlignedCompressed
      val aligned = options == AlignedCompressed || options == AlignedUncompressed

      if (!compressed) {
        entry.method = ZipEntry.STORED
        val crc32 = CRC32()
        crc32.update(contents, 0, contents.size)
        entry.crc = crc32.value
        entry.size = contents.size.toLong()
        entry.compressedSize = contents.size.toLong()
      }
      if (aligned) {
        entry.setAlignment(PAGE_ALIGNMENT_16K.toInt())
      }
      zipOutputStream.putArchiveEntry(entry)
      zipOutputStream.write(contents)
      zipOutputStream.closeArchiveEntry()
      return this
    }

    fun toByteArray(): ByteArray {
      zipOutputStream.close() // Close the zipOutputStream to finalize the ZIP file
      return outputStream.toByteArray()
    }

    fun build(): ZipArchiveInputStream {
      return ZipArchiveInputStream(ByteArrayInputStream(toByteArray()))
    }
  }
}
