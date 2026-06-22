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

package com.android.tools.coverage.reporter

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SmapResolverTest {

  @Test
  fun testSimpleMapping() {
    val smap =
      """
      SMAP
      MyClass.kt
      Kotlin
      *S Kotlin
      *F
      + 1 MyClass.kt
      com/example/MyClass.kt
      *L
      10#1,5:100
      *E
      """
        .trimIndent()

    val resolver = SmapResolver(smap)

    assertThat(resolver.resolve(99, "Unknown.kt")).isEqualTo(Pair(99, "Unknown.kt"))
    // Bytecode line 100 -> Source line 10
    assertThat(resolver.resolve(100, "Unknown.kt")).isEqualTo(Pair(10, "MyClass.kt"))
    assertThat(resolver.resolve(101, "Unknown.kt")).isEqualTo(Pair(11, "MyClass.kt"))
    assertThat(resolver.resolve(102, "Unknown.kt")).isEqualTo(Pair(12, "MyClass.kt"))
    // Bytecode line 104 -> Source line 14
    assertThat(resolver.resolve(104, "Unknown.kt")).isEqualTo(Pair(14, "MyClass.kt"))
    // Bytecode line 105 (no mapping) -> Source line 105 (identity)
    assertThat(resolver.resolve(105, "Unknown.kt")).isEqualTo(Pair(105, "Unknown.kt"))
  }

  @Test
  fun testManyToOneMapping() {
    val smap =
      """
      SMAP
      MyClass.kt
      Kotlin
      *S Kotlin
      *F
      + 1 MyClass.kt
      com/example/MyClass.kt
      *L
      11:101,2
      *E
      """
        .trimIndent()

    val resolver = SmapResolver(smap)

    // Both bytecode lines 101 and 102 should map back to source line 11
    assertThat(resolver.resolve(101, "MyClass.kt")).isEqualTo(Pair(11, "MyClass.kt"))
    assertThat(resolver.resolve(102, "MyClass.kt")).isEqualTo(Pair(11, "MyClass.kt"))
  }

  @Test
  fun testInlineMapping() {
    val smap =
      """
      SMAP
      Main.kt
      Kotlin
      *S Kotlin
      *F
      + 1 Main.kt
      com/example/Main.kt
      + 2 Utils.kt
      com/example/Utils.kt
      *L
      1#1,10:1
      5#2,3:11
      11#1,5:14
      *E
      """
        .trimIndent()

    val resolver = SmapResolver(smap)

    assertThat(resolver.resolve(1, "Main.kt")).isEqualTo(Pair(1, "Main.kt"))
    // Standard line in Main.kt
    assertThat(resolver.resolve(5, "Main.kt")).isEqualTo(Pair(5, "Main.kt"))
    assertThat(resolver.resolve(10, "Main.kt")).isEqualTo(Pair(10, "Main.kt"))

    // Inline line from Utils.kt (mapped to virtual line 11-13)
    assertThat(resolver.resolve(11, "Main.kt")).isEqualTo(Pair(5, "Utils.kt"))
    assertThat(resolver.resolve(12, "Main.kt")).isEqualTo(Pair(6, "Utils.kt"))
    assertThat(resolver.resolve(13, "Main.kt")).isEqualTo(Pair(7, "Utils.kt"))

    // Back to Main.kt
    assertThat(resolver.resolve(14, "Main.kt")).isEqualTo(Pair(11, "Main.kt"))
    assertThat(resolver.resolve(15, "Main.kt")).isEqualTo(Pair(12, "Main.kt"))
    assertThat(resolver.resolve(18, "Main.kt")).isEqualTo(Pair(15, "Main.kt"))
  }

  @Test
  fun testKotlinDebugSectionIgnored() {
    val smap =
      """
      SMAP
      MyClass.kt
      Kotlin
      *S Kotlin
      *F
      + 1 MyClass.kt
      com/example/MyClass.kt
      *L
      10#1:100
      *S KotlinDebug
      *F
      + 1 MyClass.kt
      com/example/MyClass.kt
      *L
      100#1:500
      *E
      """
        .trimIndent()

    val resolver = SmapResolver(smap)

    // Should resolve line 100 (from Kotlin section)
    assertThat(resolver.resolve(100, "MyClass.kt")).isEqualTo(Pair(10, "MyClass.kt"))
    // Should NOT resolve line 500 (from KotlinDebug section)
    assertThat(resolver.resolve(500, "MyClass.kt")).isEqualTo(Pair(500, "MyClass.kt"))
  }
}
