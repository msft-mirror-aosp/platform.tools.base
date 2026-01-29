/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.ide.common.gradle

import com.android.ide.common.gradle.RichVersion.Declaration
import com.android.ide.common.gradle.RichVersion.Kind.REQUIRE
import com.android.ide.common.gradle.RichVersion.Kind.STRICTLY
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RichVersionTest {
  @Test
  fun testParseAll() {
    for (string in listOf("+", "[,]", "],]", "(,]", "[,[", "],[", "(,[", "[,)", "],)", "(,)")) {
      val version = RichVersion.parse(string)
      assertThat(version.strictly).isNull()
      assertThat(version.require).isEqualTo(VersionRange.parse("+"))
      assertThat(version.prefer).isNull()
      assertThat(version.exclude).isEmpty()
      assertThat(version.toIdentifier()).isEqualTo(string)
      assertThat(version.toString()).isEqualTo(string)
    }
  }

  @Test
  fun testParseStrictAll() {
    for (string in listOf("+", "[,]", "],]", "(,]", "[,[", "],[", "(,[", "[,)", "],)", "(,)")) {
      val version = RichVersion.parse("$string!!")
      assertThat(version.strictly).isEqualTo(VersionRange.parse("+"))
      assertThat(version.require).isNull()
      assertThat(version.prefer).isNull()
      assertThat(version.exclude).isEmpty()
      assertThat(version.toIdentifier()).isEqualTo("${string}!!")
      assertThat(version.toString()).isEqualTo("${string}!!")
    }
  }

  @Test
  fun testParseMavenVersion() {
    val version = RichVersion.parse("[1.0,2.0)")
    assertThat(version.strictly).isNull()
    assertThat(version.require).isEqualTo(VersionRange.parse("[1.0,2.0)"))
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("[1.0,2.0)")
    assertThat(version.toString()).isEqualTo("[1.0,2.0)")
  }

  @Test
  fun testParseStrictMavenVersion() {
    val version = RichVersion.parse("[1.0,2.0)!!")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("[1.0,2.0)"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("[1.0,2.0)!!")
    assertThat(version.toString()).isEqualTo("[1.0,2.0)!!")
  }

  @Test
  fun testParsePrefixVersion() {
    val version = RichVersion.parse("1.0.+")
    assertThat(version.strictly).isNull()
    assertThat(version.require).isEqualTo(VersionRange.parse("1.0.+"))
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("1.0.+")
    assertThat(version.toString()).isEqualTo("1.0.+")
  }

  @Test
  fun testParseStrictPrefixVersion() {
    val version = RichVersion.parse("1.0.+!!")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("1.0.+"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("1.0.+!!")
    assertThat(version.toString()).isEqualTo("1.0.+!!")
  }

  @Test
  fun testParsePrefixVersionWithDifferentSeparator() {
    val version = RichVersion.parse("1.0-+")
    assertThat(version.strictly).isNull()
    assertThat(version.require).isEqualTo(VersionRange.parse("1.0.+"))
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("1.0-+")
    assertThat(version.toString()).isEqualTo("1.0-+")
  }

  @Test
  fun testParseStrictPrefixVersionWithDifferentSeparator() {
    val version = RichVersion.parse("1.0-+!!")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("1.0.+"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("1.0-+!!")
    assertThat(version.toString()).isEqualTo("1.0-+!!")
  }

  @Test
  fun testParseSingleVersion() {
    val version = RichVersion.parse("1.2.3")
    assertThat(version.strictly).isNull()
    assertThat(version.require).isEqualTo(VersionRange.parse("1.2.3"))
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("1.2.3")
    assertThat(version.toString()).isEqualTo("1.2.3")
  }

  @Test
  fun testParseStrictSingleVersion() {
    val version = RichVersion.parse("1.2.3!!")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("1.2.3"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("1.2.3!!")
    assertThat(version.toString()).isEqualTo("1.2.3!!")
  }

  @Test
  fun testParseAllWithPrefer() {
    val version = RichVersion.parse("+!!1.2.3")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("+"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isEqualTo(Version.parse("1.2.3"))
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("+!!1.2.3")
    assertThat(version.toString()).isEqualTo("+!!1.2.3")
  }

  @Test
  fun testParseMavenWithPrefer() {
    val version = RichVersion.parse("[1.0,2.0)!!1.2.3")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("[1.0,2.0)"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isEqualTo(Version.parse("1.2.3"))
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("[1.0,2.0)!!1.2.3")
    assertThat(version.toString()).isEqualTo("[1.0,2.0)!!1.2.3")
  }

  @Test
  fun testParsePrefixWithPrefer() {
    val version = RichVersion.parse("1.+!!1.2.3")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("1.+"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isEqualTo(Version.parse("1.2.3"))
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("1.+!!1.2.3")
    assertThat(version.toString()).isEqualTo("1.+!!1.2.3")
  }

  @Test
  fun testParseSingleWithPrefer() {
    // This is pretty nonsensical but syntactically valid
    val version = RichVersion.parse("1.2.3!!1.2.3")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("1.2.3"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isEqualTo(Version.parse("1.2.3"))
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("1.2.3!!1.2.3")
    assertThat(version.toString()).isEqualTo("1.2.3!!1.2.3")
  }

  @Test
  fun testParseEmpty() {
    val version = RichVersion.parse("")
    assertThat(version.strictly).isNull()
    assertThat(version.require).isEqualTo(VersionRange.parse(""))
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("")
    assertThat(version.toString()).isEqualTo("")
  }

  @Test
  fun testParseEmptyWithPrefer() {
    // This is not actually a good idea, but let's capture the current behaviour
    val version = RichVersion.parse("!!1.2.3")
    assertThat(version.strictly).isEqualTo(VersionRange.parse(""))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isEqualTo(Version.parse("1.2.3"))
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("!!1.2.3")
    assertThat(version.toString()).isEqualTo("!!1.2.3")
  }

  @Test
  fun testParseMultipleBangs() {
    // Again, not a good idea, but let's capture the current behaviour
    val version = RichVersion.parse("12!!34!!56")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("12"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isEqualTo(Version.parse("34!!56"))
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("12!!34!!56")
    assertThat(version.toString()).isEqualTo("12!!34!!56")
  }

  @Test
  fun testRequireVersion() {
    val version = RichVersion.require(Version.parse("12.34"))
    assertThat(version.strictly).isNull()
    assertThat(version.require).isEqualTo(VersionRange.parse("12.34"))
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("12.34")
    assertThat(version.toString()).isEqualTo("12.34")
  }

  @Test
  fun testRequireVersionRange() {
    val version = RichVersion.require(VersionRange.parse("[12.34,56.78)"))
    assertThat(version.strictly).isNull()
    assertThat(version.require).isEqualTo(VersionRange.parse("[12.34,56.78)"))
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("[12.34,56.78)")
    assertThat(version.toString()).isEqualTo("[12.34,56.78)")
  }

  @Test
  fun testStrictlyVersion() {
    val version = RichVersion.strictly(Version.parse("12.34"))
    assertThat(version.strictly).isEqualTo(VersionRange.parse("12.34"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("12.34!!")
    assertThat(version.toString()).isEqualTo("12.34!!")
  }

  @Test
  fun testStrictlyVersionRange() {
    val version = RichVersion.strictly(VersionRange.parse("[12.34,56.78)"))
    assertThat(version.strictly).isEqualTo(VersionRange.parse("[12.34,56.78)"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("[12.34,56.78)!!")
    assertThat(version.toString()).isEqualTo("[12.34,56.78)!!")
  }

  @Test
  fun testRequiredContains() {
    RichVersion.parse("1.2.3").let { version ->
      assertThat(version.contains(Version.parse("1"))).isFalse()
      assertThat(version.contains(Version.parse("1.2"))).isFalse()
      assertThat(version.contains(Version.parse("1.2.2"))).isFalse()
      assertThat(version.contains(Version.parse("1.2.3"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.4"))).isFalse()
      assertThat(version.contains(Version.parse("1.3"))).isFalse()
      assertThat(version.contains(Version.parse("2"))).isFalse()
    }
    RichVersion.parse("1.2.+").let { version ->
      assertThat(version.contains(Version.parse("1"))).isFalse()
      assertThat(version.contains(Version.parse("1.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.3"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.4"))).isTrue()
      assertThat(version.contains(Version.parse("1.3"))).isFalse()
      assertThat(version.contains(Version.parse("2"))).isFalse()
    }
    RichVersion.parse("[1.2,1.3]").let { version ->
      assertThat(version.contains(Version.parse("1"))).isFalse()
      assertThat(version.contains(Version.parse("1.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.3"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.4"))).isTrue()
      assertThat(version.contains(Version.parse("1.3"))).isTrue()
      assertThat(version.contains(Version.parse("2"))).isFalse()
    }
  }

  @Test
  fun testStrictlyContains() {
    RichVersion.parse("1.2.3!!").let { version ->
      assertThat(version.contains(Version.parse("1"))).isFalse()
      assertThat(version.contains(Version.parse("1.2"))).isFalse()
      assertThat(version.contains(Version.parse("1.2.2"))).isFalse()
      assertThat(version.contains(Version.parse("1.2.3"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.4"))).isFalse()
      assertThat(version.contains(Version.parse("1.3"))).isFalse()
      assertThat(version.contains(Version.parse("2"))).isFalse()
    }
    RichVersion.parse("1.2.+!!").let { version ->
      assertThat(version.contains(Version.parse("1"))).isFalse()
      assertThat(version.contains(Version.parse("1.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.3"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.4"))).isTrue()
      assertThat(version.contains(Version.parse("1.3"))).isFalse()
      assertThat(version.contains(Version.parse("2"))).isFalse()
    }
    RichVersion.parse("[1.2,1.3]!!").let { version ->
      assertThat(version.contains(Version.parse("1"))).isFalse()
      assertThat(version.contains(Version.parse("1.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.3"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.4"))).isTrue()
      assertThat(version.contains(Version.parse("1.3"))).isTrue()
      assertThat(version.contains(Version.parse("2"))).isFalse()
    }
  }

  @Test
  fun testContainsWithExcludes() {
    RichVersion(Declaration(REQUIRE, VersionRange.parse("1.2.3")), exclude = listOf(VersionRange.parse("1.2.4"))).let { version ->
      assertThat(version.contains(Version.parse("1"))).isFalse()
      assertThat(version.contains(Version.parse("1.2"))).isFalse()
      assertThat(version.contains(Version.parse("1.2.2"))).isFalse()
      assertThat(version.contains(Version.parse("1.2.3"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.4"))).isFalse()
      assertThat(version.contains(Version.parse("1.3"))).isFalse()
      assertThat(version.contains(Version.parse("2"))).isFalse()
    }
    RichVersion(Declaration(REQUIRE, VersionRange.parse("1.2.+")), exclude = listOf(VersionRange.parse("1.2.4"))).let { version ->
      assertThat(version.contains(Version.parse("1"))).isFalse()
      assertThat(version.contains(Version.parse("1.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.3"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.4"))).isFalse()
      assertThat(version.contains(Version.parse("1.3"))).isFalse()
      assertThat(version.contains(Version.parse("2"))).isFalse()
    }
    RichVersion(Declaration(REQUIRE, VersionRange.parse("[1.2,1.3]")), exclude = listOf(VersionRange.parse("1.2.4"))).let { version ->
      assertThat(version.contains(Version.parse("1"))).isFalse()
      assertThat(version.contains(Version.parse("1.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.2"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.3"))).isTrue()
      assertThat(version.contains(Version.parse("1.2.4"))).isFalse()
      assertThat(version.contains(Version.parse("1.3"))).isTrue()
      assertThat(version.contains(Version.parse("2"))).isFalse()
    }
  }

  @Test
  fun testRequiredAccepts() {
    RichVersion.parse("1.2.3").let { version ->
      assertThat(version.accepts(Version.parse("1"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2.2"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2.3"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.4"))).isTrue()
      assertThat(version.accepts(Version.parse("1.3"))).isTrue()
      assertThat(version.accepts(Version.parse("2"))).isTrue()
    }
    RichVersion.parse("1.2.+").let { version ->
      assertThat(version.accepts(Version.parse("1"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.3"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.4"))).isTrue()
      assertThat(version.accepts(Version.parse("1.3"))).isTrue()
      assertThat(version.accepts(Version.parse("2"))).isTrue()
    }
    RichVersion.parse("[1.2,1.3]").let { version ->
      assertThat(version.accepts(Version.parse("1"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.3"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.4"))).isTrue()
      assertThat(version.accepts(Version.parse("1.3"))).isTrue()
      assertThat(version.accepts(Version.parse("2"))).isTrue()
    }
  }

  @Test
  fun testStrictlyAccepts() {
    RichVersion.parse("1.2.3!!").let { version ->
      assertThat(version.accepts(Version.parse("1"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2.2"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2.3"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.4"))).isFalse()
      assertThat(version.accepts(Version.parse("1.3"))).isFalse()
      assertThat(version.accepts(Version.parse("2"))).isFalse()
    }
    RichVersion.parse("1.2.+!!").let { version ->
      assertThat(version.accepts(Version.parse("1"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.3"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.4"))).isTrue()
      assertThat(version.accepts(Version.parse("1.3"))).isFalse()
      assertThat(version.accepts(Version.parse("2"))).isFalse()
    }
    RichVersion.parse("[1.2,1.3]!!").let { version ->
      assertThat(version.accepts(Version.parse("1"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.3"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.4"))).isTrue()
      assertThat(version.accepts(Version.parse("1.3"))).isTrue()
      assertThat(version.accepts(Version.parse("2"))).isFalse()
    }
  }

  @Test
  fun testAcceptsWithExcludes() {
    RichVersion(Declaration(REQUIRE, VersionRange.parse("1.2.3")), exclude = listOf(VersionRange.parse("1.2.4"))).let { version ->
      assertThat(version.accepts(Version.parse("1"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2.2"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2.3"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.4"))).isFalse()
      assertThat(version.accepts(Version.parse("1.3"))).isTrue()
      assertThat(version.accepts(Version.parse("2"))).isTrue()
    }
    RichVersion(Declaration(REQUIRE, VersionRange.parse("1.2.+")), exclude = listOf(VersionRange.parse("1.2.4"))).let { version ->
      assertThat(version.accepts(Version.parse("1"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.3"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.4"))).isFalse()
      assertThat(version.accepts(Version.parse("1.3"))).isTrue()
      assertThat(version.accepts(Version.parse("2"))).isTrue()
    }
    RichVersion(Declaration(REQUIRE, VersionRange.parse("[1.2,1.3]")), exclude = listOf(VersionRange.parse("1.2.4"))).let { version ->
      assertThat(version.accepts(Version.parse("1"))).isFalse()
      assertThat(version.accepts(Version.parse("1.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.2"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.3"))).isTrue()
      assertThat(version.accepts(Version.parse("1.2.4"))).isFalse()
      assertThat(version.accepts(Version.parse("1.3"))).isTrue()
      assertThat(version.accepts(Version.parse("2"))).isTrue()
    }
  }

  @Test
  fun testExplicitSingletonFalse() {
    for (string in listOf("+", "1.+", "[1,2]")) {
      for (suffix in listOf("", "!!", "!!1.0")) {
        val version = RichVersion.parse("$string$suffix")
        assertThat(version.isExplicitSingleton).isFalse()
        assertThat(version.explicitSingletonVersion).isNull()
      }
    }
  }

  @Test
  fun testExplicitSingletonTrue() {
    for (string in listOf("1.0", "[1.0,1.0]")) {
      for (suffix in listOf("", "!!", "!!1.0")) {
        val version = RichVersion.parse("$string$suffix")
        assertThat(version.isExplicitSingleton).isTrue()
        assertThat(version.explicitSingletonVersion).isEqualTo(Version.parse("1.0"))
      }
    }
  }

  @Test
  fun testExplicitSingletonWithDistinctExcludes() {
    val version = RichVersion(declaration = Declaration(REQUIRE, VersionRange.parse("1.0")), exclude = listOf(VersionRange.parse("2.+")))
    assertThat(version.isExplicitSingleton).isTrue()
    assertThat(version.explicitSingletonVersion).isEqualTo(Version.parse("1.0"))
  }

  @Test
  fun testExplicitSingletonWithOverlappingExcludes() {
    val version = RichVersion(declaration = Declaration(REQUIRE, VersionRange.parse("1.0")), exclude = listOf(VersionRange.parse("1.+")))
    assertThat(version.isExplicitSingleton).isFalse()
    assertThat(version.explicitSingletonVersion).isNull()
  }

  @Test
  fun testExplicitSingletonWithSingletonByConstruction() {
    // This is unlikely to be exposed in production, and is also allowed by the "explicit"
    // qualifier of this field name.  However, if singleton detection is made cleverer for
    // whatever reason, verify also that the singleton version produced is correct.
    val version1 =
      RichVersion(
        declaration = Declaration(STRICTLY, VersionRange.parse("[1,2]")),
        exclude = listOf(VersionRange(Range.greaterThan(Version.parse("1")))),
      )
    val version2 =
      RichVersion(
        declaration = Declaration(STRICTLY, VersionRange.parse("[1,2]")),
        exclude = listOf(VersionRange(Range.lessThan(Version.parse("2")))),
      )
    if (version1.isExplicitSingleton) {
      assertThat(version1.explicitSingletonVersion).isEqualTo(Version.parse("1"))
    }
    if (version2.isExplicitSingleton) {
      assertThat(version2.explicitSingletonVersion).isEqualTo(Version.parse("2"))
    }
  }

  @Test
  fun testLowerBound() {
    val tests =
      listOf(
        "1" to Version.parse("1"),
        "1!!" to Version.parse("1"),
        "[1,2]" to Version.parse("1"),
        "[1,2]!!" to Version.parse("1"),
        "[1,2]!!1.5" to Version.parse("1"),
        "1.0" to Version.parse("1.0"),
        "1.0!!" to Version.parse("1.0"),
        "1.+" to Version.prefixInfimum("1"),
        "[,2]" to Version.prefixInfimum("dev"),
      )
    for ((string, expected) in tests) {
      val richVersion = RichVersion.parse(string)
      assertThat(richVersion.lowerBound).isEqualTo(expected)
    }
  }

  @Test
  fun testLowerBoundWithExcludes() {
    val boundNotExcluded =
      RichVersion(declaration = Declaration(REQUIRE, VersionRange.parse("1.0")), exclude = listOf(VersionRange.parse("[1.1,1.5]")))
    assertThat(boundNotExcluded.lowerBound).isEqualTo(Version.parse("1.0"))
    // This is correct for the documentation of lowerBound as currently written, though it's
    // conceivable that there might be an application for making it handle exclude entries.
    // If so, change this test.
    val boundExcluded =
      RichVersion(declaration = Declaration(REQUIRE, VersionRange.parse("1.2")), exclude = listOf(VersionRange.parse("[1.1,1.5]")))
    assertThat(boundExcluded.lowerBound).isEqualTo(Version.parse("1.2"))
  }

  @Test
  fun testNoIdentifierForRequireWithBangs() {
    val range = VersionRange.parse("12!!34")
    // precondition for this test
    assertThat(range.toIdentifier()).isEqualTo("12!!34")
    val version = RichVersion(declaration = Declaration(REQUIRE, range))
    assertThat(version.toIdentifier()).isNull()
    assertThat(version.toString()).matches("^RichVersion\\(.*\\)$")
  }

  @Test
  fun testNoIdentifierForRequireWithPrefer() {
    val range = VersionRange.parse("[1.0,2.0)")
    val version = RichVersion(declaration = Declaration(REQUIRE, range), prefer = Version.parse("1.2.3"))
    assertThat(version.toIdentifier()).isNull()
    assertThat(version.toString()).matches("^RichVersion\\(.*\\)$")
  }

  @Test
  fun testNoIdentifierForStrictlyWithBangs() {
    val range = VersionRange.parse("12!!34")
    // precondition for this test
    assertThat(range.toIdentifier()).isEqualTo("12!!34")
    val version = RichVersion(declaration = Declaration(RichVersion.Kind.STRICTLY, range))
    assertThat(version.toIdentifier()).isNull()
    assertThat(version.toString()).matches("^RichVersion\\(.*\\)$")
  }

  @Test
  fun testNoIdentifierForRequiredBeginningWithDoubleBang() {
    // if we parse Rich Version declaration strings beginning with !! as implying an empty
    // strict version, we cannot make an identifier for a Rich Version declaration with a
    // required version beginning with two exclamation marks.
    val doubleBangRange = VersionRange(Range.singleton(Version.parse("!!123")))
    // precondition for this test
    assertThat(doubleBangRange.toIdentifier()).isEqualTo("!!123")
    val version = RichVersion(declaration = Declaration(REQUIRE, doubleBangRange))
    assertThat(version.toIdentifier()).isNull()
    assertThat(version.toString()).matches("^RichVersion\\(.*\\)$")
  }

  @Test
  fun testNoIdentifierForEmptyPrefer() {
    val range = VersionRange.parse("[1.0,2.0)")
    val version = RichVersion(declaration = Declaration(RichVersion.Kind.STRICTLY, range), prefer = Version.parse(""))
    assertThat(version.toIdentifier()).isNull()
    assertThat(version.toString()).matches("^RichVersion\\(.*\\)$")
  }

  @Test
  fun testNoIdentifierWithExcludes() {
    val range = VersionRange.parse("[1.0,2.0)")
    for (kind in RichVersion.Kind.values()) {
      val version = RichVersion(declaration = Declaration(kind, range), exclude = listOf(VersionRange.parse("1.2.3")))
      assertThat(version.toIdentifier()).isNull()
      assertThat(version.toString()).matches("^RichVersion\\(.*\\)$")
    }
  }

  // Test cases from https://maven.apache.org/pom.html#Dependency_Version_Requirement_Specification
  @Test
  fun testFromPomVersion1() {
    val version = RichVersion.fromPomVersion("1.0")
    assertThat(version.strictly).isNull()
    assertThat(version.require).isEqualTo(VersionRange.parse("[1.0,1.0]"))
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("1.0")
    assertThat(version.toString()).isEqualTo("1.0")
  }

  @Test
  fun testFromPomVersion2() {
    val version = RichVersion.fromPomVersion("[1.0]")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("[1.0,1.0]"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("1.0!!")
    assertThat(version.toString()).isEqualTo("1.0!!")
  }

  @Test
  fun testFromPomVersion3() {
    val version = RichVersion.fromPomVersion("(,1.0]")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("(,1.0]"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("(,1.0]!!")
    assertThat(version.toString()).isEqualTo("(,1.0]!!")
  }

  @Test
  fun testFromPomVersion4() {
    val version = RichVersion.fromPomVersion("[1.2,1.3]")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("[1.2,1.3]"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("[1.2,1.3]!!")
    assertThat(version.toString()).isEqualTo("[1.2,1.3]!!")
  }

  @Test
  fun testFromPomVersion5() {
    val version = RichVersion.fromPomVersion("[1.2,2.0)")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("[1.2,2.0)"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("[1.2,2.0)!!")
    assertThat(version.toString()).isEqualTo("[1.2,2.0)!!")
  }

  @Test
  fun testFromPomVersion6() {
    val version = RichVersion.fromPomVersion("[1.5,)")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("[1.5,)"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    assertThat(version.exclude).isEmpty()
    assertThat(version.toIdentifier()).isEqualTo("[1.5,)!!")
    assertThat(version.toString()).isEqualTo("[1.5,)!!")
  }

  @Test
  fun testFromPomVersion7() {
    val version = RichVersion.fromPomVersion("(,1.0],[1.2,)")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("(,)"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    // Beware: this is not the same as VersionRange.parse("(1.0,1.2)")
    assertThat(version.exclude).containsExactly(VersionRange(Range.open(Version.parse("1.0"), Version.parse("1.2"))))
    assertThat(version.toIdentifier()).isNull()
    assertThat(version.toString()).startsWith("RichVersion")
  }

  @Test
  fun testFromPomVersion8() {
    val version = RichVersion.fromPomVersion("(,1.1),(1.1,)")
    assertThat(version.strictly).isEqualTo(VersionRange.parse("(,)"))
    assertThat(version.require).isNull()
    assertThat(version.prefer).isNull()
    // Beware: this is not the same as VersionRange.parse("1.1") or .parse("[1.1,1.1]")
    assertThat(version.exclude).containsExactly(VersionRange(Range.closed(Version.prefixInfimum("1.1"), Version.parse("1.1"))))
    assertThat(version.toIdentifier()).isNull()
    assertThat(version.toString()).startsWith("RichVersion")
  }

  // Test cases derived from the behavior of maven-artifact/.../VersionRange.java

  @Test
  fun testFromPomVersionLargerRangeSets() {
    RichVersion.fromPomVersion("[1,2],(3,4],(5,6]").let { version ->
      assertThat(version.strictly).isEqualTo(VersionRange.parse("[1,6]"))
      assertThat(version.exclude).containsExactly(VersionRange.parse("(2,3]"), VersionRange.parse("(4,5]"))
    }
  }

  @Test
  fun testFromPomVersionWhitespace() {
    // trailing whitespace is apparently OK in all cases.
    RichVersion.fromPomVersion("1.0 ").let { version -> assertThat(version.require).isEqualTo(VersionRange.parse("1.0")) }
    RichVersion.fromPomVersion("[1.0] ").let { version -> assertThat(version.strictly).isEqualTo(VersionRange.parse("1.0")) }
    // leading whitespace is not trimmed...
    RichVersion.fromPomVersion(" 1.0").let { version -> assertThat(version.require).isEqualTo(VersionRange.parse(" 1.0")) }
    // ... meaning that the special treatment of [ only applies in position 0
    RichVersion.fromPomVersion(" [1.0]").let { version -> assertThat(version.require).isEqualTo(VersionRange.parse(" [1.0]")) }
    // whitespace is trimmed from start and end of the singleton version
    RichVersion.fromPomVersion("[ 1.0 ] ").let { version -> assertThat(version.strictly).isEqualTo(VersionRange.parse("1.0")) }
    // whitespace is trimmed from both sides of each endpoint version
    RichVersion.fromPomVersion("[1,2] , (3 , 4 ] , ( 5, 6 ] ").let { version ->
      assertThat(version.strictly).isEqualTo(VersionRange.parse("[1,6]"))
      assertThat(version.exclude).containsExactly(VersionRange.parse("(2,3]"), VersionRange.parse("(4,5]"))
    }
    // whitespace is as defined by Java String.trim() so is codepoints equal to or below U+0020
    RichVersion.fromPomVersion("[\u00001.2\u0008,\u00103.4\u0018]\u0020").let { version ->
      assertThat(version).isEqualTo(RichVersion.fromPomVersion("[1.2,3.4]"))
    }
    RichVersion.fromPomVersion("[\u00a01.2\u2002,\u20033.4\u205f]").let { version ->
      assertThat(version).isEqualTo(RichVersion.parse("[\u00a01.2\u2002,\u20033.4\u205f]!!"))
    }
    // whitespace within version numbers is not trimmed
    RichVersion.fromPomVersion("[1 .2, 3. 4] ").let { version -> assertThat(version).isEqualTo(RichVersion.parse("[1 .2,3. 4]!!")) }
  }

  @Test
  fun testFromPomVersionMultipleRangesWithoutCommasWorkActually() {
    RichVersion.fromPomVersion("[1,2](3,4)").let { version -> assertThat(version).isEqualTo(RichVersion.fromPomVersion("[1,2],(3,4)")) }
  }

  @Test
  fun testFromPomVersionMultipleRangesOrderIndependent() {
    fun <T> List<T>.permutations(): List<List<T>> =
      when {
        isEmpty() -> listOf(listOf())
        else -> dropLast(1).permutations().flatMap { p -> (0..(p.size)).map { i -> p.take(i) + last() + p.drop(i) } }
      }
    val expected = RichVersion.fromPomVersion("[1,2],[3,4],[5,6]")
    listOf("[1,2]", "[3,4]", "[5,6]").permutations().forEach { p ->
      val version = RichVersion.fromPomVersion(p.joinToString(","))
      assertThat(version).isEqualTo(expected)
    }
  }

  @Test
  fun testFromPomVersionExplicitSingleton() {
    RichVersion.fromPomVersion("[1.0,1.0]").let { version -> assertThat(version).isEqualTo(RichVersion.fromPomVersion("[1.0]")) }
  }

  @Test
  fun testFromPomVersionDoesNotError() {
    val invalids = listOf("[1,1)", "(1,1)", "[3,2]", "[1", "2]", "[[3]]", "[4],", "[]", "[1)", "[1,,2]")
    invalids.forEach {
      val version = RichVersion.fromPomVersion(it)
      assertThat(version).isInstanceOf(RichVersion::class.java)
    }
    invalids.forEach { i1 ->
      invalids.forEach { i2 ->
        RichVersion.fromPomVersion("$i1,$i2").let { version -> assertThat(version).isInstanceOf(RichVersion::class.java) }
        RichVersion.fromPomVersion("$i1$i2").let { version -> assertThat(version).isInstanceOf(RichVersion::class.java) }
      }
    }
  }
}
