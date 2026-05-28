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
package com.android.template.engine.impl

import com.google.common.truth.Truth
import org.junit.Test

@Suppress("FunctionName")
class GlobToRegexTest {
  @Test
  fun testSimpleMatch() {
    val glob = "foobar.kt"
    val value = "foobar"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isFalse()
  }

  @Test
  fun testSimpleMatch_positive() {
    val glob = "foobar.kt"
    val value = "foobar.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isTrue()
  }

  @Test
  fun testWildcardMatch() {
    val glob = "foo*.kt"
    val value = "foobar.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isTrue()
  }

  @Test
  fun testWildcardMatch_negative() {
    val glob = "foo*.kt"
    val value = "foobar.java"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isFalse()
  }

  @Test
  fun testDoubleWildcardMatch() {
    val glob = "foo/**/bar.kt"
    val value = "foo/a/b/bar.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isTrue()
  }

  @Test
  fun testDoubleWildcardMatch_negative() {
    val glob = "foo/**/bar.kt"
    val value = "foo/a/b/baz.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isFalse()
  }

  @Test
  fun testQuestionMarkMatch() {
    val glob = "foo?.kt"
    val value = "foob.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isTrue()
  }

  @Test
  fun testQuestionMarkMatch_negative() {
    val glob = "foo?.kt"
    val value = "foobar.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isFalse()
  }

  @Test
  fun testCharacterClassMatch() {
    val glob = "foo[a-z].kt"
    val value = "foob.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isTrue()
  }

  @Test
  fun testCharacterClassMatch_negative() {
    val glob = "foo[a-z].kt"
    val value = "foo1.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isFalse()
  }

  @Test
  fun testNegatedCharacterClassMatch() {
    val glob = "foo[^a-z].kt"
    val value = "foo1.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isTrue()
  }

  @Test
  fun testNegatedCharacterClassMatch_negative() {
    val glob = "foo[^a-z].kt"
    val value = "foob.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isFalse()
  }

  @Test
  fun testEscapedWildcardMatch() {
    val glob = "foo\\*.kt"
    val value = "foo*.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isTrue()
  }

  @Test
  fun testEscapedWildcardMatch_negative() {
    val glob = "foo\\*.kt"
    val value = "foobar.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isFalse()
  }

  @Test
  fun testEscapedQuestionMarkMatch() {
    val glob = "foo\\?.kt"
    val value = "foo?.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isTrue()
  }

  @Test
  fun testEscapedQuestionMarkMatch_negative() {
    val glob = "foo\\?.kt"
    val value = "foob.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isFalse()
  }

  @Test
  fun testEscapedCharacterClassMatch() {
    val glob = "foo\\[a-z\\].kt"
    val value = "foo[a-z].kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isTrue()
  }

  @Test
  fun testEscapedCharacterClassMatch_negative() {
    val glob = "foo\\[a-z\\].kt"
    val value = "foob.kt"
    val regex = GlobToRegex.parseGlobPattern(glob)

    Truth.assertThat(regex.matches(value)).isFalse()
  }

  @Test
  fun testCaseInsensitiveMatch() {
    val glob = "FooBar.kt"
    val value = "foobar.kt"
    val regex = GlobToRegex.parseGlobPattern(glob, caseSensitive = false)

    Truth.assertThat(regex.matches(value)).isTrue()
  }

  @Test
  fun testCaseInsensitiveMatch_negative() {
    val glob = "FooBar.kt"
    val value = "foobar.kt"
    val regex = GlobToRegex.parseGlobPattern(glob, caseSensitive = true)

    Truth.assertThat(regex.matches(value)).isFalse()
  }
}
