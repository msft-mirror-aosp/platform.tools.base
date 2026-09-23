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

package com.android.tools.ui.inspector.printer.text

import com.android.tools.ui.inspector.model.UiNode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextValueFormatterTest {

  @Test
  fun testFormatComposeParameterSingleValues() {
    assertThat(single(UiNode.ComposeParameter.Value.StringVal("Hello"))).isEqualTo("Hello")
    assertThat(single(UiNode.ComposeParameter.Value.NumberVal(3.14))).isEqualTo("3.14")
    assertThat(single(UiNode.ComposeParameter.Value.DimensionVal(8f, UiNode.ComposeParameter.DimensionUnit.DP))).isEqualTo("8.0dp")
    assertThat(single(UiNode.ComposeParameter.Value.ColorVal(-1))).isEqualTo("#FFFFFFFF")
    assertThat(single(UiNode.ComposeParameter.Value.ResourceVal("android", null, "textView"))).isEqualTo("@android:textView")
    assertThat(single(UiNode.ComposeParameter.Value.LambdaVal("File.kt", 42))).isEqualTo("[lambda in File.kt:42]")
  }

  @Test
  fun testFormatComposeParameterCollectionGroup() {
    val list =
      UiNode.ComposeParameter.Group(
        name = "listParam",
        elements =
          listOf(
            UiNode.ComposeParameter.Single("0", UiNode.ComposeParameter.Value.StringVal("item1")),
            UiNode.ComposeParameter.Single("1", UiNode.ComposeParameter.Value.StringVal("item2")),
          ),
        isCollection = true,
      )

    assertThat(formatComposeParameter(list)).isEqualTo("[item1, item2]")
  }

  @Test
  fun testFormatComposeParameterObjectGroup() {
    val map =
      UiNode.ComposeParameter.Group(
        name = "mapParam",
        elements = listOf(UiNode.ComposeParameter.Single("key1", UiNode.ComposeParameter.Value.StringVal("val1"))),
        isCollection = false,
      )

    assertThat(formatComposeParameter(map)).isEqualTo("{key1=val1}")
  }

  private fun single(value: UiNode.ComposeParameter.Value) = formatComposeParameter(UiNode.ComposeParameter.Single("param", value))
}
