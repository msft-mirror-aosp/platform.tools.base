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
package com.android.adblib.utils

import com.android.adblib.AdbLogger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** Utility functions for locating the `adb` executable when starting an ADB server. */
object AdbServerStartupUtils {

  /**
   * Searches the directories listed in the `PATH` environment variable, in order, for the `adb` executable (`adb.exe` on Windows) and
   * returns the absolute [Path] of the first match. Empty and invalid `PATH` entries are skipped.
   *
   * @throws IOException if the `PATH` environment variable is not defined, or if `adb` cannot be found in any of its directories
   */
  fun findAdbOnPath(logger: AdbLogger? = null): Path {
    return findAdbOnPath(osName = System.getProperty("os.name"), pathEnv = System.getenv("PATH"), logger = logger)
  }

  internal fun findAdbOnPath(osName: String?, pathEnv: String?, logger: AdbLogger? = null): Path {
    if (pathEnv == null) {
      throw IOException("No PATH environment variable is defined")
    }
    val isWindows = osName?.startsWith("Windows", ignoreCase = true) == true
    val executableName = if (isWindows) "adb.exe" else "adb"
    for (rawBinDir in pathEnv.split(File.pathSeparator)) {
      // Windows allows quoting `PATH` entries (e.g. `"C:\Program Files\foo"`), and `"` is not a valid character in Windows paths
      val binDir = if (isWindows) rawBinDir.replace("\"", "").trim() else rawBinDir
      // Skip empty entries (e.g. from "::" or a trailing separator), which would otherwise resolve to the current working directory
      if (binDir.isBlank()) {
        continue
      }
      val file =
        try {
          // Resolve relative `PATH` entries (e.g. "."), since `ProcessRunner` requires an absolute executable path
          Paths.get(binDir).resolve(executableName).toAbsolutePath()
        } catch (e: InvalidPathException) {
          logger?.debug { "Skipping invalid PATH entry '$binDir': ${e.message}" }
          continue
        }
      if (Files.isRegularFile(file)) {
        return file
      }
    }
    logger?.debug { "$executableName could not be located in any of the $pathEnv folders" }
    throw IOException("Couldn't locate '$executableName' on PATH")
  }
}
