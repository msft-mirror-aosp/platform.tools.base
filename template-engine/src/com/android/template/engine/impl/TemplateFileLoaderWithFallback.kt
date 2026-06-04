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
package com.android.template.engine.impl

import com.android.template.engine.TemplateDefinitionStorage
import com.android.template.engine.TemplateFile
import com.android.template.engine.TemplateFileEntry
import com.android.template.engine.TemplateFileLoader

/**
 * A [TemplateFileLoader] implementation that attempts to resolve files from a preloaded map of [loadedFiles], and falls back to a
 * [fallbackLoader] if the file is not found in the map.
 *
 * This loader is useful when some files of a template (e.g. extra files or preloaded template files) have already been loaded into
 * memory/cached, while other files should still be loaded dynamically on demand.
 */
internal class TemplateFileLoaderWithFallback(
  private val loadedFiles: Map<TemplateFileEntry, TemplateFile>,
  private val fallbackLoader: TemplateFileLoader,
) : TemplateFileLoader {
  override val storage: TemplateDefinitionStorage
    get() = fallbackLoader.storage

  override fun <R> withLoader(block: (TemplateFileLoader.Loader) -> R): R {
    return fallbackLoader.withLoader { fallback ->
      val loader = FallbackLoader(loadedFiles, fallback)
      block(loader)
    }
  }

  /** A [TemplateFileLoader.Loader] implementation that checks [loadedFiles] first and falls back to the [fallback] loader. */
  private class FallbackLoader(
    private val loadedFiles: Map<TemplateFileEntry, TemplateFile>,
    private val fallback: TemplateFileLoader.Loader,
  ) : TemplateFileLoader.Loader {
    override fun loadFile(entry: TemplateFileEntry): TemplateFile {
      return loadedFiles[entry] ?: fallback.loadFile(entry)
    }
  }
}
