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

import com.android.tools.ui.inspector.attribute
import com.android.tools.ui.inspector.dimension
import com.android.tools.ui.inspector.model.UiNode
import com.android.tools.ui.inspector.printer.SemanticsDisplayMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TreePrinterTest {

  @Test
  fun testFormatAttribute_String() {
    val attr = attribute(name = "text", value = UiNode.AttributeValue.StringVal("Hello"))
    assertThat(attr.format()).isEqualTo("prop: text=Hello")
  }

  @Test
  fun testFormatAttribute_Boolean() {
    val attr = attribute(name = "enabled", value = UiNode.AttributeValue.BooleanVal(true))
    assertThat(attr.format()).isEqualTo("prop: enabled=true")
  }

  @Test
  fun testFormatAttribute_Color() {
    val attr = attribute(name = "textColor", value = UiNode.AttributeValue.ColorVal(-1))
    assertThat(attr.format()).isEqualTo("prop: textColor=#FFFFFFFF")
  }

  @Test
  fun testFormatAttribute_Null() {
    val attr = attribute(name = "tag", value = UiNode.AttributeValue.NullVal)
    assertThat(attr.format()).isEqualTo("prop: tag=")
  }

  @Test
  fun testFormatAttribute_DimensionVal() {
    // 1. Integer-like dimension, no density
    val attrInt = attribute(name = "layout_width", value = dimension(120.0f))
    assertThat(attrInt.format()).isEqualTo("prop: layout_width=120px")

    // 2. Decimal-like dimension, no density
    val attrDec = attribute(name = "layout_width", value = dimension(120.5f))
    assertThat(attrDec.format()).isEqualTo("prop: layout_width=120.50px")

    // 3. Integer-like dp attribute, with density
    val attrDpInt = attribute(name = "layout_width", value = dimension(240.0f, dp = 120.0f))
    assertThat(attrDpInt.format()).isEqualTo("prop: layout_width=240px (120dp)")

    // 4. Decimal dp attribute, with density
    val attrDpDec = attribute(name = "layout_width", value = dimension(241.0f, dp = 120.5f))
    assertThat(attrDpDec.format()).isEqualTo("prop: layout_width=241px (120.50dp)")

    // 5. Integer-like sp attribute (textSize), with density and fontScale
    val attrSpInt = attribute(name = "textSize", value = dimension(240.0f, sp = 96.0f))
    assertThat(attrSpInt.format()).isEqualTo("prop: textSize=240px (96sp)")

    // 6. Decimal sp attribute (textSize), with density and fontScale
    val attrSpDec = attribute(name = "textSize", value = dimension(241.0f, sp = 96.4f))
    assertThat(attrSpDec.format()).isEqualTo("prop: textSize=241px (96.40sp)")

    // 7. sp attribute (textSize) with density but no fontScale (defaults to 1.0f)
    val attrSpNoFontScale = attribute(name = "textSize", value = dimension(240.0f, sp = 120.0f))
    assertThat(attrSpNoFontScale.format()).isEqualTo("prop: textSize=240px (120sp)")

    // 8. another sp attribute (lineHeight), with density and fontScale
    val attrLineHeight = attribute(name = "lineHeight", value = dimension(240.0f, sp = 96.0f))
    assertThat(attrLineHeight.format()).isEqualTo("prop: lineHeight=240px (96sp)")
  }

  @Test
  fun testFormatAttribute_NumberVal() {
    // Integer number
    val attrInt = attribute(name = "count", value = UiNode.AttributeValue.NumberVal(42))
    assertThat(attrInt.format()).isEqualTo("prop: count=42")

    // Double number
    val attrDouble = attribute(name = "ratio", value = UiNode.AttributeValue.NumberVal(3.14159))
    assertThat(attrDouble.format()).isEqualTo("prop: ratio=3.14")

    // Float number
    val attrFloat = attribute(name = "scale", value = UiNode.AttributeValue.NumberVal(1.5f))
    assertThat(attrFloat.format()).isEqualTo("prop: scale=1.50")
  }

  @Test
  fun testPrintUiTree_SemanticsMode_Both() {
    val node =
      UiNode.ComposeNode(
        id = 1L,
        className = "MyButton",
        bounds = UiNode.Bounds(0, 0, 100, 50),
        parameters = emptyList(),
        mergedSemantics = listOf(UiNode.ComposeParameter.Single("Role", UiNode.ComposeParameter.Value.StringVal("Button"))),
        unmergedSemantics = listOf(UiNode.ComposeParameter.Single("OnClick", UiNode.ComposeParameter.Value.StringVal("[lambda]"))),
        isSystemCreated = false,
        children = emptyList(),
        sourceLocation = null,
      )

    val output = captureOutput { printUiTree(node, 0, it, SemanticsDisplayMode.BOTH) }

    val expected =
      """
View Hierarchy:
[MyButton] [compose] (0, 0, 100, 50)
 merged semantics: Role=Button
 unmerged semantics: OnClick=[lambda]
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expected.normalizeLineEndings())
  }

  @Test
  fun testPrintUiTree_SemanticsMode_MergedWithUnmergedFallback() {
    // 1. With merged semantics non-empty -> prints merged
    val nodeWithMerged =
      UiNode.ComposeNode(
        id = 1L,
        className = "MyButton",
        bounds = UiNode.Bounds(0, 0, 100, 50),
        parameters = emptyList(),
        mergedSemantics = listOf(UiNode.ComposeParameter.Single("Role", UiNode.ComposeParameter.Value.StringVal("Button"))),
        unmergedSemantics = listOf(UiNode.ComposeParameter.Single("OnClick", UiNode.ComposeParameter.Value.StringVal("[lambda]"))),
        isSystemCreated = false,
        children = emptyList(),
        sourceLocation = null,
      )

    val outputMerged = captureOutput { printUiTree(nodeWithMerged, 0, it, SemanticsDisplayMode.MERGED_WITH_UNMERGED_FALLBACK) }

    val expectedMerged =
      """
View Hierarchy:
[MyButton] [compose] (0, 0, 100, 50)
 merged semantics: Role=Button
"""
        .trim()

    assertThat(outputMerged.normalizeLineEndings()).isEqualTo(expectedMerged.normalizeLineEndings())

    // 2. With merged semantics empty -> falls back to unmerged
    val nodeWithoutMerged =
      UiNode.ComposeNode(
        id = 2L,
        className = "MyText",
        bounds = UiNode.Bounds(0, 0, 50, 20),
        parameters = emptyList(),
        mergedSemantics = emptyList(),
        unmergedSemantics = listOf(UiNode.ComposeParameter.Single("Text", UiNode.ComposeParameter.Value.StringVal("Hello"))),
        isSystemCreated = false,
        children = emptyList(),
        sourceLocation = null,
      )

    val outputUnmerged = captureOutput { printUiTree(nodeWithoutMerged, 0, it, SemanticsDisplayMode.MERGED_WITH_UNMERGED_FALLBACK) }

    val expectedUnmerged =
      """
View Hierarchy:
[MyText] [compose] (0, 0, 50, 20)
 unmerged semantics: Text=Hello
"""
        .trim()

    assertThat(outputUnmerged.normalizeLineEndings()).isEqualTo(expectedUnmerged.normalizeLineEndings())

    // 3. With both merged and unmerged semantics empty -> prints neither
    val nodeBothEmpty =
      UiNode.ComposeNode(
        id = 3L,
        className = "MyBox",
        bounds = UiNode.Bounds(0, 0, 10, 10),
        parameters = emptyList(),
        mergedSemantics = emptyList(),
        unmergedSemantics = emptyList(),
        isSystemCreated = false,
        children = emptyList(),
        sourceLocation = null,
      )

    val outputBothEmpty = captureOutput { printUiTree(nodeBothEmpty, 0, it, SemanticsDisplayMode.MERGED_WITH_UNMERGED_FALLBACK) }

    val expectedBothEmpty =
      """
View Hierarchy:
[MyBox] [compose] (0, 0, 10, 10)
"""
        .trim()

    assertThat(outputBothEmpty.normalizeLineEndings()).isEqualTo(expectedBothEmpty.normalizeLineEndings())
  }

  @Test
  fun testPrintUiTree_SemanticsMode_MergedOnly() {
    val node =
      UiNode.ComposeNode(
        id = 1L,
        className = "MyButton",
        bounds = UiNode.Bounds(0, 0, 100, 50),
        parameters = emptyList(),
        mergedSemantics = listOf(UiNode.ComposeParameter.Single("Role", UiNode.ComposeParameter.Value.StringVal("Button"))),
        unmergedSemantics = listOf(UiNode.ComposeParameter.Single("OnClick", UiNode.ComposeParameter.Value.StringVal("[lambda]"))),
        isSystemCreated = false,
        children = emptyList(),
        sourceLocation = null,
      )

    val output = captureOutput { printUiTree(node, 0, it, SemanticsDisplayMode.MERGED_ONLY) }

    val expected =
      """
View Hierarchy:
[MyButton] [compose] (0, 0, 100, 50)
 merged semantics: Role=Button
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expected.normalizeLineEndings())
  }

  @Test
  fun testPrintUiTree_SemanticsMode_UnmergedOnly() {
    val node =
      UiNode.ComposeNode(
        id = 1L,
        className = "MyButton",
        bounds = UiNode.Bounds(0, 0, 100, 50),
        parameters = emptyList(),
        mergedSemantics = listOf(UiNode.ComposeParameter.Single("Role", UiNode.ComposeParameter.Value.StringVal("Button"))),
        unmergedSemantics = listOf(UiNode.ComposeParameter.Single("OnClick", UiNode.ComposeParameter.Value.StringVal("[lambda]"))),
        isSystemCreated = false,
        children = emptyList(),
        sourceLocation = null,
      )

    val output = captureOutput { printUiTree(node, 0, it, SemanticsDisplayMode.UNMERGED_ONLY) }

    val expected =
      """
View Hierarchy:
[MyButton] [compose] (0, 0, 100, 50)
 unmerged semantics: OnClick=[lambda]
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expected.normalizeLineEndings())
  }

  @Test
  fun testPrintUiTree_SemanticsMode_None() {
    val node =
      UiNode.ComposeNode(
        id = 1L,
        className = "MyButton",
        bounds = UiNode.Bounds(0, 0, 100, 50),
        parameters = emptyList(),
        mergedSemantics = listOf(UiNode.ComposeParameter.Single("Role", UiNode.ComposeParameter.Value.StringVal("Button"))),
        unmergedSemantics = listOf(UiNode.ComposeParameter.Single("OnClick", UiNode.ComposeParameter.Value.StringVal("[lambda]"))),
        isSystemCreated = false,
        children = emptyList(),
        sourceLocation = null,
      )

    val output = captureOutput { printUiTree(node, 0, it, SemanticsDisplayMode.NONE) }

    val expected =
      """
View Hierarchy:
[MyButton] [compose] (0, 0, 100, 50)
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expected.normalizeLineEndings())
  }

  private fun captureOutput(action: (java.io.PrintStream) -> Unit): String {
    val outContent = java.io.ByteArrayOutputStream()
    action(java.io.PrintStream(outContent))
    return outContent.toString().trim()
  }

  private fun String.normalizeLineEndings(): String = this.replace("\r\n", "\n").replace('\r', '\n')
}
