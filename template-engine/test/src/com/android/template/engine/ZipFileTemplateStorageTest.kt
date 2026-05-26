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
package com.android.template.engine

import com.android.template.engine.DefaultFileStorageTest.Companion.FileSystemId
import com.google.common.truth.Truth.assertThat
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@Suppress("FunctionName")
@RunWith(Parameterized::class)
class ZipFileTemplateStorageTest(private val fileSystemId: FileSystemId) {
  @get:Rule val tempFolder = TemporaryFolder()

  private fun getTestRootPath(): Path {
    return DefaultFileStorageTest.getTestRootPath(fileSystemId, tempFolder).resolve("test-zip-storage")
  }

  @Test
  fun `test openAndUse opens and closes filesystem`() {
    val rootPath = getTestRootPath()
    Files.createDirectories(rootPath)
    val zipFile = rootPath.resolve("archive.zip")
    createEmptyZip(zipFile)

    val storage = ZipFileTemplateStorage(zipFile)

    var fsRef: FileSystem? = null
    storage.openAndUse { fs ->
      fsRef = fs
      assertThat(fs.isOpen).isTrue()
    }

    val finalFs = fsRef
    assertThat(finalFs).isNotNull()
    assertThat(finalFs!!.isOpen).isFalse()
  }

  @Test
  fun `test nested open handles keep filesystem open until all are closed`() {
    val rootPath = getTestRootPath()
    Files.createDirectories(rootPath)
    val zipFile = rootPath.resolve("archive.zip")
    createEmptyZip(zipFile)

    val storage = ZipFileTemplateStorage(zipFile)

    var fsRef: FileSystem? = null

    // Open first handle
    val handle1 = storage.open()
    storage.openAndUse { fs ->
      fsRef = fs
      assertThat(fs.isOpen).isTrue()
    }
    val finalFs = fsRef
    assertThat(finalFs).isNotNull()
    assertThat(finalFs!!.isOpen).isTrue()

    // Open second handle
    val handle2 = storage.open()
    assertThat(finalFs.isOpen).isTrue()

    // Close first handle, filesystem should remain open
    handle1.close()
    assertThat(finalFs.isOpen).isTrue()

    // Close second handle, filesystem should close
    handle2.close()
    assertThat(finalFs.isOpen).isFalse()
  }

  @Test
  fun `test openAndUse is exception safe`() {
    val rootPath = getTestRootPath()
    Files.createDirectories(rootPath)
    val zipFile = rootPath.resolve("archive.zip")
    createEmptyZip(zipFile)

    val storage = ZipFileTemplateStorage(zipFile)
    val fsRef = arrayOfNulls<FileSystem>(1)

    try {
      storage.openAndUse { fs ->
        fsRef[0] = fs
        throw RuntimeException("Force fail")
      }
    } catch (e: Exception) {
      assertThat(e.message).isEqualTo("Force fail")
    }

    val finalFs = fsRef[0]
    assertThat(finalFs).isNotNull()
    assertThat(finalFs!!.isOpen).isFalse()
  }

  @Test
  fun `test withZipInputStream reads stream correctly`() {
    val rootPath = getTestRootPath()
    Files.createDirectories(rootPath)
    val zipFile = rootPath.resolve("archive.zip")

    // Create a zip with some file content
    ZipOutputStream(Files.newOutputStream(zipFile)).use { zos ->
      zos.putNextEntry(ZipEntry("test.txt"))
      zos.write("hello".toByteArray(Charsets.UTF_8))
      zos.closeEntry()
    }

    val storage = ZipFileTemplateStorage(zipFile)

    storage.withZipInputStream { zis ->
      val entry = zis.nextEntry
      assertThat(entry).isNotNull()
      assertThat(entry!!.name).isEqualTo("test.txt")
      assertThat(zis.readBytes().toString(Charsets.UTF_8)).isEqualTo("hello")
    }
  }

  private fun createEmptyZip(path: Path) {
    ZipOutputStream(Files.newOutputStream(path)).use {
      // Just open and close to make a valid empty zip archive
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun allFileSystems(): Collection<Array<Any>> = DefaultFileStorageTest.allFileSystems()
  }
}
