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

import com.android.tools.ui.inspector.printer.formatComposeParameter
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import com.google.common.truth.Truth.assertThat
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import org.junit.Test

class ProtoConvertersTest {

  @Test
  fun testConvertComposeNode_PrimitiveParameters() {
    val stringTable =
      mapOf(
        1 to "MyComponent",
        2 to "paramString",
        3 to "Hello",
        4 to "paramDouble",
        5 to "paramDimension",
        6 to "paramColor",
        7 to "paramResource",
        8 to "textView",
        9 to "android",
        10 to "paramLambda",
        11 to "File.kt",
      )

    val composableNode =
      LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
        .setId(100)
        .setName(1) // MyComponent
        .build()

    val allParams =
      LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
        .addParameterGroups(
          LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(100)
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(2)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(3) // "Hello"
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(4)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.DOUBLE)
                .setDoubleValue(3.14)
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(5)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_DP)
                .setFloatValue(8f)
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(6)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.COLOR)
                .setInt32Value(-1) // 0xFFFFFFFF
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(7)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.RESOURCE)
                .setResourceValue(
                  LayoutInspectorComposeProtocol.Resource.newBuilder()
                    .setNamespace(9) // android
                    .setType(1)
                    .setName(8) // textView
                )
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(10)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.LAMBDA)
                .setLambdaValue(
                  LayoutInspectorComposeProtocol.LambdaValue.newBuilder()
                    .setFileName(11) // File.kt
                    .setStartLineNumber(42)
                )
            )
        )
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("paramString"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("Hello"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("paramDouble"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(5).setStr("paramDimension"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(6).setStr("paramColor"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(7).setStr("paramResource"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(8).setStr("textView"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(9).setStr("android"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(10).setStr("paramLambda"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(11).setStr("File.kt"))
        .build()

    val composeNode = convertComposeNode(node = composableNode, stringTable = stringTable, hostedViews = emptyMap(), parameters = allParams)

    assertThat(composeNode.parameters).hasSize(6)

    // 1. String Value
    val pString = composeNode.parameters[0] as UiNode.ComposeParameter.Single
    assertThat(pString.name).isEqualTo("paramString")
    assertThat(pString.value).isEqualTo(UiNode.ComposeParameter.Value.StringVal("Hello"))
    assertThat(formatComposeParameter(pString)).isEqualTo("Hello")

    // 2. Double Value
    val pDouble = composeNode.parameters[1] as UiNode.ComposeParameter.Single
    assertThat(pDouble.name).isEqualTo("paramDouble")
    assertThat(pDouble.value).isEqualTo(UiNode.ComposeParameter.Value.NumberVal(3.14))
    assertThat(formatComposeParameter(pDouble)).isEqualTo("3.14")

    // 3. Dimension Value
    val pDim = composeNode.parameters[2] as UiNode.ComposeParameter.Single
    assertThat(pDim.name).isEqualTo("paramDimension")
    assertThat(pDim.value).isEqualTo(UiNode.ComposeParameter.Value.DimensionVal(8f, UiNode.ComposeParameter.DimensionUnit.DP))
    assertThat(formatComposeParameter(pDim)).isEqualTo("8.0dp")

    // 4. Color Value
    val pColor = composeNode.parameters[3] as UiNode.ComposeParameter.Single
    assertThat(pColor.name).isEqualTo("paramColor")
    assertThat(pColor.value).isEqualTo(UiNode.ComposeParameter.Value.ColorVal(-1))
    assertThat(formatComposeParameter(pColor)).isEqualTo("#FFFFFFFF")

    // 5. Resource Value
    val pRes = composeNode.parameters[4] as UiNode.ComposeParameter.Single
    assertThat(pRes.name).isEqualTo("paramResource")
    assertThat(pRes.value).isEqualTo(UiNode.ComposeParameter.Value.ResourceVal("android", null, "textView"))
    assertThat(formatComposeParameter(pRes)).isEqualTo("@android:textView")

    // 6. Lambda Value
    val pLambda = composeNode.parameters[5] as UiNode.ComposeParameter.Single
    assertThat(pLambda.name).isEqualTo("paramLambda")
    assertThat(pLambda.value).isEqualTo(UiNode.ComposeParameter.Value.LambdaVal("File.kt", 42))
    assertThat(formatComposeParameter(pLambda)).isEqualTo("[lambda in File.kt:42]")
  }

  @Test
  fun testConvertComposeNode_CollectionsAndGroups() {
    val stringTable = mapOf(1 to "MyComponent", 2 to "listParam", 3 to "item1", 4 to "item2", 5 to "mapParam", 6 to "key1", 7 to "val1")

    val composableNode =
      LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
        .setId(100)
        .setName(1) // MyComponent
        .build()

    val allParams =
      LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
        .addParameterGroups(
          LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(100)
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(2) // listParam
                .addElements(
                  LayoutInspectorComposeProtocol.Parameter.newBuilder()
                    .setName(0) // anonymous
                    .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                    .setInt32Value(3) // "item1"
                )
                .addElements(
                  LayoutInspectorComposeProtocol.Parameter.newBuilder()
                    .setName(0) // anonymous
                    .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                    .setInt32Value(4) // "item2"
                )
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(5) // mapParam
                .addElements(
                  LayoutInspectorComposeProtocol.Parameter.newBuilder()
                    .setName(6) // key1
                    .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                    .setInt32Value(7) // "val1"
                )
            )
        )
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("listParam"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("item1"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("item2"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(5).setStr("mapParam"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(6).setStr("key1"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(7).setStr("val1"))
        .build()

    val composeNode = convertComposeNode(node = composableNode, stringTable = stringTable, hostedViews = emptyMap(), parameters = allParams)

    assertThat(composeNode.parameters).hasSize(2)

    // 1. Collection List
    val pList = composeNode.parameters[0] as UiNode.ComposeParameter.Group
    assertThat(pList.name).isEqualTo("listParam")
    assertThat(pList.isCollection).isTrue()
    assertThat(pList.elements).hasSize(2)
    assertThat(formatComposeParameter(pList)).isEqualTo("[item1, item2]")

    // 2. Map object
    val pMap = composeNode.parameters[1] as UiNode.ComposeParameter.Group
    assertThat(pMap.name).isEqualTo("mapParam")
    assertThat(pMap.isCollection).isFalse()
    assertThat(pMap.elements).hasSize(1)
    assertThat(formatComposeParameter(pMap)).isEqualTo("{key1=val1}")
  }

  @Test
  fun testConvertViewNode_PrimitiveAttributes() {
    val stringTable = mapOf(1 to "myView", 2 to "myCharAttr", 3 to "myIntAttr")

    val viewNodeProto =
      ViewInspectorProtocol.ViewNode.newBuilder()
        .setId(100L)
        .setClassName(1) // myView
        .setBounds(ViewInspectorProtocol.Rect.newBuilder().setX(0).setY(0).setWidth(100).setHeight(100))
        .addAttributes(
          ViewInspectorProtocol.ViewNode.Attribute.newBuilder()
            .setName(2) // myCharAttr
            .setType(ViewInspectorProtocol.ViewNode.Attribute.Type.CHAR)
            .setInt32Value(65) // 'A'
        )
        .addAttributes(
          ViewInspectorProtocol.ViewNode.Attribute.newBuilder()
            .setName(3) // myIntAttr
            .setType(ViewInspectorProtocol.ViewNode.Attribute.Type.INT32)
            .setInt32Value(42)
        )
        .build()

    val viewNode = convertViewNode(viewNodeProto, stringTable)

    assertThat(viewNode.attributes).hasSize(2)

    val charAttr = viewNode.attributes[0]
    assertThat(charAttr.name).isEqualTo("myCharAttr")
    assertThat(charAttr.value).isEqualTo(UiNode.AttributeValue.StringVal("A"))

    val intAttr = viewNode.attributes[1]
    assertThat(intAttr.name).isEqualTo("myIntAttr")
    assertThat(intAttr.value).isEqualTo(UiNode.AttributeValue.NumberVal(42))
  }
}
