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

/**
 * A Comparator that compares two strings representing relative file paths. It ensures that files are sorted before directories within the
 * same directory.
 */
internal class FileBeforeDirectoryComparator : Comparator<String> {
  override fun compare(path1: String, path2: String): Int {
    if (path1 == path2) return 0

    val parts1 = path1.split('/')
    val parts2 = path2.split('/')

    val minLen = minOf(parts1.size, parts2.size)

    for (i in 0 until minLen) {
      val part1 = parts1[i]
      val part2 = parts2[i]

      val isPart1Dir = i < parts1.size - 1
      val isPart2Dir = i < parts2.size - 1

      if (isPart1Dir != isPart2Dir) {
        // One is a directory, the other is a file. Files come first.
        return if (isPart1Dir) 1 else -1
      }

      // Both are directories or both are files at this level.
      val cmp = part1.compareTo(part2)
      if (cmp != 0) {
        return cmp
      }
    }

    // One path is a prefix of the other (e.g., "a" vs "a/b")
    return parts1.size.compareTo(parts2.size)
  }
}
