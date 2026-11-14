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

class CoverageUtilsTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun findProjectClassesLeavesOutTheClassesThatReportsSkip() {
    val classFolder = temporaryFolder.newFolder("classes")
    writeFiles(
      classFolder,
      "com/example/Foo.class",
      "com/example/Foo\$Bar.class",
      "com/example/R.class",
      "com/example/R\$string.class",
      "com/example/Manifest.class",
      "com/example/Manifest\$permission.class",
      "com/example/BuildConfig.class",
      "META-INF/app.kotlin_module",
    )

    assertThat(findProjectClasses(listOf(classFolder)))
      .containsExactly("com/example/Foo", "com/example/Foo\$Bar", "com/example/BuildConfig")
  }

  @Test
  fun findProjectClassesNamesTheClassesOfANestedClassFolderRelativeToIt() {
    val classFolder = temporaryFolder.newFolder("classes")
    writeFiles(classFolder, "com/example/Foo.class")
    val nestedClassFolder = File(classFolder, "nested")
    writeFiles(nestedClassFolder, "com/example/Bar.class")

    assertThat(findProjectClasses(listOf(classFolder, nestedClassFolder))).containsExactly("com/example/Foo", "com/example/Bar")
  }

  @Test
  fun findProjectClassesSkipsEntriesThatAreNotFolders() {
    val jar = temporaryFolder.newFile("classes.jar")
    val classFile = temporaryFolder.newFile("Foo.class")

    assertThat(findProjectClasses(listOf(jar, classFile, File(temporaryFolder.root, "missing")))).isEmpty()
  }

  private fun writeFiles(folder: File, vararg paths: String) {
    for (path in paths) {
      File(folder, path).apply { parentFile.mkdirs() }.writeText("")
    }
  }
}
