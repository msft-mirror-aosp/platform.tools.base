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

package com.android.tools.ui.inspector.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParameterReferenceTest {

  @Test
  fun testEncodeJoinsAllFields() {
    val reference = ParameterReference(7L, 100L, ParameterReference.Kind.MERGED_SEMANTICS, 2, listOf(1, 3), startIndex = 5)

    assertThat(reference.encode()).isEqualTo("7:100:m:2:1.3:5")
  }

  @Test
  fun testEncodeKeepsNegativeIdsAndEmptyPath() {
    // Composable ids are often negative, and a reference to the parameter itself has no path.
    val reference = ParameterReference(-7L, -100L, ParameterReference.Kind.UNMERGED_SEMANTICS, 0, emptyList(), startIndex = 0)

    assertThat(reference.encode()).isEqualTo("-7:-100:u:0::0")
  }

  @Test
  fun testEncodeDistinguishesKinds() {
    val codes = ParameterReference.Kind.entries.map { ParameterReference(1L, 2L, it, 0, emptyList(), 0).encode().split(":")[2] }

    assertThat(codes).containsExactly("p", "m", "u").inOrder()
  }
}
