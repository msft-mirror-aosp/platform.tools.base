/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.project.builder

import com.android.build.gradle.integration.common.fixture.project.reversible.ReversibleGradleProjectFiles
import com.android.testutils.truth.PathSubject
import kotlin.io.path.writeText
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.TemporaryFolder

class ReversibleGradleProjectFilesTest {
  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @get:Rule val exceptionRule: ExpectedException = ExpectedException.none()

  private lateinit var files: ReversibleGradleProjectFiles

  @Before
  fun setup() {
    val path = tempFolder.newFolder().toPath()
    files = ReversibleGradleProjectFiles(path)
  }

  @Test
  fun test_simple_add() {
    val currentFile = files.location.resolve("foo.txt")
    PathSubject.assertThat(currentFile).doesNotExist()

    files.add("foo.txt", "content")
    PathSubject.assertThat(currentFile).hasContents("content")

    files.revert()
    PathSubject.assertThat(currentFile).doesNotExist()
  }

  @Test
  fun test_already_existing_add() {
    val currentFile = files.location.resolve("foo.txt")
    currentFile.writeText("content")

    exceptionRule.expect(RuntimeException::class.java)
    files.add("foo.txt", "different content")
  }

  @Test
  fun test_add_and_remove() {
    val currentFile = files.location.resolve("foo.txt")
    PathSubject.assertThat(currentFile).doesNotExist()

    files.add("foo.txt", "content")
    PathSubject.assertThat(currentFile).hasContents("content")

    files.remove("foo.txt")

    files.revert()
    PathSubject.assertThat(currentFile).doesNotExist()
  }

  @Test
  fun test_simple_remove() {
    val currentFile = files.location.resolve("foo.txt")
    currentFile.writeText("content")

    files.remove("foo.txt")
    PathSubject.assertThat(currentFile).doesNotExist()

    files.revert()
    PathSubject.assertThat(currentFile).hasContents("content")
  }

  @Test
  fun test_lenient_remove() {
    val currentFile = files.location.resolve("foo.txt")
    PathSubject.assertThat(currentFile).doesNotExist()

    files.remove("foo.txt")
  }

  @Test
  fun test_simple_replaceWith() {
    val currentFile = files.location.resolve("foo.txt")
    currentFile.writeText("some content")

    files.update("foo.txt").replaceWith("new stuff")
    PathSubject.assertThat(currentFile).hasContents("new stuff")

    files.revert()
    PathSubject.assertThat(currentFile).hasContents("some content")
  }

  @Test
  fun test_simple_search_and_replace() {
    val currentFile = files.location.resolve("foo.txt")
    currentFile.writeText("some content")

    files.update("foo.txt").searchAndReplace("some", "new")
    PathSubject.assertThat(currentFile).hasContents("new content")

    files.revert()
    PathSubject.assertThat(currentFile).hasContents("some content")
  }

  @Test
  fun test_stacked_search_and_replace() {
    val currentFile = files.location.resolve("foo.txt")
    currentFile.writeText("some content")

    files.update("foo.txt") {
      searchAndReplace("some", "new")
      searchAndReplace("content", "stuff")
    }
    PathSubject.assertThat(currentFile).hasContents("new stuff")

    files.revert()
    PathSubject.assertThat(currentFile).hasContents("some content")
  }

  @Test
  fun test_simple_append() {
    val currentFile = files.location.resolve("foo.txt")
    currentFile.writeText("some content")

    files.update("foo.txt").append(" ... and some more")
    PathSubject.assertThat(currentFile).hasContents("some content ... and some more")

    files.revert()
    PathSubject.assertThat(currentFile).hasContents("some content")
  }
}
