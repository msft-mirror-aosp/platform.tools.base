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

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PathSafetyTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun resolveContainedFile_returnsFileInsideBaseDir() {
    val baseDir = tempFolder.newFolder("base")

    assertThat(PathSafety.resolveContainedFile(baseDir, "nested/trace.pb")).isEqualTo(File(baseDir, "nested/trace.pb"))
  }

  @Test
  fun resolveContainedFile_returnsNullWhenPathEscapesBaseDir() {
    val baseDir = tempFolder.newFolder("base")

    assertThat(PathSafety.resolveContainedFile(baseDir, "../outside.pb")).isNull()
  }

  @Test
  fun resolveContainedFile_returnsNullForInvalidHostPath() {
    val baseDir = tempFolder.newFolder("base")

    // A NUL character is invalid on every host file system, like '?' is on Windows; resolving it must not throw.
    assertThat(PathSafety.resolveContainedFile(baseDir, "trace\u0000.pb")).isNull()
  }
}
