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
import org.junit.Assert.assertThrows
import org.junit.Test

class ParameterReferenceTest {

  @Test
  fun testEncodeJoinsAllFields() {
    val reference = ParameterReference(7L, 100L, ParameterReference.Kind.MERGED_SEMANTICS, 2, listOf(1, 3), startIndex = 5, valueHash = 35)

    assertThat(reference.encode()).isEqualTo("7:100:m:2:1.3:5:z")
  }

  @Test
  fun testEncodeKeepsNegativeIdsAndEmptyPath() {
    // Composable ids are often negative, and a reference to the parameter itself has no path.
    val reference =
      ParameterReference(-7L, -100L, ParameterReference.Kind.UNMERGED_SEMANTICS, 0, emptyList(), startIndex = 0, valueHash = 0)

    assertThat(reference.encode()).isEqualTo("-7:-100:u:0::0:0")
  }

  @Test
  fun testEncodeWritesValueHashAsUnsignedNumber() {
    // A negative hash has no sign in the token, so that the token keeps the same few characters.
    val reference = ParameterReference(1L, 2L, ParameterReference.Kind.PARAMETERS, 0, emptyList(), startIndex = 0, valueHash = -1)

    assertThat(reference.encode()).isEqualTo("1:2:p:0::0:1z141z3")
  }

  @Test
  fun testParseReversesEncode() {
    val references =
      ParameterReference.Kind.entries.flatMap { kind ->
        listOf(
          ParameterReference(7L, -100L, kind, 2, listOf(1, 3), startIndex = 5, valueHash = 123_456),
          ParameterReference(7L, -100L, kind, 0, emptyList(), startIndex = 0, valueHash = -42),
          ParameterReference(Long.MAX_VALUE, Long.MIN_VALUE, kind, 0, listOf(0), startIndex = 0, valueHash = Int.MIN_VALUE),
        )
      }

    for (reference in references) {
      assertThat(ParameterReference.parse(reference.encode())).isEqualTo(reference)
    }
  }

  @Test
  fun testParseRejectsMalformedTokens() {
    val malformed =
      listOf(
        "",
        "garbage",
        "7:100:p:2:1.3:5", // A field is missing.
        "7:100:p:2:1.3:5:z:extra",
        "x:100:p:2:1.3:5:z",
        "7:x:p:2:1.3:5:z",
        "7:100:q:2:1.3:5:z", // An unknown kind.
        "7:100:p:-1:1.3:5:z",
        "7:100:p:2:1..3:5:z",
        "7:100:p:2:1.-3:5:z",
        "7:100:p:2:1.3:-5:z",
        "7:100:p:2:1.3:5:", // An empty value hash.
        "7:100:p:2:1.3:5:-z",
        "7:100:p:2:1.3:5:1z141z4", // A value hash above the largest unsigned Int.
      )

    for (token in malformed) {
      val exception = assertThrows(token, IllegalArgumentException::class.java) { ParameterReference.parse(token) }
      assertThat(exception).hasMessageThat().contains("'$token'")
    }
  }

  @Test
  fun testMatchesComparesNameAndValue() {
    val reference =
      ParameterReference(
        1L,
        2L,
        ParameterReference.Kind.PARAMETERS,
        0,
        emptyList(),
        0,
        ParameterReference.hashOf("feed", UiNode.ComposeParameter.Value.StringVal("List[131]")),
      )
    fun list(name: String, value: String) =
      UiNode.ComposeParameter.Group(name, emptyList(), isCollection = true, UiNode.ComposeParameter.Value.StringVal(value))

    assertThat(reference.matches(list("feed", "List[131]"))).isTrue()
    // The dump can report the value as a single value, e.g. at the depth limit.
    assertThat(reference.matches(UiNode.ComposeParameter.Single("feed", UiNode.ComposeParameter.Value.StringVal("List[131]")))).isTrue()
    assertThat(reference.matches(list("feedState", "List[131]"))).isFalse()
    assertThat(reference.matches(list("feed", "List[140]"))).isFalse()
  }

  @Test
  fun testHashOfTreatsEmptyValueAsNone() {
    // A group drops an empty value, which a single value keeps: e.g. a Modifier chain at the depth limit in the dump, then fetched.
    val none = ParameterReference.hashOf("modifier", null)

    assertThat(ParameterReference.hashOf("modifier", UiNode.ComposeParameter.Value.StringVal(""))).isEqualTo(none)
    assertThat(ParameterReference.hashOf("modifier", UiNode.ComposeParameter.Value.NullVal)).isEqualTo(none)
  }

  @Test
  fun testHashOfUsesTheTextOfTheValue() {
    // Hash codes of enums differ between processes, so values are hashed through their text, which includes the enum names.
    val dimension = UiNode.ComposeParameter.Value.DimensionVal(48f, UiNode.ComposeParameter.DimensionUnit.DP)

    assertThat(ParameterReference.hashOf("bottomEnd", dimension)).isEqualTo("bottomEnd\u0000$dimension".hashCode())
    assertThat(dimension.toString()).contains("DP")
  }
}
