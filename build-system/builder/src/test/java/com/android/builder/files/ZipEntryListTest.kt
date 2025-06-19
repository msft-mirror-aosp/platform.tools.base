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

package com.android.builder.files

import com.android.zipflinger.ZipMap
import com.google.common.truth.Truth.assertThat
import com.google.gson.stream.MalformedJsonException
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ZipEntryListTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var tempRoot: File

    @Before
    fun setup() {
        tempRoot = temporaryFolder.newFolder()
    }

    @Test
    fun `deserialization of a previously stored zip entry list`() {
        val file = prepareZipEntryListInFile()
        val after = ZipEntryList.deserializeFile(file)
        assertThat(after).isEqualTo(TEST_ENTRY_LIST)
    }

    @Test
    fun `deserialization of an empty file should throw exception`() {
        assertFailsWith<EOFException> {
            val file = File(tempRoot, "test").apply {
                createNewFile()
            }
            ZipEntryList.deserializeFile(file)
        }
    }

    @Test
    fun `deserialization of a malformed file should fail`() {
        assertFailsWith<MalformedJsonException> {
            val file = File(tempRoot, "test").apply {
                createNewFile()
                writeText("Malformed content example")
            }
            ZipEntryList.deserializeFile(file)
        }
    }

    @Test
    fun `reading zip entry list of an invalid zip should fail`() {
        val exception = assertFailsWith<IllegalStateException> {
            val file = temporaryFolder.newFile("a.zip")
            ZipEntryList.fromZip(file)
        }
        assertThat(exception.message).startsWith("Could not find EOCD in")
    }

    @Test
    fun `zip entry list creation from a zip file matches expected content`() {
        val zip = ZipTestTool.createZipFile(
            tempRoot.absolutePath + "/a.zip", "item.txt", "content"
        )
        val zipEntryList = ZipEntryList.fromZip(zip)
        assertThat(zipEntryList.dump()).isEqualTo(zip.dump())
    }

    private fun prepareZipEntryListInFile(): File {
        val asByteArray = TEST_ENTRY_LIST.toByteArray()
        val output = temporaryFolder.newFile("output")
        output.writeBytes(asByteArray)
        return output
    }

    private fun ZipEntryList.dump(): String {
        return entries.entries.toList().flatMap { (path, entry) ->
            listOf(path, entry.name, entry.size, entry.crc)
        }.joinToString()
    }

    private fun File.dump(): String {
        return ZipMap.from(toPath()).entries.entries.toList().flatMap { (path, entry) ->
            listOf(path, entry.name, entry.uncompressedSize, entry.crc)
        }.joinToString()
    }

    companion object {

        val TEST_ENTRY_LIST = ZipEntryList(
            mapOf(
                "a" to ZipEntry("a.txt", 1L, 1L),
                "b" to ZipEntry("b.txt", 1000L, 10L),
                "c" to ZipEntry("c.txt", 2000L, 20L),
            )
        )
    }
}
