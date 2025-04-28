/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.tools.appinspection.database

import java.io.File
import java.io.IOException
import kotlin.text.Charsets.UTF_8

object Utils {
  /**
   * Returns true if the file is a valid database
   *
   * https://www.sqlite.org/fileformat.html#magic_header_string
   */
  fun File.isDatabase(): Boolean {
    return try {
      val header = inputStream().use { it.readNBytes(16) }
      header.toString(UTF_8) == "SQLite format 3\u0000"
    } catch (_: IOException) {
      false
    }
  }
}
