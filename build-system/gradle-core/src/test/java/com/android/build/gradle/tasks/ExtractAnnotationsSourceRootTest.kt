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

package com.android.build.gradle.tasks

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.RelativePath
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ExtractAnnotationsSourceRootTest {

  @Test
  fun testOverlappingSourceRoots() {
    val visitor = ExtractAnnotations.SourceFileVisitor()

    // Mock a file in .../src/com/example/Foo.java
    // Source root should be .../src
    val file1 = mock(FileVisitDetails::class.java)
    // Use absolute paths as they would be on the system
    val root1 = File("/abs/path/to/src").absolutePath
    val path1 = File("$root1/com/example/Foo.java")
    val relPath1 = RelativePath(true, "com", "example", "Foo.java")
    `when`(file1.file).thenReturn(path1)
    `when`(file1.relativePath).thenReturn(relPath1)

    visitor.visitFile(file1)

    // Mock a file in .../src_dagger/com/example/Bar.java
    // Source root should be .../src_dagger
    val file2 = mock(FileVisitDetails::class.java)
    val root2 = File("/abs/path/to/src_dagger").absolutePath
    val path2 = File("$root2/com/example/Bar.java")
    val relPath2 = RelativePath(true, "com", "example", "Bar.java")
    `when`(file2.file).thenReturn(path2)
    `when`(file2.relativePath).thenReturn(relPath2)

    visitor.visitFile(file2)

    val sourceRoots = visitor.getSourceRoots()
    assertThat(sourceRoots).containsExactly(File(root1), File(root2))
  }
}
