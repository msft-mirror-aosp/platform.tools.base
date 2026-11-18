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
package com.android.tools.render.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewIdsTest {
  @Test
  fun idIsTheMethodFollowedByTheNameAndTheHashOfThePreviewParameters() {
    val previewParams = mapOf("name" to "Dark", "uiMode" to "32", "widthDp" to "360")

    assertThat(previewId(METHOD, previewParams, emptyList())).isEqualTo("${METHOD}_Dark_4be26859")
  }

  @Test
  fun idWithoutNameOnlyHasTheHash() {
    assertThat(previewId(METHOD, mapOf("widthDp" to "360"), emptyList())).isEqualTo("${METHOD}_c1fd2edc")
  }

  @Test
  fun idWithoutParametersIsTheMethod() {
    assertThat(previewId(METHOD, emptyMap(), emptyList())).isEqualTo(METHOD)
  }

  @Test
  fun idOfAMethodWithAPreviewParameterEndsWithTheHashOfItsParameters() {
    val previewParams = mapOf("name" to "Dark", "uiMode" to "32", "widthDp" to "360")
    val methodParams = listOf(mapOf("provider" to "com.example.UserProvider", "limit" to "2"))

    assertThat(previewId(METHOD, previewParams, methodParams)).isEqualTo("${METHOD}_Dark_4be26859_6fd90ef1")
  }

  @Test
  fun nameWithCharactersThatCantBeInAFileNameIsLeftOut() {
    // The hash still covers the name, so two previews that only differ by such a name still have different ids.
    assertThat(previewId(METHOD, mapOf("name" to "a/b", "widthDp" to "360"), emptyList())).isEqualTo("${METHOD}_e95a2923")
  }

  @Test
  fun nameWithControlCharactersIsLeftOut() {
    val newlineId = previewId(METHOD, mapOf("name" to "a\nb", "widthDp" to "360"), emptyList())
    val tabId = previewId(METHOD, mapOf("name" to "a\tb", "widthDp" to "360"), emptyList())

    assertThat(newlineId).matches("^${Regex.escape(METHOD)}_[0-9a-f]{8}$")
    assertThat(tabId).matches("^${Regex.escape(METHOD)}_[0-9a-f]{8}$")
    assertThat(newlineId).isNotEqualTo(tabId)
  }

  @Test
  fun dottedNameIsKeptInId() {
    val dottedId = previewId(METHOD, mapOf("name" to "Font 1.5x", "widthDp" to "360"), emptyList())

    assertThat(dottedId).matches("^${Regex.escape("${METHOD}_Font 1.5x")}_[0-9a-f]{8}$")
  }

  @Test
  fun blankNameIsLeftOut() {
    val emptyNameId = previewId(METHOD, mapOf("name" to "", "widthDp" to "360"), emptyList())
    val whitespaceNameId = previewId(METHOD, mapOf("name" to "   ", "widthDp" to "360"), emptyList())

    assertThat(emptyNameId).matches("^${Regex.escape(METHOD)}_[0-9a-f]{8}$")
    assertThat(whitespaceNameId).matches("^${Regex.escape(METHOD)}_[0-9a-f]{8}$")
    assertThat(emptyNameId).isNotEqualTo(whitespaceNameId)
  }

  @Test
  fun barePreviewWithPreviewParameterOmitsAnnotationHash() {
    val methodParams = listOf(mapOf("provider" to "com.example.UserProvider", "limit" to "2"))

    assertThat(previewId(METHOD, emptyMap(), methodParams)).isEqualTo("${METHOD}_6fd90ef1")
  }

  @Test
  fun idDoesNotDependOnTheOrderOfTheParameters() {
    val previewParams = linkedMapOf("widthDp" to "360", "uiMode" to "32", "name" to "Dark")
    val methodParams = listOf(linkedMapOf("limit" to "2", "provider" to "com.example.UserProvider"))

    assertThat(previewId(METHOD, previewParams, methodParams)).isEqualTo("${METHOD}_Dark_4be26859_6fd90ef1")
  }

  @Test
  fun previewsWithDifferentParametersHaveDifferentIds() {
    val light = previewId(METHOD, mapOf("name" to "Phone", "widthDp" to "360"), emptyList())
    val dark = previewId(METHOD, mapOf("name" to "Phone", "widthDp" to "360", "uiMode" to "32"), emptyList())

    assertThat(light).isNotEqualTo(dark)
  }

  private companion object {
    const val METHOD = "com.example.HomeKt.HomePreview"
  }
}
