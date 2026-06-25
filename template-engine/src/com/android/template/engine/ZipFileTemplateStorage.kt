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

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.inputStream

internal class ZipFileTemplateStorage(private val zipFile: Path) : TemplateDefinitionStorage {
  private val lock = Any()
  private var fileSystem: FileSystem? = null
  private var openRefCount = 0

  override fun open(): TemplateDefinitionStorage.Handle {
    return acquire().let { ZipFileHandle() }
  }

  internal fun <R> withZipInputStream(block: (ZipInputStream) -> R): R {
    return zipFile.inputStream().use { block(ZipInputStream(it)) }
  }

  fun <R> openAndUse(block: (FileSystem) -> R): R {
    val fs = acquire()
    return try {
      block(fs)
    } finally {
      release()
    }
  }

  private fun acquire(): FileSystem =
    synchronized(lock) {
      if (openRefCount == 0) {
        check(fileSystem == null) { "Internal error: file system for '$zipFile' should be null when openRefCount is 0" }
        val fs = FileSystems.newFileSystem(zipFile, null as ClassLoader?)
        fileSystem = fs
        openRefCount = 1
        return fs
      } else {
        openRefCount++
        return fileSystem ?: throw IllegalStateException("Internal error: file system for '$zipFile' should be active")
      }
    }

  private fun release() =
    synchronized(lock) {
      check(openRefCount > 0) { "Internal error: openRefCount is underflowing" }
      openRefCount--
      if (openRefCount == 0) {
        check(fileSystem != null) { "Internal error: file system for '$zipFile' should not be null when openRefCount is 0" }
        fileSystem?.close()
        fileSystem = null
      }
    }

  private inner class ZipFileHandle : TemplateDefinitionStorage.Handle {
    override fun close() {
      this@ZipFileTemplateStorage.release()
    }
  }
}
