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
package com.android.tools.lint.checks.fx.utils

import com.google.common.truth.Truth
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Test

class PersistentCollectionUtilsTest {

  @Test
  fun `partitioning by class tag works`() {
    val s = persistentSetOf<Any?>("foo", 42, "bar", 120, null)
    val (strings, notStrings) = s.partitionIsInstanceOf<_, String>()
    Truth.assertThat(strings).isEqualTo(persistentSetOf("foo", "bar"))
    Truth.assertThat(notStrings).isEqualTo(persistentSetOf(42, 120, null))
  }

  @Test
  fun `partitioning to persistent sets works`() {
    val s = persistentSetOf(0, 1, 2, 3, 4)
    val (evens, odds) = s.partitionToPersistentSets { it % 2 == 0 }
    Truth.assertThat(evens).isEqualTo(persistentSetOf(0, 2, 4))
    Truth.assertThat(odds).isEqualTo(persistentSetOf(1, 3))
  }

  @Test
  fun `flat map to persistent sets works`() {
    val s = persistentSetOf(0, 10, 20)
    Truth.assertThat(s.flatMapToPersistentSet { listOf(it, it + 1) }).isEqualTo(persistentSetOf(0, 1, 10, 11, 20, 21))
  }

  @Test
  fun `map works on persistent set`() {
    Truth.assertThat(persistentSetOf("foo", "bar", "word").map(String::length)).isEqualTo(persistentSetOf(3, 4))
    Truth.assertThat(persistentSetOf<String>().map(String::length)).isEqualTo(persistentSetOf<Int>())
  }

  @Test
  fun `assoc to persistent map works`() {
    val m = persistentMapOf(0 to "foo", 1 to "bar")
    val d = listOf(1 to "qux", 2 to "word")
    Truth.assertThat(d.assoc(m) { it }).isEqualTo(persistentMapOf(0 to "foo", 1 to "qux", 2 to "word"))
  }

  @Test
  fun `mapping of values works on persistent map`() {
    val m = persistentMapOf(0 to "foo", 1 to "bar")
    Truth.assertThat(m.mapValues { _, w -> w.length }).isEqualTo(persistentMapOf(0 to 3, 1 to 3))
  }

  @Test
  fun `map merging works`() {
    Truth.assertThat(persistentMapOf(1 to "foo", 2 to "bar") + persistentMapOf(1 to "qux", 3 to "word"))
      .isEqualTo(persistentMapOf(1 to "qux", 2 to "bar", 3 to "word"))
  }
}
