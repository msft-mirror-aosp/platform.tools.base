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

package com.android.tools.androidtest.testengine.util

import java.io.File

/**
 * Utility functions for centralizing path sanitization and containment validation against path traversal attacks (b/509645146,
 * b/509658904).
 */
object PathSafety {

  // Allowlist regex for device display names and serials: alphanumeric, dots, underscores,
  // hyphens, and spaces. Space is explicitly allowed to support standard emulator names.
  private val safeNameRegex = Regex("[^a-zA-Z0-9._ -]")

  // Strict allowlist regex for file names and other leaf path components (no spaces allowed).
  private val strictSafeRegex = Regex("[^a-zA-Z0-9._-]")

  /**
   * Sanitizes a device display name or serial number to prevent directory traversal. Unsafe characters are replaced with underscores. Dots
   * are replaced with underscores if they form a relative path (e.g. ".." becomes "__") to prevent UniqueId collisions.
   */
  fun sanitizeDisplayName(raw: String): String {
    val sanitized = safeNameRegex.replace(raw, "_")
    return if (sanitized.isBlank() || sanitized.all { it == '.' }) {
      sanitized.replace('.', '_').trim().ifEmpty { "device" }
    } else {
      sanitized
    }
  }

  /**
   * Sanitizes a leaf file name to a strict set of safe characters (no spaces, no slashes). Unsafe characters are replaced with underscores.
   */
  fun sanitizeLeafName(raw: String): String? {
    val sanitized = strictSafeRegex.replace(raw, "_")
    return if (sanitized.isBlank() || sanitized.all { it == '.' }) {
      null
    } else {
      sanitized
    }
  }

  /**
   * Resolves a relative path [child] under a [baseDir] and verifies that the resolved canonical path is strictly contained within the base
   * directory. Returns null if it escapes.
   */
  fun resolveContainedFile(baseDir: File, child: String): File? {
    val baseCanon = baseDir.canonicalFile.toPath()
    val targetFile = File(baseDir, child)
    val targetCanon = targetFile.canonicalFile.toPath()
    return if (targetCanon.startsWith(baseCanon)) {
      targetFile
    } else {
      null
    }
  }
}
