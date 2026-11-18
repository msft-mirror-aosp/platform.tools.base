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

package com.android.build.gradle.internal.coverage

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverageClassListTaskTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  /** Creates an empty class file named [name], such as `com/example/Foo`, in [folder]. */
  private fun classFile(folder: File, name: String) {
    File(folder, "$name.class").apply {
      parentFile.mkdirs()
      writeBytes(byteArrayOf())
    }
  }

  @Test
  fun writesTheSortedClassesWithHeaderAndEndMarker() {
    val javacFolder = temporaryFolder.newFolder("javac")
    val kotlinFolder = temporaryFolder.newFolder("kotlin")
    classFile(kotlinFolder, "com/example/Foo")
    classFile(kotlinFolder, "com/example/Foo\$special\$\$inlined\$map\$1")
    classFile(javacFolder, "hilt_aggregated_deps/_com_example_FooModule")
    classFile(javacFolder, "com/example/Bar")
    val listFile = File(temporaryFolder.root, "out/coverage_classes.txt")

    writeCoverageClassList(listOf(javacFolder, kotlinFolder), listFile)

    assertThat(listFile.readText())
      .isEqualTo(
        """
        #coverage-classes v1 count=4
        com/example/Bar
        com/example/Foo
        com/example/Foo${'$'}special${'$'}${'$'}inlined${'$'}map${'$'}1
        hilt_aggregated_deps/_com_example_FooModule
        #end

        """
          .trimIndent()
      )
    assertThat(File(listFile.parentFile, "coverage_classes.txt.tmp").exists()).isFalse()
  }

  @Test
  fun leavesOutTheClassesThatTheReportSkips() {
    val folder = temporaryFolder.newFolder("classes")
    classFile(folder, "com/example/Foo")
    classFile(folder, "com/example/R")
    classFile(folder, "com/example/R\$string")
    classFile(folder, "com/example/Manifest")
    classFile(folder, "com/example/package-info")
    classFile(folder, "module-info")
    val listFile = File(temporaryFolder.root, "coverage_classes.txt")

    writeCoverageClassList(listOf(folder), listFile)

    assertThat(listFile.readLines()).containsExactly("#coverage-classes v1 count=1", "com/example/Foo", "#end").inOrder()
  }

  @Test
  fun writesNoFileWithoutClasses() {
    val folder = temporaryFolder.newFolder("classes")
    classFile(folder, "com/example/R")
    val listFile = File(temporaryFolder.root, "coverage_classes.txt").apply { writeText("an old list") }

    writeCoverageClassList(listOf(folder), listFile)

    assertThat(listFile.exists()).isFalse()
  }

  @Test
  fun replacesAnOldList() {
    val folder = temporaryFolder.newFolder("classes")
    classFile(folder, "com/example/Foo")
    val listFile = File(temporaryFolder.root, "coverage_classes.txt").apply { writeText("an old list") }

    writeCoverageClassList(listOf(folder), listFile)

    assertThat(listFile.readLines()).containsExactly("#coverage-classes v1 count=1", "com/example/Foo", "#end").inOrder()
  }
}
