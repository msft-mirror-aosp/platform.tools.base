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

package com.android.tools.uicheck.checks

import com.android.tools.uicheck.ComponentSourceLocation
import com.android.tools.uicheck.ProjectSourceFile
import kotlin.math.absoluteValue

/**
 * Decides whether a component's call site is in the user's code rather than in a library such as Material 3.
 *
 * A file name alone is ambiguous (the user's `Button.kt` vs. Material's `Button.kt`), so a call site's package hash must also match the
 * package of the user's file (or the preview's own package). Android Studio uses the same hash to navigate from a preview to its source.
 */
class UserCodeFilter(
  projectFiles: Collection<ProjectSourceFile>,
  previewMethodFqn: String? = null,
) {

  // Maps each user source file name (e.g. "Button.kt") to the package hashes of all project
  // files with that name, since multiple packages in a project can share the same file name.
  private val packageHashesByFileName: Map<String, Set<Int>> =
    buildMap<String, MutableSet<Int>> {
      projectFiles.forEach { (fileName, packageName) ->
        getOrPut(fileName) { mutableSetOf() }.add(packageNameHash(packageName))
      }
    }

  // Fallback package hash extracted from the @Preview method's FQN (e.g. "com.example" from
  // "com.example.LoginScreenKt.LoginPreview"), used when projectFiles is empty or omits the
  // preview's own source file.
  private val previewPackageHash: Int? = previewPackageName(previewMethodFqn)?.let(::packageNameHash)

  /** Returns true if [location] belongs to user code in the project or the preview's package. */
  fun isUserCode(location: ComponentSourceLocation): Boolean {
    // Reject missing file names or invalid line numbers.
    if (location.fileName.isBlank() || location.lineNumber < 0) return false

    // Require a package hash so we never match on file name alone
    // which could misattribute a library's "Button.kt" line number to the user's "Button.kt".
    val packageHash = location.packageHash ?: return false

    // Accept any call site in the @Preview method's own package.
    // It covers previews when projectFiles is empty or omits the preview's source set.
    if (packageHash == previewPackageHash) return true

    // Otherwise, both the file name and its package hash must match a known project file.
    val projectHashesForFile = packageHashesByFileName[location.fileName] ?: return false
    return packageHash in projectHashesForFile
  }

  companion object {

    /**
     * Extracts `"com.example"` from a preview method FQN like `"com.example.LoginScreenKt.LoginPreview"`, or returns `null` if there is no
     * package prefix.
     */
    private fun previewPackageName(methodFqn: String?): String? {
      // Strip the method name: "com.example.LoginScreenKt.LoginPreview" -> "com.example.LoginScreenKt"
      val classFqn = methodFqn?.substringBeforeLast('.', missingDelimiterValue = "") ?: return null
      // Strip the class name: "com.example.LoginScreenKt" -> "com.example" (or null if in the default package)
      return classFqn.substringBeforeLast('.', missingDelimiterValue = "").ifEmpty { null }
    }

    /** The hash of a package name that the Compose compiler records in source information (see Android Studio's `packageNameHash`). */
    @JvmStatic fun packageNameHash(packageName: String): Int = packageName.fold(0) { hash, char -> hash * 31 + char.code }.absoluteValue
  }
}
