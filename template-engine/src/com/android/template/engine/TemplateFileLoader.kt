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

import java.nio.file.Files
import java.nio.file.Path

/** Provides access to template file content ([TemplateFile]) for a given set of [TemplateFileEntry] entries of template. */
interface TemplateFileLoader {
  /** The [TemplateDefinitionStorage] (e.g. ZipFile) used by this loader to access the template definitions. */
  val storage: TemplateDefinitionStorage

  /** Opens the underlying [TemplateDefinitionStorage] (e.g. ZipFile) and allows loading any [TemplateFileEntry] through the [Loader]. */
  fun <R> withLoader(block: (Loader) -> R): R

  interface Loader {
    fun loadFile(entry: TemplateFileEntry): TemplateFile
  }

  companion object {
    internal fun forMap(map: Map<TemplateFileEntry, TemplateFile>): TemplateFileLoader {
      return forFunction({ entry -> map[entry] ?: throw IllegalArgumentException("Unknown template entry '$entry'") })
    }

    internal fun forFunction(func: (TemplateFileEntry) -> TemplateFile): TemplateFileLoader {
      return FunctionTemplateFileLoader(func)
    }

    internal fun forZipStorage(zipStorage: ZipFileTemplateStorage, rootPath: String): TemplateFileLoader {
      return ZipTemplateFileLoader(zipStorage, rootPath)
    }

    /** A [TemplateFileLoader] that uses a function to provide content of template files. */
    private class FunctionTemplateFileLoader(val func: (TemplateFileEntry) -> TemplateFile) : TemplateFileLoader {
      private val loader = FuncLoader()

      override val storage: TemplateDefinitionStorage
        get() = NoStorage

      override fun <R> withLoader(block: (Loader) -> R): R {
        return block(loader)
      }

      private inner class FuncLoader : Loader {
        override fun loadFile(entry: TemplateFileEntry): TemplateFile {
          return func(entry)
        }
      }

      private object NoStorage : TemplateDefinitionStorage {
        override fun open(): TemplateDefinitionStorage.Handle {
          return NoHandle
        }

        private object NoHandle : TemplateDefinitionStorage.Handle {
          override fun close() {
            // Nothing to do
          }
        }
      }
    }

    internal class ZipTemplateFileLoader(private val zipStorage: ZipFileTemplateStorage, private val rootPath: String) :
      TemplateFileLoader {

      override val storage: TemplateDefinitionStorage
        get() = zipStorage

      override fun <R> withLoader(block: (Loader) -> R): R {
        return zipStorage.openAndUse { zipFS ->
          val zipFsRootPath = zipFS.getPath(rootPath)
          block(ZipLoader(zipFsRootPath))
        }
      }

      private class ZipLoader(private val rootPath: Path) : Loader {
        override fun loadFile(entry: TemplateFileEntry): TemplateFile {
          val pathInsideZip = rootPath.resolve(entry.relativePath)
          val content = Files.readAllBytes(pathInsideZip)
          return TemplateFile(entry.relativePath, content)
        }
      }
    }
  }
}
