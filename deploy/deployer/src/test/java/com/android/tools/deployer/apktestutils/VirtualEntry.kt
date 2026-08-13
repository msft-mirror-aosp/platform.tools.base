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
package com.android.tools.deployer.apktestutils

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Represents a virtual entry within a test APK archive. */
interface VirtualEntry {
  val name: String

  /** Writes this entry's header and payload to the provided [ZipOutputStream]. */
  fun writeTo(zos: ZipOutputStream)
}

/** A virtual ZIP entry containing text content. */
class TextEntry(override val name: String, val content: String) : VirtualEntry {
  override fun writeTo(zos: ZipOutputStream) {
    val entry = ZipEntry(name).apply { time = 0L }
    zos.putNextEntry(entry)
    zos.write(content.toByteArray(Charsets.UTF_8))
    zos.closeEntry()
  }
}

/** A virtual ZIP entry containing raw binary bytes. */
class BinaryEntry(override val name: String, val bytes: ByteArray) : VirtualEntry {
  override fun writeTo(zos: ZipOutputStream) {
    val entry = ZipEntry(name).apply { time = 0L }
    zos.putNextEntry(entry)
    zos.write(bytes)
    zos.closeEntry()
  }
}

/** A virtual ZIP entry containing raw binary bytes and custom extra fields. */
class ExtraFieldsBinaryEntry(override val name: String, val bytes: ByteArray, val extra: ByteArray) : VirtualEntry {
  override fun writeTo(zos: ZipOutputStream) {
    val entry =
      ZipEntry(name).apply {
        time = 0L
        setExtra(this@ExtraFieldsBinaryEntry.extra)
      }
    zos.putNextEntry(entry)
    zos.write(bytes)
    zos.closeEntry()
  }
}
