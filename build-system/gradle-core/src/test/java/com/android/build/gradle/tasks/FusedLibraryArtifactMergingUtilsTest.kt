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

package com.android.build.gradle.tasks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

internal class FusedLibraryArtifactMergingUtilsTest {

  @Test
  fun testDesugarJdkLibIdMerging_emptyList() {
    val result = buildDesugaredJdkLibCoordinate(emptyList())
    assertThat(result).isNull()
  }

  @Test
  fun testDesugarJdkLibIdMerging_singleElement() {
    val result = buildDesugaredJdkLibCoordinate(listOf("com.android.tools:desugar_jdk_libs:1.0.0"))
    assertThat(result).isEqualTo("com.android.tools:desugar_jdk_libs:1.0.0")
  }

  @Test
  fun testDesugarJdkLibIdMerging_multipleElements() {
    val result =
      buildDesugaredJdkLibCoordinate(listOf("com.android.tools:desugar_jdk_libs:1.0.0", "com.android.tools:desugar_jdk_libs:1.1.0"))
    assertThat(result).isEqualTo("com.android.tools:desugar_jdk_libs:1.1.0")
  }

  @Test
  fun testDesugarJdkLibIdMerging_multipleElementsWithDifferentArtifactIds() {
    val result =
      buildDesugaredJdkLibCoordinate(listOf("com.android.tools:desugar_jdk_libs:1.0.0", "com.android.tools:desugar_jdk_libs_nio:1.0.0"))
    assertThat(result).isEqualTo("com.android.tools:desugar_jdk_libs_nio:1.0.0")
  }

  @Test
  fun testDesugarJdkLibIdMerging_multipleElementsWithDifferentArtifactIds_and_selects_highestVersion() {
    val result =
      buildDesugaredJdkLibCoordinate(listOf("com.android.tools:desugar_jdk_libs:1.1.0", "com.android.tools:desugar_jdk_libs_nio:1.0.0"))
    assertThat(result).isEqualTo("com.android.tools:desugar_jdk_libs_nio:1.1.0")
  }

  @Test
  fun testDesugarLibIdMerging_invalidCoordinate_noVersion() {
    var result =
      buildDesugaredJdkLibCoordinate(listOf("com.android.tools:desugar_jdk_libs", "com.android.tools:desugar_jdk_libs_nio:1.0.0"))
    assertThat(result).isEqualTo("com.android.tools:desugar_jdk_libs_nio:1.0.0")
    result = buildDesugaredJdkLibCoordinate(listOf("com.android.tools:desugar_jdk_libs"))
    assertThat(result).isEqualTo(null)
  }
}
