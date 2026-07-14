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

package com.android.tools.ui.inspector

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TreePrinterTest {

  @Test
  fun testFormatAttribute_String() {
    val attr = UiNode.Attribute(name = "text", value = UiNode.AttributeValue.StringVal("Hello"))
    assertThat(attr.format()).isEqualTo("prop: text=Hello")
  }

  @Test
  fun testFormatAttribute_Boolean() {
    val attr = UiNode.Attribute(name = "enabled", value = UiNode.AttributeValue.BooleanVal(true))
    assertThat(attr.format()).isEqualTo("prop: enabled=true")
  }

  @Test
  fun testFormatAttribute_Color() {
    val attr = UiNode.Attribute(name = "textColor", value = UiNode.AttributeValue.ColorVal(-1))
    assertThat(attr.format()).isEqualTo("prop: textColor=#FFFFFFFF")
  }

  @Test
  fun testFormatAttribute_Null() {
    val attr = UiNode.Attribute(name = "tag", value = UiNode.AttributeValue.NullVal)
    assertThat(attr.format()).isEqualTo("prop: tag=")
  }

  @Test
  fun testFormatAttribute_DimensionVal() {
    // 1. Integer-like dimension, no density
    val attrInt = UiNode.Attribute(name = "layout_width", value = UiNode.AttributeValue.DimensionVal(120.0f))
    assertThat(attrInt.format()).isEqualTo("prop: layout_width=120px")

    // 2. Decimal-like dimension, no density
    val attrDec = UiNode.Attribute(name = "layout_width", value = UiNode.AttributeValue.DimensionVal(120.5f))
    assertThat(attrDec.format()).isEqualTo("prop: layout_width=120.50px")

    // 3. Integer-like dp attribute, with density
    val attrDpInt = UiNode.Attribute(name = "layout_width", value = UiNode.AttributeValue.DimensionVal(240.0f, dp = 120.0f))
    assertThat(attrDpInt.format()).isEqualTo("prop: layout_width=240px (120dp)")

    // 4. Decimal dp attribute, with density
    val attrDpDec = UiNode.Attribute(name = "layout_width", value = UiNode.AttributeValue.DimensionVal(241.0f, dp = 120.5f))
    assertThat(attrDpDec.format()).isEqualTo("prop: layout_width=241px (120.50dp)")

    // 5. Integer-like sp attribute (textSize), with density and fontScale
    val attrSpInt = UiNode.Attribute(name = "textSize", value = UiNode.AttributeValue.DimensionVal(240.0f, sp = 96.0f))
    assertThat(attrSpInt.format()).isEqualTo("prop: textSize=240px (96sp)")

    // 6. Decimal sp attribute (textSize), with density and fontScale
    val attrSpDec = UiNode.Attribute(name = "textSize", value = UiNode.AttributeValue.DimensionVal(241.0f, sp = 96.4f))
    assertThat(attrSpDec.format()).isEqualTo("prop: textSize=241px (96.40sp)")

    // 7. sp attribute (textSize) with density but no fontScale (defaults to 1.0f)
    val attrSpNoFontScale = UiNode.Attribute(name = "textSize", value = UiNode.AttributeValue.DimensionVal(240.0f, sp = 120.0f))
    assertThat(attrSpNoFontScale.format()).isEqualTo("prop: textSize=240px (120sp)")

    // 8. another sp attribute (lineHeight), with density and fontScale
    val attrLineHeight = UiNode.Attribute(name = "lineHeight", value = UiNode.AttributeValue.DimensionVal(240.0f, sp = 96.0f))
    assertThat(attrLineHeight.format()).isEqualTo("prop: lineHeight=240px (96sp)")
  }

  @Test
  fun testFormatAttribute_NumberVal() {
    // Integer number
    val attrInt = UiNode.Attribute(name = "count", value = UiNode.AttributeValue.NumberVal(42))
    assertThat(attrInt.format()).isEqualTo("prop: count=42")

    // Double number
    val attrDouble = UiNode.Attribute(name = "ratio", value = UiNode.AttributeValue.NumberVal(3.14159))
    assertThat(attrDouble.format()).isEqualTo("prop: ratio=3.14")

    // Float number
    val attrFloat = UiNode.Attribute(name = "scale", value = UiNode.AttributeValue.NumberVal(1.5f))
    assertThat(attrFloat.format()).isEqualTo("prop: scale=1.50")
  }

  @Test
  fun testFormatNodeChange_AttributeValue() {
    val node =
      UiNode.ViewNode(
        id = 1L,
        className = "Button",
        bounds = UiNode.Bounds(0, 0, 10, 10),
        idResource = "btn",
        layoutResource = null,
        attributes = emptyList(),
      )

    // 1. Dimension change with pre-calculated dp values
    val changeDim =
      NodeChange.PropertyChange.Modified(
        name = "layout_width",
        oldValue = UiNode.AttributeValue.DimensionVal(120f, dp = 120f),
        newValue = UiNode.AttributeValue.DimensionVal(240f, dp = 240f),
      )

    assertThat(formatNodeChange(node, changeDim)).isEqualTo("prop: layout_width=120px (120dp) -> 240px (240dp)")

    // 2. Dimension change without pre-calculated values
    val changeDimNoDensity =
      NodeChange.PropertyChange.Modified(
        name = "layout_width",
        oldValue = UiNode.AttributeValue.DimensionVal(120f),
        newValue = UiNode.AttributeValue.DimensionVal(240f),
      )
    assertThat(formatNodeChange(node, changeDimNoDensity)).isEqualTo("prop: layout_width=120px -> 240px")

    // 3. String change
    val changeString =
      NodeChange.PropertyChange.Modified(
        name = "text",
        oldValue = UiNode.AttributeValue.StringVal("Click"),
        newValue = UiNode.AttributeValue.StringVal("Clicked"),
      )
    assertThat(formatNodeChange(node, changeString)).isEqualTo("prop: text=Click -> Clicked")
  }
}
