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

import com.android.tools.res.AssetFileOpener
import com.intellij.openapi.Disposable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.ZipFile

/**
 * [AssetFileOpener] that loads assets from an APK Zip file if it exists.
 *
 * It falls back to standard filesystem [FileInputStream] if the APK is not specified, if the entry is missing in the Zip, or if an
 * [IOException] occurs.
 */
class StandaloneAssetFileOpener(private val resourceApkPath: String?) : AssetFileOpener, Disposable {

  private val logger = Logger.getLogger(StandaloneAssetFileOpener::class.java.name)

  private val zipFile: ZipFile? by lazy {
    resourceApkPath?.let {
      try {
        ZipFile(it)
      } catch (e: IOException) {
        logger.log(Level.WARNING, "Failed to open APK $it", e)
        null
      }
    }
  }

  override fun dispose() {
    try {
      zipFile?.close()
    } catch (e: IOException) {
      logger.log(Level.WARNING, "Failed to close APK $resourceApkPath", e)
    }
  }

  private fun getInputStream(path: String, isAsset: Boolean): InputStream? {
    zipFile?.let { zf ->
      val zipEntryPath = if (isAsset) "assets/$path" else path
      val entry = zf.getEntry(zipEntryPath)
      if (entry != null) {
        try {
          return zf.getInputStream(entry)
        } catch (e: IOException) {
          logger.log(Level.WARNING, "Failed to load entry $path from APK $resourceApkPath, falling back to filesystem", e)
        }
      }
    }
    val file = File(path)
    return if (file.exists()) FileInputStream(file) else null
  }

  override fun openAssetFile(path: String): InputStream? = getInputStream(path, true)

  override fun openNonAssetFile(path: String): InputStream? = getInputStream(path, false)
}
