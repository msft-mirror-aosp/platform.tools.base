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

package com.android.tools.ui.inspector.proto

import com.android.tools.ui.inspector.model.ColorModeHdr
import com.android.tools.ui.inspector.model.ColorModeWideGamut
import com.android.tools.ui.inspector.model.DeviceLocale
import com.android.tools.ui.inspector.model.Dimension
import com.android.tools.ui.inspector.model.GrammaticalGender
import com.android.tools.ui.inspector.model.HardKeyboardHidden
import com.android.tools.ui.inspector.model.Keyboard
import com.android.tools.ui.inspector.model.KeyboardHidden
import com.android.tools.ui.inspector.model.LayoutDirection
import com.android.tools.ui.inspector.model.Navigation
import com.android.tools.ui.inspector.model.NavigationHidden
import com.android.tools.ui.inspector.model.Orientation
import com.android.tools.ui.inspector.model.ScreenLayoutLong
import com.android.tools.ui.inspector.model.ScreenLayoutRound
import com.android.tools.ui.inspector.model.ScreenLayoutSize
import com.android.tools.ui.inspector.model.TouchScreen
import com.android.tools.ui.inspector.model.UiModeNight
import com.android.tools.ui.inspector.model.UiModeType
import com.android.tools.ui.inspector.model.UiNode
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import com.google.common.truth.Truth.assertThat
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.Type.ITERABLE
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.Type.STRING
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

    val composeNode =
      convertComposeNode(
        node = composableNode,
        stringTable = stringTable,
        hostedViews = emptyMap(),
        renderOffsetX = 0,
        renderOffsetY = 0,
        parameters = allParams,
        includeParameters = true,
        includeSemantics = true,
      )

    assertThat(composeNode.parameters).hasSize(6)

    // 1. String Value
    val pString = composeNode.parameters[0] as UiNode.ComposeParameter.Single
    assertThat(pString.name).isEqualTo("paramString")
    assertThat(pString.value).isEqualTo(UiNode.ComposeParameter.Value.StringVal("Hello"))

    // 2. Double Value
    val pDouble = composeNode.parameters[1] as UiNode.ComposeParameter.Single
    assertThat(pDouble.name).isEqualTo("paramDouble")
    assertThat(pDouble.value).isEqualTo(UiNode.ComposeParameter.Value.NumberVal(3.14))

    // 3. Dimension Value
    val pDim = composeNode.parameters[2] as UiNode.ComposeParameter.Single
    assertThat(pDim.name).isEqualTo("paramDimension")
    assertThat(pDim.value).isEqualTo(UiNode.ComposeParameter.Value.DimensionVal(8f, UiNode.ComposeParameter.DimensionUnit.DP))

    // 4. Color Value
    val pColor = composeNode.parameters[3] as UiNode.ComposeParameter.Single
    assertThat(pColor.name).isEqualTo("paramColor")
    assertThat(pColor.value).isEqualTo(UiNode.ComposeParameter.Value.ColorVal(-1))

    // 5. Resource Value
    val pRes = composeNode.parameters[4] as UiNode.ComposeParameter.Single
    assertThat(pRes.name).isEqualTo("paramResource")
    assertThat(pRes.value).isEqualTo(UiNode.ComposeParameter.Value.ResourceVal("android", null, "textView"))

    // 6. Lambda Value
    val pLambda = composeNode.parameters[5] as UiNode.ComposeParameter.Single
    assertThat(pLambda.name).isEqualTo("paramLambda")
    assertThat(pLambda.value).isEqualTo(UiNode.ComposeParameter.Value.LambdaVal("File.kt", 42))
  }

  @Test
  fun testConvertComposeNode_iterableIsCollection() {
    // The compose inspector types every iterable as ITERABLE and names its elements by index; null elements are omitted.
    val list =
      parameter(1, ITERABLE, value = 2) // "List[3]"
        .addElements(parameter(3, STRING, value = 4)) // [0]=item0
        .addElements(parameter(5, STRING, value = 6).setIndex(2)) // [2]=item2
    val strings = mapOf(1 to "listParam", 2 to "List[3]", 3 to "[0]", 4 to "item0", 5 to "[2]", 6 to "item2")

    val pList = convertSingleParameter(list, strings) as UiNode.ComposeParameter.Group

    assertThat(pList.name).isEqualTo("listParam")
    assertThat(pList.isCollection).isTrue()
    assertThat(pList.elements)
      .containsExactly(
        UiNode.ComposeParameter.Single("[0]", UiNode.ComposeParameter.Value.StringVal("item0")),
        UiNode.ComposeParameter.Single("[2]", UiNode.ComposeParameter.Value.StringVal("item2")),
      )
      .inOrder()
  }

  @Test
  fun testConvertComposeNode_emptyIterableIsEmptyCollection() {
    // An empty iterable has no elements and no reference: there is nothing more to fetch.
    val empty = parameter(1, ITERABLE, value = 2)
    val strings = mapOf(1 to "emptyParam", 2 to "List[0]")

    assertThat(convertSingleParameter(empty, strings))
      .isEqualTo(UiNode.ComposeParameter.Group("emptyParam", elements = emptyList(), isCollection = true))
  }

  @Test
  fun testConvertComposeNode_unexpandedIterableKeepsSummary() {
    // At the recursion limit the agent sends a non-empty iterable without elements, with a reference for fetching them later.
    val unexpanded =
      parameter(1, ITERABLE, value = 2).setReference(LayoutInspectorComposeProtocol.ParameterReference.newBuilder().setComposableId(100))
    val strings = mapOf(1 to "deepList", 2 to "List[7]")

    assertThat(convertSingleParameter(unexpanded, strings))
      .isEqualTo(UiNode.ComposeParameter.Single("deepList", UiNode.ComposeParameter.Value.StringVal("List[7]")))
  }

  @Test
  fun testConvertComposeNode_mapIsCollectionOfEntries() {
    // A Map is an ITERABLE whose elements are its entries, each named "[key]" and holding a key and a value.
    val entry =
      parameter(3, STRING, value = 4) // [k1]=v1
        .addElements(parameter(5, STRING, value = 6)) // key=k1
        .addElements(parameter(7, STRING, value = 4).setIndex(1)) // value=v1
    val map = parameter(1, ITERABLE, value = 2).addElements(entry)
    val strings = mapOf(1 to "mapParam", 2 to "Map[1]", 3 to "[k1]", 4 to "v1", 5 to "key", 6 to "k1", 7 to "value")

    val pMap = convertSingleParameter(map, strings) as UiNode.ComposeParameter.Group

    assertThat(pMap.isCollection).isTrue()
    assertThat(pMap.elements)
      .containsExactly(
        UiNode.ComposeParameter.Group(
          "[k1]",
          listOf(
            UiNode.ComposeParameter.Single("key", UiNode.ComposeParameter.Value.StringVal("k1")),
            UiNode.ComposeParameter.Single("value", UiNode.ComposeParameter.Value.StringVal("v1")),
          ),
          isCollection = false,
        )
      )
  }

  @Test
  fun testConvertComposeNode_objectIsNotCollection() {
    // Objects (e.g. TextStyle, Offset) are typed by their own value and name their elements after their properties.
    val offset =
      parameter(1, STRING, value = 2)
        .addElements(parameter(3, LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_DP).setFloatValue(1f))
        .addElements(parameter(4, LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_DP).setFloatValue(2f).setIndex(1))
    val strings = mapOf(1 to "offset", 2 to "Offset", 3 to "x", 4 to "y")

    val pOffset = convertSingleParameter(offset, strings) as UiNode.ComposeParameter.Group

    assertThat(pOffset.isCollection).isFalse()
    assertThat(pOffset.elements.map { it.name }).containsExactly("x", "y").inOrder()
  }

  @Test
  fun testConvertComposeNode_unexpandedObjectKeepsValue() {
    // A non-iterable without elements stays a single value, with or without a reference.
    val unexpanded =
      parameter(1, STRING, value = 2).setReference(LayoutInspectorComposeProtocol.ParameterReference.newBuilder().setComposableId(100))
    val strings = mapOf(1 to "style", 2 to "TextStyle")

    assertThat(convertSingleParameter(unexpanded, strings))
      .isEqualTo(UiNode.ComposeParameter.Single("style", UiNode.ComposeParameter.Value.StringVal("TextStyle")))
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

    val viewNode = convertViewNode(viewNodeProto, stringTable, includeResolutionStack = true)

    assertThat(viewNode.attributes).hasSize(2)

    val charAttr = viewNode.attributes[0]
    assertThat(charAttr.name).isEqualTo("myCharAttr")
    assertThat(charAttr.value).isEqualTo(UiNode.AttributeValue.StringVal("A"))

    val intAttr = viewNode.attributes[1]
    assertThat(intAttr.name).isEqualTo("myIntAttr")
    assertThat(intAttr.value).isEqualTo(UiNode.AttributeValue.NumberVal(42))
  }

  @Test
  fun testConvertConfiguration() {
    val stringTable = mapOf(1 to "en", 2 to "US", 3 to "variant", 4 to "Latn")
    val configProto =
      ViewInspectorProtocol.Configuration.newBuilder()
        .setDensity(420)
        .setScreenWidthDp(1080)
        .setScreenHeightDp(1920)
        .setSmallestScreenWidthDp(720)
        .setFontScale(1.2f)
        .setOrientation(ViewInspectorProtocol.Orientation.ORIENTATION_LANDSCAPE)
        .setScreenLayoutSize(ViewInspectorProtocol.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_LARGE)
        .setScreenLayoutLong(ViewInspectorProtocol.ScreenLayoutLong.SCREEN_LAYOUT_LONG_YES)
        .setLayoutDirection(ViewInspectorProtocol.LayoutDirection.LAYOUT_DIRECTION_RTL)
        .setScreenLayoutRound(ViewInspectorProtocol.ScreenLayoutRound.SCREEN_LAYOUT_ROUND_YES)
        .setColorModeWideGamut(ViewInspectorProtocol.ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_YES)
        .setColorModeHdr(ViewInspectorProtocol.ColorModeHdr.COLOR_MODE_HDR_YES)
        .setTouchScreen(ViewInspectorProtocol.TouchScreen.TOUCH_SCREEN_FINGER)
        .setKeyboard(ViewInspectorProtocol.Keyboard.KEYBOARD_QWERTY)
        .setKeyboardHidden(ViewInspectorProtocol.KeyboardHidden.KEYBOARD_HIDDEN_NO)
        .setHardKeyboardHidden(ViewInspectorProtocol.HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_NO)
        .setNavigation(ViewInspectorProtocol.Navigation.NAVIGATION_NONAV)
        .setNavigationHidden(ViewInspectorProtocol.NavigationHidden.NAVIGATION_HIDDEN_YES)
        .setUiModeType(ViewInspectorProtocol.UiModeType.UI_MODE_TYPE_NORMAL)
        .setUiModeNight(ViewInspectorProtocol.UiModeNight.UI_MODE_NIGHT_YES)
        .setLocale(ViewInspectorProtocol.Locale.newBuilder().setLanguage(1).setCountry(2).setVariant(3).setScript(4))
        .setGrammaticalGender(ViewInspectorProtocol.GrammaticalGender.GRAMMATICAL_GENDER_FEMININE)
        .build()

    val config = convertConfiguration(configProto, stringTable)

    assertThat(config.density).isEqualTo(Dimension.Dpi(420))
    assertThat(config.screenWidthDp).isEqualTo(Dimension.Dp(1080))
    assertThat(config.screenHeightDp).isEqualTo(Dimension.Dp(1920))
    assertThat(config.smallestScreenWidthDp).isEqualTo(Dimension.Dp(720))
    assertThat(config.fontScale).isEqualTo(1.2f)
    assertThat(config.orientation).isEqualTo(Orientation.LANDSCAPE)
    assertThat(config.screenLayoutSize).isEqualTo(ScreenLayoutSize.LARGE)
    assertThat(config.screenLayoutLong).isEqualTo(ScreenLayoutLong.YES)
    assertThat(config.layoutDirection).isEqualTo(LayoutDirection.RTL)
    assertThat(config.screenLayoutRound).isEqualTo(ScreenLayoutRound.YES)
    assertThat(config.colorModeWideGamut).isEqualTo(ColorModeWideGamut.YES)
    assertThat(config.colorModeHdr).isEqualTo(ColorModeHdr.YES)
    assertThat(config.touchScreen).isEqualTo(TouchScreen.FINGER)
    assertThat(config.keyboard).isEqualTo(Keyboard.QWERTY)
    assertThat(config.keyboardHidden).isEqualTo(KeyboardHidden.NO)
    assertThat(config.hardKeyboardHidden).isEqualTo(HardKeyboardHidden.NO)
    assertThat(config.navigation).isEqualTo(Navigation.NONAV)
    assertThat(config.navigationHidden).isEqualTo(NavigationHidden.YES)
    assertThat(config.uiModeType).isEqualTo(UiModeType.NORMAL)
    assertThat(config.uiModeNight).isEqualTo(UiModeNight.YES)
    assertThat(config.locale).isEqualTo(DeviceLocale("en", "US", "variant", "Latn"))
    assertThat(config.grammaticalGender).isEqualTo(GrammaticalGender.FEMININE)
  }

  @Test
  fun testConvertDisplay() {
    val displayProto = ViewInspectorProtocol.Display.newBuilder().setId(0).setWidthPx(1080).setHeightPx(1920).setOrientation(90).build()
    val display = convertDisplay(displayProto)
    assertThat(display.id).isEqualTo(0)
    assertThat(display.widthPx).isEqualTo(1080)
    assertThat(display.heightPx).isEqualTo(1920)
    assertThat(display.orientation).isEqualTo(90)

    val displayWithoutOrientation =
      convertDisplay(ViewInspectorProtocol.Display.newBuilder().setId(1).setWidthPx(800).setHeightPx(600).build())
    assertThat(displayWithoutOrientation.orientation).isNull()
  }

  @Test
  fun testConvertComposeNode_facetsAreIndependent() {
    val stringTable = mapOf(1 to "MyComponent")
    val composableNode = LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(100).setName(1).build()

    val allParams =
      LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
        .addParameterGroups(
          LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(100)
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(2) // text
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(3) // "Hello"
            )
            .addMergedSemantics(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(4) // ContentDescription
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(5) // "Button"
            )
            .addUnmergedSemantics(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(4) // ContentDescription
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(5) // "Button"
            )
        )
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("text"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("Hello"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("ContentDescription"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(5).setStr("Button"))
        .build()

    fun convert(includeParameters: Boolean, includeSemantics: Boolean) =
      convertComposeNode(
        node = composableNode,
        stringTable = stringTable,
        hostedViews = emptyMap(),
        renderOffsetX = 0,
        renderOffsetY = 0,
        parameters = allParams,
        includeParameters = includeParameters,
        includeSemantics = includeSemantics,
      )

    // The device response carries both facets, but each is copied into the node only when requested.
    val attributesOnly = convert(includeParameters = true, includeSemantics = false)
    assertThat(attributesOnly.parameters).hasSize(1)
    assertThat(attributesOnly.mergedSemantics).isEmpty()
    assertThat(attributesOnly.unmergedSemantics).isEmpty()

    val semanticsOnly = convert(includeParameters = false, includeSemantics = true)
    assertThat(semanticsOnly.parameters).isEmpty()
    assertThat(semanticsOnly.mergedSemantics).hasSize(1)
    assertThat(semanticsOnly.unmergedSemantics).hasSize(1)
    assertThat((semanticsOnly.mergedSemantics.single() as UiNode.ComposeParameter.Single).name).isEqualTo("ContentDescription")

    val both = convert(includeParameters = true, includeSemantics = true)
    assertThat(both.parameters).hasSize(1)
    assertThat(both.mergedSemantics).hasSize(1)
    assertThat(both.unmergedSemantics).hasSize(1)
  }

  @Test
  fun testConvertComposeNode_noFacetsRequested_copiesNothing() {
    val stringTable = mapOf(1 to "MyComponent")
    val composableNode = LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(100).setName(1).build()
    val allParams =
      LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
        .addParameterGroups(
          LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(100)
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(2)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(3)
            )
            .addMergedSemantics(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(2)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(3)
            )
        )
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("text"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("Hello"))
        .build()

    // Even with a response present, the converter is the contract: nothing requested, nothing copied.
    val node =
      convertComposeNode(
        node = composableNode,
        stringTable = stringTable,
        hostedViews = emptyMap(),
        renderOffsetX = 0,
        renderOffsetY = 0,
        parameters = allParams,
        includeParameters = false,
        includeSemantics = false,
      )

    assertThat(node.parameters).isEmpty()
    assertThat(node.mergedSemantics).isEmpty()
    assertThat(node.unmergedSemantics).isEmpty()
  }

  @Test
  fun testConvertComposeNode_systemCreatedFlag() {
    val stringTable = mapOf(1 to "MyComponent")
    val unknownBit = 0x8000

    fun convert(flags: Int) =
      convertComposeNode(
        node = LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(100).setName(1).setFlags(flags).build(),
        stringTable = stringTable,
        hostedViews = emptyMap(),
        renderOffsetX = 0,
        renderOffsetY = 0,
        includeParameters = false,
        includeSemantics = false,
      )

    // Only the SYSTEM_CREATED bit decides; other bits neither mark a node as system-created nor mask the bit when set.
    assertThat(convert(0).isSystemCreated).isFalse()
    assertThat(convert(LayoutInspectorComposeProtocol.ComposableNode.Flags.SYSTEM_CREATED_VALUE).isSystemCreated).isTrue()
    assertThat(convert(unknownBit).isSystemCreated).isFalse()
    assertThat(convert(LayoutInspectorComposeProtocol.ComposableNode.Flags.SYSTEM_CREATED_VALUE or unknownBit).isSystemCreated).isTrue()
  }

  @Test
  fun testConvertComposeNode_appendsHostedSubtreeIntactAfterComposeChildren() {
    val stringTable = mapOf(1 to "AndroidView", 2 to "Text")
    // AndroidView (100) with one Compose child (101) and a hosted subtree reference: the mapped value is the carrier
    // that held the embedded View (a ViewFactoryHolder), whose own subtree must move wholesale.
    val composableNode =
      LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
        .setId(100)
        .setName(1)
        .setViewId(12)
        .addChildren(LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(101).setName(2))
        .build()
    val payload =
      UiNode.ViewNode(
        id = 13,
        className = "android.widget.TextView",
        bounds = UiNode.Bounds(0, 0, 100, 50),
        idResource = null,
        layoutResource = null,
        attributes = emptyList(),
        children = emptyList(),
      )
    val holder =
      UiNode.ViewNode(
        id = 12,
        className = "androidx.compose.ui.viewinterop.ViewFactoryHolder",
        bounds = UiNode.Bounds(0, 0, 100, 50),
        idResource = null,
        layoutResource = null,
        attributes = emptyList(),
        children = listOf(payload),
      )

    val node =
      convertComposeNode(
        node = composableNode,
        stringTable = stringTable,
        hostedViews = mapOf(12L to holder),
        renderOffsetX = 0,
        renderOffsetY = 0,
        parameters = null,
        includeParameters = false,
        includeSemantics = false,
      )

    // The hosted subtree is appended after the Compose children, intact.
    assertThat(node.children.map { it.id }).containsExactly(101L, 12L).inOrder()
    val graftedHolder = node.children[1] as UiNode.ViewNode
    assertThat((graftedHolder.children.single() as UiNode.ViewNode).id).isEqualTo(13)
  }

  @Test
  fun testConvertComposeNode_irregularRenderQuadUsesAxisAlignedBounds() {
    val bounds =
      LayoutInspectorComposeProtocol.Bounds.newBuilder()
        .setLayout(LayoutInspectorComposeProtocol.Rect.newBuilder().setX(100).setY(200).setW(10).setH(20))
        .setRender(
          LayoutInspectorComposeProtocol.Quad.newBuilder().setX0(20).setY0(-10).setX1(70).setY1(15).setX2(55).setY2(80).setX3(-5).setY3(60)
        )
        .build()

    assertThat(convertBounds(bounds)).isEqualTo(UiNode.Bounds(-5, -10, 75, 90))
  }

  @Test
  fun testConvertComposeNode_renderQuadIncludesWindowOffset() {
    val bounds =
      LayoutInspectorComposeProtocol.Bounds.newBuilder()
        .setRender(
          LayoutInspectorComposeProtocol.Quad.newBuilder().setX0(10).setY0(20).setX1(50).setY1(15).setX2(60).setY2(40).setX3(20).setY3(50)
        )
        .build()

    assertThat(convertBounds(bounds, renderOffsetX = 300, renderOffsetY = 400)).isEqualTo(UiNode.Bounds(310, 415, 50, 35))
  }

  @Test
  fun testConvertComposeNode_missingRenderUsesScreenRelativeLayout() {
    val bounds =
      LayoutInspectorComposeProtocol.Bounds.newBuilder()
        .setLayout(LayoutInspectorComposeProtocol.Rect.newBuilder().setX(310).setY(420).setW(50).setH(60))
        .build()

    assertThat(convertBounds(bounds, renderOffsetX = 300, renderOffsetY = 400)).isEqualTo(UiNode.Bounds(310, 420, 50, 60))
  }

  @Test
  fun testConvertComposeNode_identityRenderAndLayoutFallbackMatch() {
    val layout = LayoutInspectorComposeProtocol.Rect.newBuilder().setX(12).setY(34).setW(56).setH(78)
    val renderedBounds =
      LayoutInspectorComposeProtocol.Bounds.newBuilder()
        .setLayout(layout)
        .setRender(
          LayoutInspectorComposeProtocol.Quad.newBuilder().setX0(12).setY0(34).setX1(68).setY1(34).setX2(68).setY2(112).setX3(12).setY3(112)
        )
        .build()
    val layoutBounds = LayoutInspectorComposeProtocol.Bounds.newBuilder().setLayout(layout).build()

    assertThat(convertBounds(renderedBounds)).isEqualTo(UiNode.Bounds(12, 34, 56, 78))
    assertThat(convertBounds(layoutBounds)).isEqualTo(UiNode.Bounds(12, 34, 56, 78))
  }

  @Test
  fun testConvertComposeNode_missingBoundsRemainsZero() {
    assertThat(convertBounds(null, renderOffsetX = 300, renderOffsetY = 400)).isEqualTo(UiNode.Bounds(0, 0, 0, 0))
  }

  @Test
  fun testConvertViewNode_withoutResolutionStack_omitsProvenance() {
    val stringTable = mapOf(1 to "android.widget.TextView", 2 to "text", 3 to "Hello", 4 to "layout.xml", 5 to "AppTheme")
    // The agent reports provenance whenever the device setting is enabled — which an earlier resolution-stack run
    // leaves set — so the converter must drop it when the facet was not requested.
    val viewNodeProto =
      ViewInspectorProtocol.ViewNode.newBuilder()
        .setId(1)
        .setClassName(1)
        .addAttributes(
          ViewInspectorProtocol.ViewNode.Attribute.newBuilder()
            .setName(2)
            .setType(ViewInspectorProtocol.ViewNode.Attribute.Type.STRING)
            .setInt32Value(3)
            .setDirectSource(4)
            .addStyleChain(5)
        )
        .build()

    val withProvenance = convertViewNode(viewNodeProto, stringTable, includeResolutionStack = true)
    assertThat(withProvenance.attributes.single().directSource).isEqualTo("layout.xml")
    assertThat(withProvenance.attributes.single().styleChain).containsExactly("AppTheme")

    val withoutProvenance = convertViewNode(viewNodeProto, stringTable, includeResolutionStack = false)
    assertThat(withoutProvenance.attributes.single().directSource).isNull()
    assertThat(withoutProvenance.attributes.single().styleChain).isEmpty()
    // The attribute value itself is unaffected: provenance is the resolution-stack facet, the value is the attributes facet.
    assertThat(withoutProvenance.attributes.single().value).isEqualTo(UiNode.AttributeValue.StringVal("Hello"))
  }

  private fun parameter(name: Int, type: LayoutInspectorComposeProtocol.Parameter.Type, value: Int? = null) =
    LayoutInspectorComposeProtocol.Parameter.newBuilder().setName(name).setType(type).apply { value?.let { setInt32Value(it) } }

  /** Converts [parameter] as the only parameter of a composable, resolving names and values through [strings]. */
  private fun convertSingleParameter(
    parameter: LayoutInspectorComposeProtocol.Parameter.Builder,
    strings: Map<Int, String>,
  ): UiNode.ComposeParameter {
    val response =
      LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
        .addParameterGroups(LayoutInspectorComposeProtocol.ParameterGroup.newBuilder().setComposableId(100).addParameter(parameter))
        .addAllStrings(strings.map { (id, str) -> LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(id).setStr(str).build() })
        .build()
    return convertComposeNode(
        node = LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(100).build(),
        stringTable = emptyMap(),
        hostedViews = emptyMap(),
        renderOffsetX = 0,
        renderOffsetY = 0,
        parameters = response,
        includeParameters = true,
        includeSemantics = false,
      )
      .parameters
      .single()
  }

  private fun convertBounds(bounds: LayoutInspectorComposeProtocol.Bounds?, renderOffsetX: Int = 0, renderOffsetY: Int = 0): UiNode.Bounds {
    val node = LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(1)
    bounds?.let { node.setBounds(it) }
    return convertComposeNode(
        node = node.build(),
        stringTable = emptyMap(),
        hostedViews = emptyMap(),
        renderOffsetX = renderOffsetX,
        renderOffsetY = renderOffsetY,
        includeParameters = false,
        includeSemantics = false,
      )
      .bounds
  }
}
