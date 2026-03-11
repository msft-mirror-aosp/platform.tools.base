/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.tools.render

import com.google.common.truth.Truth.assertThat
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StandaloneAssetFileOpenerTest {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun testOpenAssetFile_fromZip() {
    val zipFile = tempFolder.newFile("test.apk")
    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
      zos.putNextEntry(ZipEntry("assets/foo.txt"))
      zos.write("Hello Zip".toByteArray())
      zos.closeEntry()
    }

    val opener = StandaloneAssetFileOpener(zipFile.absolutePath)
    val stream = opener.openAssetFile("foo.txt")
    val content = stream?.readBytes()?.toString(Charsets.UTF_8)
    assertThat(content).isEqualTo("Hello Zip")
  }

  @Test
  fun testOpenNonAssetFile_fromZip() {
    val zipFile = tempFolder.newFile("test_non.apk")
    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
      zos.putNextEntry(ZipEntry("res/values/strings.xml"))
      zos.write("<resources>".toByteArray())
      zos.closeEntry()
    }

    val opener = StandaloneAssetFileOpener(zipFile.absolutePath)
    val stream = opener.openNonAssetFile("res/values/strings.xml")
    val content = stream?.readBytes()?.toString(Charsets.UTF_8)
    assertThat(content).isEqualTo("<resources>")
  }

  @Test
  fun testFallbackToFileInputStream_whenEntryMissing() {
    val zipFile = tempFolder.newFile("test_fallback.apk")
    ZipOutputStream(FileOutputStream(zipFile)).use { zos -> } // empty zip

    val fallbackFile = tempFolder.newFile("foo.txt")
    fallbackFile.writeText("Hello Filesystem")

    val opener = StandaloneAssetFileOpener(zipFile.absolutePath)
    // openNonAssetFile uses exact path inside getInputStream
    val stream = opener.openNonAssetFile(fallbackFile.absolutePath)
    val content = stream?.readBytes()?.toString(Charsets.UTF_8)
    assertThat(content).isEqualTo("Hello Filesystem")
  }

  @Test
  fun testReturnNull_whenEverythingMissing() {
    val opener = StandaloneAssetFileOpener(null)
    val stream = opener.openAssetFile("missing.txt")
    assertThat(stream).isNull()
  }

  @Test
  fun testMultipleReads_fromSameZip() {
    val zipFile = tempFolder.newFile("test_multiple.apk")
    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
      zos.putNextEntry(ZipEntry("assets/one.txt"))
      zos.write("First".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("assets/two.txt"))
      zos.write("Second".toByteArray())
      zos.closeEntry()
    }

    val opener = StandaloneAssetFileOpener(zipFile.absolutePath)
    assertThat(opener.openAssetFile("one.txt")?.readBytes()?.toString(Charsets.UTF_8)).isEqualTo("First")
    assertThat(opener.openAssetFile("two.txt")?.readBytes()?.toString(Charsets.UTF_8)).isEqualTo("Second")
    opener.dispose()
  }
}
