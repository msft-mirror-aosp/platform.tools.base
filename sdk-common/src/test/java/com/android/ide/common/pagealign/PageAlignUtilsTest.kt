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

import com.android.ide.common.pagealign.PageAlignUtilsTest.ZipBuilder.ZipEntryOptions.AlignedCompressed
import com.android.ide.common.pagealign.PageAlignUtilsTest.ZipBuilder.ZipEntryOptions.AlignedUncompressed
import com.android.ide.common.pagealign.PageAlignUtilsTest.ZipBuilder.ZipEntryOptions.UnalignedUncompressed
import org.junit.Test
import java.io.ByteArrayInputStream
import com.google.common.truth.Truth.assertThat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import com.android.ide.common.pagealign.AlignmentProblems.ElfNot16kAlignedInZip
import com.android.ide.common.pagealign.AlignmentProblems.ElfLoadSectionsNot16kAligned
import com.android.ide.common.pagealign.PageAlignUtilsTest.ZipBuilder.ZipEntryOptions
import com.android.ide.common.pagealign.PageAlignUtilsTest.ZipBuilder.ZipEntryOptions.UnalignedCompressed
import java.io.File
import java.util.zip.CRC32

// First bytes of ndk/28.0.12433566/toolchains/llvm/prebuilt/linux-x86_64/lib/aarch64-unknown-linux-musl/libc++abi.so
val SO_FILE_16K_ALIGNED = byteArrayOf(
    127, 69, 76, 70, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, -73, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    64, 0, 0, 0, 0, 0, 0, 0, 56, 92, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 64, 0, 56, 0, 10, 0, 64, 0, 35, 0, 33, 0,
    6, 0, 0, 0, 4, 0, 0, 0, 64, 0, 0, 0, 0, 0, 0, 0, 64, 0, 0, 0, 0, 0, 0, 0, 64, 0, 0, 0, 0, 0, 0, 0,
    48, 2, 0, 0, 0, 0, 0, 0, 48, 2, 0, 0, 0, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 4, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -4, -7, 1, 0, 0, 0, 0, 0,
    -4, -7, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 5, 0, 0, 0, -4, -7, 1, 0, 0, 0, 0, 0,
    -4, -7, 2, 0, 0, 0, 0, 0, -4, -7, 2, 0, 0, 0, 0, 0, -124, -89, 2, 0, 0, 0, 0, 0, -124, -89, 2, 0, 0, 0, 0, 0,
    0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 6, 0, 0, 0, -128, -95, 4, 0, 0, 0, 0, 0, -128, -95, 6, 0, 0, 0, 0, 0,
    -128, -95, 6, 0, 0, 0, 0, 0, 56, 61, 0, 0, 0, 0, 0, 0, -128, 62, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0,
    1, 0, 0, 0, 6, 0, 0, 0, -72, -34, 4, 0, 0, 0, 0, 0, -72, -34, 7, 0, 0, 0, 0, 0, -72, -34, 7, 0, 0, 0, 0, 0,
    104, 3, 0, 0, 0, 0, 0, 0, -88, 14, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 7, 0, 0, 0, 4, 0, 0, 0,
    -128, -95, 4, 0, 0, 0, 0, 0, -128, -95, 5, 0, 0, 0, 0, 0, -128, -95, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    16, 0, 0, 0, 0, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 6, 0, 0, 0, -8, -37, 4, 0, 0, 0, 0, 0,
    -8, -37, 6, 0, 0, 0, 0, 0, -8, -37, 6, 0, 0, 0, 0, 0, -112, 1, 0, 0, 0, 0, 0, 0, -112, 1, 0, 0, 0, 0, 0, 0,
    8, 0, 0, 0, 0, 0, 0, 0, 82, -27, 116, 100, 4, 0, 0, 0, -128, -95, 4, 0, 0, 0, 0, 0, -128, -95, 6, 0, 0, 0, 0, 0,
    -128, -95, 6, 0, 0, 0, 0, 0, 56, 61, 0, 0, 0, 0, 0, 0, -128, 62, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0,
    80, -27, 116, 100, 4, 0, 0, 0, 92, 106, 1, 0, 0, 0, 0, 0, 92, 106, 1, 0, 0, 0, 0, 0, 92, 106, 1, 0, 0, 0, 0, 0,
    116, 17, 0, 0, 0, 0, 0, 0, 116, 17, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 81, -27, 116, 100, 6, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 32, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

val SO_FILE_NOT_16K_ALIGNED = byteArrayOf(
    127, 69, 76, 70, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 62, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    64, 0, 0, 0, 0, 0, 0, 0, 120, -34, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 64, 0, 56, 0, 6, 0, 64, 0, 33, 0, 31, 0,
    6, 0, 0, 0, 4, 0, 0, 0, 64, 0, 0, 0, 0, 0, 0, 0, 64, 0, 0, 0, 0, 0, 0, 0, 64, 0, 0, 0, 0, 0, 0, 0,
    80, 1, 0, 0, 0, 0, 0, 0, 80, 1, 0, 0, 0, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 5, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 112, -54, 0, 0, 0, 0, 0, 0,
    112, -54, 0, 0, 0, 0, 0, 0, 0, 16, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, -48, 0, 0, 0, 0, 0, 0,
    0, -48, 0, 0, 0, 0, 0, 0, 0, -48, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, -55, 14, 0, 0, 0, 0, 0, 0,
    0, 16, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 6, 0, 0, 0, 0, -48, 0, 0, 0, 0, 0, 0, 0, -48, 0, 0, 0, 0, 0, 0,
    0, -48, 0, 0, 0, 0, 0, 0, 96, 2, 0, 0, 0, 0, 0, 0, 96, 2, 0, 0, 0, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0,
    80, -27, 116, 100, 4, 0, 0, 0, -92, -62, 0, 0, 0, 0, 0, 0, -92, -62, 0, 0, 0, 0, 0, 0, -92, -62, 0, 0, 0, 0, 0, 0,
    -52, 7, 0, 0, 0, 0, 0, 0, -52, 7, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 81, -27, 116, 100, 6, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

class PageAlignUtilsTest {
    enum class PageAlignCheckResult {
        NotElf,
        IsNotAligned64bitElf,
        IsAligned64BitElf
    }

    private fun checkPageAlign(input : InputStream) : PageAlignCheckResult{
        if (!hasElfMagicNumber(input))
            return PageAlignCheckResult.NotElf
        if (readElfMinimumLoadSectionAlignment(input) < PAGE_ALIGNMENT_16K)
            return PageAlignCheckResult.IsNotAligned64bitElf
        return PageAlignCheckResult.IsAligned64BitElf
    }

    private fun checkZipPageAlign(content : ByteArray, options : ZipEntryOptions) : Set<AlignmentProblems> {
        val result = findElfFile16kAlignmentInfo(ZipBuilder()
                                                       .addFile("lib/arm64-v8a/elf.so", content, options)
                                                       .build())
        assertThat(result.hasElfFiles).isTrue()
        return result.alignmentProblems["lib/arm64-v8a/elf.so"] ?: setOf()
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
        val lib = this::class.java.classLoader.getResourceAsStream("testData/pagealign/lib-16kb-4kb.so")!!
        check(hasElfMagicNumber(lib))
        assertThat(readElfMinimumLoadSectionAlignment(lib))
            .named("Expect 4KB alignment")
            .isEqualTo(0x1000)
    }

    @Test
    fun `Repro 425337033 - corrupted APK`() {
        val zipBytes = ZipBuilder()
                        .addFile("lib/arm64-v8a/elf.so", SO_FILE_16K_ALIGNED, AlignedUncompressed)
                        .toByteArray()
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
        assertThat(checkZipPageAlign(SO_FILE_16K_ALIGNED, UnalignedCompressed))
            .isEmpty()

        // .so file is uncompressed and not 16k aligned
        assertThat(checkZipPageAlign(SO_FILE_16K_ALIGNED, UnalignedUncompressed))
            .isEqualTo(setOf(ElfNot16kAlignedInZip))

        // .so file is uncompressed and not 16k aligned
        assertThat(checkZipPageAlign(SO_FILE_16K_ALIGNED, AlignedCompressed))
            .isEmpty()

        // .so file is uncompressed and not 16k aligned
        assertThat(checkZipPageAlign(SO_FILE_16K_ALIGNED, AlignedUncompressed))
            .isEmpty()
    }

    @Test
    fun `APK with so file that is not 16 KB aligned LOAD sections`() {
        // .so file is compressed and not 16k aligned
        assertThat(checkZipPageAlign(SO_FILE_NOT_16K_ALIGNED, UnalignedCompressed))
            .isEqualTo(setOf(ElfLoadSectionsNot16kAligned))

        // .so file is uncompressed and not 16k aligned
        assertThat(checkZipPageAlign(SO_FILE_NOT_16K_ALIGNED, UnalignedUncompressed))
            .isEqualTo(setOf(ElfLoadSectionsNot16kAligned, ElfNot16kAlignedInZip))

        // .so file is uncompressed and not 16k aligned
        assertThat(checkZipPageAlign(SO_FILE_NOT_16K_ALIGNED, AlignedCompressed))
            .isEqualTo(setOf(ElfLoadSectionsNot16kAligned))

        // .so file is uncompressed and not 16k aligned
        assertThat(checkZipPageAlign(SO_FILE_NOT_16K_ALIGNED, AlignedUncompressed))
            .isEqualTo(setOf(ElfLoadSectionsNot16kAligned))
    }

    // /emulator/lib64/gles_swiftshader/libEGL.so[400]
    @Test
    fun `so that is not 16 KB aligned`() {
        val input = ByteArrayInputStream(SO_FILE_NOT_16K_ALIGNED)
        assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
    }

    // First bytes of 21.4.7075529/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/arm-linux-androideabi/29/libc++.so
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
        val input = ByteArrayInputStream(byteArrayOf(
            127, 69, 76, 70, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 3, 0, 1, 0, 0,
            0, 0, 0, 0, 0, 52, 0, 0, 0, 84, -34, 0, 0, 0, 0, 0, 0, 52, 0, 32, 0, 8, 0,
            40, 0, 26, 0, 25, 0, 6, 0, 0, 0, 52, 0, 0, 0, 52, 0, 0, 0, 52, 0, 0, 0, 0,
            1, 0, 0, 0, 1, 0, 0, 4, 0, 0, 0, 4, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, -108, -52, 0, 0, -108, -52, 0, 0, 5, 0, 0, 0, 0, 16, 0,
            0, 1, 0, 0, 0, 124, -50, 0, 0, 124, -34, 0, 0, 124, -34, 0, 0, -28, 5, 0,
            0, -128, 9, 0, 0, 6, 0, 0, 0, 0, 16, 0, 0, 2, 0, 0, 0, -120, -50, 0, 0,
            -120, -34, 0, 0, -120, -34, 0, 0, 40, 1, 0, 0, 40, 1, 0, 0, 6, 0, 0, 0, 4,
            0, 0, 0, 4, 0, 0, 0, 52, 1, 0, 0, 52, 1, 0, 0, 52, 1, 0, 0, 32, 0, 0, 0,
            32, 0, 0, 0, 4, 0, 0, 0, 4, 0, 0, 0, 80, -27, 116, 100, 112, -55, 0, 0,
            112, -55, 0, 0, 112, -55, 0, 0, 36, 3, 0, 0, 36, 3, 0, 0, 4, 0, 0, 0, 4,
            0, 0, 0, 81, -27, 116, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 6, 0, 0, 0)
        )
        assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
    }

    // Synthetic big-endian
    @Test
    fun `so file is big-endian`() {
        val input = ByteArrayInputStream(
            byteArrayOf(127, 69, 76, 70, 1, 2, 1, 0, 0, 0, 0, 0)
        )
        assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
    }

    // Synthetic unknown endian-ness
    @Test
    fun `so file is unknown-endian`() {
        val input = ByteArrayInputStream(
            byteArrayOf(127, 69, 76, 70, 1, 0, 1, 0, 0, 0, 0, 0)
        )
        assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
    }

    // Synthetic wrong ELF version
    @Test
    fun `so file has wrong ELF version`() {
        val input = ByteArrayInputStream(
            byteArrayOf(127, 69, 76, 70, 1, 1, 0, 0, 0, 0, 0, 0)
        )
        assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
    }

    // Synthetic wrong bitness
    @Test
    fun `so file has wrong bitness`() {
        val input = ByteArrayInputStream(
            byteArrayOf(127, 69, 76, 70, 3, 1, 1, 0, 0, 0, 0, 0)
        )
        assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
    }

    // First bytes from build-tools/26.0.3/lib64/libc++.so
    @Test
    fun `program header flags are in a different position for 64-bit`() {
        val input = ByteArrayInputStream(byteArrayOf(
            127, 69, 76, 70, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 62, 0, 1, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 64, 0, 0, 0, 0, 0, 0, 0, -16, -31, 16, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 64, 0, 56, 0, 8, 0, 64, 0, 34, 0, 31, 0, 6, 0, 0, 0, 4))
        assertThat(checkPageAlign(input)).isEqualTo(PageAlignCheckResult.IsNotAligned64bitElf)
    }


    class ZipBuilder {
        private val outputStream = ByteArrayOutputStream()
        private val zipOutputStream = ZipArchiveOutputStream(outputStream)

        // Use an enum to make the tests read more transparently.
        enum class ZipEntryOptions {
            UnalignedCompressed,
            UnalignedUncompressed,
            AlignedCompressed,
            AlignedUncompressed
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

        fun toByteArray() : ByteArray {
            zipOutputStream.close() // Close the zipOutputStream to finalize the ZIP file
            return outputStream.toByteArray()
        }

        fun build(): ZipArchiveInputStream {
            return ZipArchiveInputStream(ByteArrayInputStream(toByteArray()))
        }
    }
}
