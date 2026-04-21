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

import com.google.common.collect.ImmutableSet
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class FilterFileMergerInputTest {

  @Test
  fun testFiltering() {
    val delegate = mock(FileMergerInput::class.java)
    `when`(delegate.getName()).thenReturn("delegate")
    `when`(delegate.getAllPaths()).thenReturn(ImmutableSet.of("accepted", "rejected"))

    val acceptedStream = ByteArrayInputStream(byteArrayOf(1))
    `when`(delegate.openPath("accepted")).thenReturn(acceptedStream)

    val filtered = FilterFileMergerInput(delegate, { it == "accepted" })

    assertThat(filtered.getName()).isEqualTo("delegate")
    assertThat(filtered.getAllPaths()).containsExactly("accepted")
    assertThat(filtered.openPath("accepted")).isSameInstanceAs(acceptedStream)
  }
}
