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

package com.android.builder.merge

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class FileMergerTest {

  @Test
  fun testFileMerger() {
    val i0 = FileMergerTestInput("i0")
    i0.add("file1")
    i0.add("file2")

    val i1 = FileMergerTestInput("i1")
    i1.add("file2")
    i1.add("file3")

    val output = mock(FileMergerOutput::class.java)
    `when`(output.open()).then {}
    `when`(output.close()).then {}

    val created = mutableListOf<CreateParams>()
    `when`(output.create(anyString(), anyList(), anyBoolean())).thenAnswer { invocation ->
      created.add(CreateParams(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))
      null
    }

    FileMerger.merge(listOf(i0, i1), output) { it == "file3" } // no compress for file3

    assertThat(created).hasSize(3)

    val createdMap = created.associateBy { it.path }

    assertThat(createdMap["file1"]?.inputs).containsExactly(i0)
    assertThat(createdMap["file1"]?.compress).isTrue()

    assertThat(createdMap["file2"]?.inputs).containsExactly(i0, i1).inOrder()
    assertThat(createdMap["file2"]?.compress).isTrue()

    assertThat(createdMap["file3"]?.inputs).containsExactly(i1)
    assertThat(createdMap["file3"]?.compress).isFalse()
  }

  private data class CreateParams(val path: String, val inputs: List<FileMergerInput>, val compress: Boolean)
}
