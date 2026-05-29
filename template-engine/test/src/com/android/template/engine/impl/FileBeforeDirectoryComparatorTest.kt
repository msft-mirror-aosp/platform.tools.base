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

import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileBeforeDirectoryComparatorTest {
  private val comparator = FileBeforeDirectoryComparator()

  @Test
  fun testDirectoryBeforeFile() {
    val file1 = "src/main/AndroidManifest.xml"
    val dir1 = "src/main/java/com/example/MainActivity.kt"
    val file2 = "src/main/java" // although technically a dir, its relative path has 3 parts

    assertThat(comparator.compare(file1, dir1)).isLessThan(0)
    assertThat(comparator.compare(dir1, file1)).isGreaterThan(0)
    assertThat(comparator.compare(file2, dir1)).isLessThan(0)
    assertThat(comparator.compare(file2, file1)).isGreaterThan(0)
  }

  @Test
  fun testSameDirectoryFiles() {
    val file1 = "src/main/A.kt"
    val file2 = "src/main/B.kt"

    assertThat(comparator.compare(file1, file2)).isLessThan(0)
    assertThat(comparator.compare(file2, file1)).isGreaterThan(0)
    assertThat(comparator.compare(file1, file1)).isEqualTo(0)
  }

  @Test
  fun testSortList() {
    val paths =
      listOf(
          "app/build.gradle.kts",
          "app/src/main/AndroidManifest.xml",
          "app/src/main/java/com/example/MainActivity.kt",
          "app/src/main/res/values/strings.xml",
          "build.gradle.kts",
          "settings.gradle.kts",
        )
        .shuffled() // ensure it gets shuffled so sort does something

    val expected =
      listOf(
        "build.gradle.kts",
        "settings.gradle.kts",
        "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/java/com/example/MainActivity.kt",
        "app/src/main/res/values/strings.xml",
      )

    val sorted = paths.sortedWith(comparator)
    Truth.assertThat(sorted).isEqualTo(expected)
  }

  @Test
  fun testSortListComplex() {
    val paths = listOf("dir/z.txt", "dir/sub/c.txt", "a.txt", "b.txt").shuffled()

    val sorted = paths.sortedWith(comparator)
    val expected = listOf("a.txt", "b.txt", "dir/z.txt", "dir/sub/c.txt")
    Truth.assertThat(sorted).isEqualTo(expected)
  }
}
