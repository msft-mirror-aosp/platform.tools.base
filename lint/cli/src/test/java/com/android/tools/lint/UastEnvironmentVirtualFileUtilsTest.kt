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
package com.android.tools.lint

import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UastEnvironmentVirtualFileUtilsTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun testToPathCollectionPreservesOrder() {
    val names = listOf("z", "a", "m", "b", "y", "c", "x", "d", "w", "e")
    val fileSystem = CoreLocalFileSystem()

    val physicalFiles = names.map { temporaryFolder.newFile("$it.jar") }
    val physicalDirs = names.map { temporaryFolder.newFolder("dir_$it") }
    val virtualFiles = names.map {
      fileSystem.findFileByIoFile(temporaryFolder.newFile("v_$it.jar"))!!
    }
    val virtualDirs = names.map {
      fileSystem.findFileByIoFile(temporaryFolder.newFolder("vdir_$it"))!!
    }

    val inputs = physicalFiles + physicalDirs + virtualFiles.map { it.asFile() } + virtualDirs.map { it.asFile() }

    val pathCollection = inputs.toPathCollection()

    assertThat(pathCollection.physicalFiles).containsExactlyElementsIn(physicalFiles.map { it.toPath() }).inOrder()
    assertThat(pathCollection.physicalDirectories).containsExactlyElementsIn(physicalDirs.map { it.toPath() }).inOrder()
    assertThat(pathCollection.virtualFiles).containsExactlyElementsIn(virtualFiles).inOrder()
    assertThat(pathCollection.virtualDirectories).containsExactlyElementsIn(virtualDirs).inOrder()
  }

  @Test
  fun testGetSourceFilePathsPreservesOrder() {
    val names = listOf("z", "a", "m", "b", "y", "c", "x", "d", "w", "e")
    val fileSystem = CoreLocalFileSystem()

    val physicalFiles = names.map { temporaryFolder.newFile("$it.kt") }
    val physicalDirs = names.map { temporaryFolder.newFolder("src_$it") }
    val virtualFiles = names.map {
      fileSystem.findFileByIoFile(temporaryFolder.newFile("v_$it.kt"))!!
    }
    val virtualDirs = names.map {
      fileSystem.findFileByIoFile(temporaryFolder.newFolder("vsrc_$it"))!!
    }

    val inputs = physicalFiles + physicalDirs + virtualFiles.map { it.asFile() } + virtualDirs.map { it.asFile() }

    val pathCollection = getSourceFilePaths(inputs, includeDirectoryRoot = true)

    assertThat(pathCollection.physicalFiles).containsExactlyElementsIn(physicalFiles.map { it.toPath() }).inOrder()
    assertThat(pathCollection.physicalDirectories).containsExactlyElementsIn(physicalDirs.map { it.toPath() }).inOrder()
    assertThat(pathCollection.virtualFiles).containsExactlyElementsIn(virtualFiles).inOrder()
    assertThat(pathCollection.virtualDirectories).containsExactlyElementsIn(virtualDirs).inOrder()
  }
}
