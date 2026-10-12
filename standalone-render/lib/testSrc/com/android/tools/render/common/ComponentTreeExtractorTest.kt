/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.render.common

import com.android.ide.common.rendering.api.ViewInfo
import com.android.tools.rendering.compose.ComposeViewInfo
import com.android.tools.rendering.compose.PxBounds
import com.android.tools.rendering.compose.SourceLocationImpl
import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentTreeExtractorTest {

  private class FakeSourceLocation(
    val sourceFile: String,
    val lineNumber: Int,
    val offset: Int = 0,
    val length: Int = 0,
    val packageHash: Int = 0,
  )

  private class FakeIntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  )

  private class FakePx(val value: Float)

  private class FakePxBounds(
    val left: FakePx,
    val top: FakePx,
    val right: FakePx,
    val bottom: FakePx,
  )

  private class FakeComposeViewInfo(
    val name: String,
    val bounds: Any,
    val fileName: String,
    val lineNumber: Int,
    val location: Any? = null,
    val children: List<FakeComposeViewInfo> = emptyList(),
    val layoutInfo: Any? = null,
  )

  private class FakeComposeViewAdapter(private val infos: List<Any>) {
    fun getViewInfos(): List<Any> = infos
  }

  @Test
  fun testExtractComposeNodeWithIntRectBounds() {
    val node =
      FakeComposeViewInfo(
        name = "androidx.compose.material3.Text",
        bounds = FakeIntRect(left = 10, top = 20, right = 110, bottom = 60),
        fileName = "MainScreen.kt",
        lineNumber = 42,
        location = FakeSourceLocation(sourceFile = "MainScreen.kt", lineNumber = 42, offset = 100, length = 25, packageHash = 999),
      )

    val extracted = ComponentTreeExtractor.parseComposeViewInfo(node)

    assertEquals("androidx.compose.material3.Text", extracted.name)
    assertEquals(10, extracted.bounds.left)
    assertEquals(20, extracted.bounds.top)
    assertEquals(110, extracted.bounds.right)
    assertEquals(60, extracted.bounds.bottom)
    assertEquals(100, extracted.bounds.width)
    assertEquals(40, extracted.bounds.height)

    val loc = extracted.sourceLocation
    assertNotNull(loc)
    assertEquals("MainScreen.kt", loc!!.fileName)
    assertEquals(42, loc.lineNumber)
    assertEquals(999, loc.packageHash)
    assertNull(extracted.children)
  }

  @Test
  fun testExtractComposeNodeWithPxBounds() {
    val node =
      FakeComposeViewInfo(
        name = "androidx.compose.foundation.layout.Box",
        bounds = FakePxBounds(left = FakePx(0f), top = FakePx(5f), right = FakePx(200f), bottom = FakePx(150f)),
        fileName = "Box.kt",
        lineNumber = 15,
        location = null,
      )

    val extracted = ComponentTreeExtractor.parseComposeViewInfo(node)

    assertEquals("androidx.compose.foundation.layout.Box", extracted.name)
    assertEquals(0, extracted.bounds.left)
    assertEquals(5, extracted.bounds.top)
    assertEquals(200, extracted.bounds.right)
    assertEquals(150, extracted.bounds.bottom)
    assertEquals(200, extracted.bounds.width)
    assertEquals(145, extracted.bounds.height)

    val loc = extracted.sourceLocation
    assertNotNull(loc)
    assertEquals("Box.kt", loc!!.fileName)
    assertEquals(15, loc.lineNumber)
    assertNull(extracted.children)
  }

  @Test
  fun testExtractComposeHierarchyWithChildren() {
    val child =
      FakeComposeViewInfo(
        name = "Button",
        bounds = FakeIntRect(10, 10, 50, 30),
        fileName = "Button.kt",
        lineNumber = 5,
        location = null,
      )
    val root =
      FakeComposeViewInfo(
        name = "Column",
        bounds = FakeIntRect(0, 0, 100, 100),
        fileName = "Column.kt",
        lineNumber = 1,
        location = null,
        children = listOf(child),
      )

    val adapter = FakeComposeViewAdapter(listOf(root))
    val extractedList = ComponentTreeExtractor.extractFromComposeAdapter(adapter)
    assertEquals(1, extractedList.size)
    val extracted = extractedList[0]
    assertEquals("Column", extracted.name)
    assertEquals(1, extracted.children?.size)
    val childNode = extracted.children!![0]
    assertEquals("Button", childNode.name)
    assertEquals(40, childNode.bounds.width)
    assertEquals(20, childNode.bounds.height)
    assertNull(childNode.children)
  }

  @Test
  fun testFallbackToLayoutlibViewInfos() {
    val childViewInfo = ViewInfo("android.widget.TextView", null, 10, 20, 90, 50)
    val rootViewInfo = ViewInfo("android.widget.LinearLayout", null, 100, 200, 300, 500)
    rootViewInfo.children = listOf(childViewInfo)

    val extractedList = ComponentTreeExtractor.fallbackToLayoutlibViewInfos(listOf(rootViewInfo))
    assertEquals(1, extractedList.size)
    val rootNode = extractedList[0]
    assertEquals("android.widget.LinearLayout", rootNode.name)
    assertEquals(100, rootNode.bounds.left)
    assertEquals(200, rootNode.bounds.top)
    assertEquals(300, rootNode.bounds.right)
    assertEquals(500, rootNode.bounds.bottom)
    assertEquals(200, rootNode.bounds.width)
    assertEquals(300, rootNode.bounds.height)

    assertEquals(1, rootNode.children?.size)
    val childNode = rootNode.children!![0]
    assertEquals("android.widget.TextView", childNode.name)
    assertEquals(110, childNode.bounds.left)
    assertEquals(220, childNode.bounds.top)
    assertEquals(190, childNode.bounds.right)
    assertEquals(250, childNode.bounds.bottom)
    assertEquals(80, childNode.bounds.width)
    assertEquals(30, childNode.bounds.height)
    assertNull(childNode.children)
  }

  @Test
  fun testComponentTreeJsonSerializationAndRoundTrip() {
    val originalTree =
      PreviewComponentTree(
        previewId = "preview_1",
        methodFQN = "com.example.MyScreenKt.Preview",
        displayName = "Phone Light",
        width = 1080,
        height = 1920,
        densityDpi = 480,
        fontScale = 1.25f,
        uiMode = 16,
        deviceId = "pixel_6",
        components =
          listOf(
            ComponentTreeNode(
              name = "androidx.compose.foundation.layout.Column",
              bounds = ComponentBounds(0, 0, 1080, 1920),
              sourceLocation = ComponentSourceLocation("MyScreen.kt", 12, packageHash = 42),
              children =
                listOf(
                  ComponentTreeNode(
                    name = "androidx.compose.material3.Text",
                    bounds = ComponentBounds(32, 64, 400, 120),
                    sourceLocation = ComponentSourceLocation("MyScreen.kt", 18, packageHash = 42),
                  )
                ),
            )
          ),
      )

    val writer = StringWriter()
    writeComponentTreeToJson(writer, originalTree)
    val jsonString = writer.toString()

    val deserialized = readComponentTreeFromJson(StringReader(jsonString))
    assertEquals(originalTree, deserialized)
    assertTrue("Leaf node should omit children in serialized JSON", !jsonString.contains("\"children\": []"))
    assertTrue("Leaf node should omit null children in serialized JSON", !jsonString.contains("\"children\": null"))
    assertTrue("Non-leaf node should have children array", jsonString.contains("\"children\": ["))
  }

  @Test
  fun testReadComponentTreeFromJsonRejectsMalformedInput() {
    assertThrows(IllegalArgumentException::class.java) {
      readComponentTreeFromJson(StringReader("""{"previewId": "p1"}"""))
    }
    assertThrows(IllegalArgumentException::class.java) {
      readComponentTreeFromJson(
        StringReader(
          """{"previewId": "p1", "methodFQN": "pkg.M", "width": 100, "height": 100, "densityDpi": 420, "fontScale": 1.0, "uiMode": 0, "components": [{"name": "Box"}]}"""
        )
      )
    }
  }

  @Test
  fun testPruningZeroBoundsAndNamelessWrappers() {
    val rememberNode =
      FakeComposeViewInfo(
        name = "remember",
        bounds = FakeIntRect(0, 0, 0, 0),
        fileName = "Composables.kt",
        lineNumber = 50,
      )
    val getCurrentNode =
      FakeComposeViewInfo(
        name = "<get-current>",
        bounds = FakeIntRect(0, 0, 0, 0),
        fileName = "CompositionLocal.kt",
        lineNumber = 120,
      )
    val button =
      FakeComposeViewInfo(
        name = "Button",
        bounds = FakeIntRect(10, 10, 90, 50),
        fileName = "MyScreen.kt",
        lineNumber = 15,
      )
    val lambdaNode =
      FakeComposeViewInfo(
        name = "rememberComposableLambda",
        bounds = FakeIntRect(0, 0, 0, 0),
        fileName = "ComposableLambda.kt",
        lineNumber = 20,
      )
    val namelessWrapper =
      FakeComposeViewInfo(
        name = "",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "",
        lineNumber = -1,
        children = listOf(lambdaNode, button),
      )
    val root =
      FakeComposeViewInfo(
        name = "MyScreen",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "MyScreen.kt",
        lineNumber = 10,
        children = listOf(rememberNode, getCurrentNode, namelessWrapper),
      )

    val adapter = FakeComposeViewAdapter(listOf(root))
    val extractedList = ComponentTreeExtractor.extractFromComposeAdapter(adapter)

    assertEquals(1, extractedList.size)
    val rootNode = extractedList[0]
    assertEquals("MyScreen", rootNode.name)
    assertEquals(1, rootNode.children?.size)

    val childNode = rootNode.children!![0]
    assertEquals("Button", childNode.name)
    assertEquals(ComponentBounds(10, 10, 90, 50), childNode.bounds)
    assertEquals("MyScreen.kt", childNode.sourceLocation?.fileName)
    assertEquals(15, childNode.sourceLocation?.lineNumber)
    assertNull(childNode.children)
  }

  @Test
  fun testCollapsingInternalPrimitivesAndDuplicateBounds() {
    val reusableNode =
      FakeComposeViewInfo(
        name = "ReusableComposeNode",
        bounds = FakeIntRect(10, 10, 100, 40),
        fileName = "Composables.kt",
        lineNumber = 80,
      )
    val layout =
      FakeComposeViewInfo(
        name = "Layout",
        bounds = FakeIntRect(10, 10, 100, 40),
        fileName = "Layout.kt",
        lineNumber = 200,
        children = listOf(reusableNode),
      )
    val basicText =
      FakeComposeViewInfo(
        name = "BasicText",
        bounds = FakeIntRect(10, 10, 100, 40),
        fileName = "BasicText.kt",
        lineNumber = 155,
        children = listOf(layout),
      )
    val text =
      FakeComposeViewInfo(
        name = "Text",
        bounds = FakeIntRect(10, 10, 100, 40),
        fileName = "MyScreen.kt",
        lineNumber = 30,
        children = listOf(basicText),
      )
    val compositionLocalProvider =
      FakeComposeViewInfo(
        name = "CompositionLocalProvider",
        bounds = FakeIntRect(0, 0, 200, 100),
        fileName = "CompositionLocal.kt",
        lineNumber = 100,
        children = listOf(text),
      )
    val box =
      FakeComposeViewInfo(
        name = "Box",
        bounds = FakeIntRect(120, 10, 150, 40),
        fileName = "Box.kt",
        lineNumber = 50,
      )
    val icon =
      FakeComposeViewInfo(
        name = "Icon",
        bounds = FakeIntRect(120, 10, 150, 40),
        fileName = "MyScreen.kt",
        lineNumber = 40,
        children = listOf(box),
      )
    val root =
      FakeComposeViewInfo(
        name = "Card",
        bounds = FakeIntRect(0, 0, 200, 100),
        fileName = "Card.kt",
        lineNumber = 20,
        children = listOf(compositionLocalProvider, icon),
      )

    val adapter = FakeComposeViewAdapter(listOf(root))
    val extractedList = ComponentTreeExtractor.extractFromComposeAdapter(adapter)

    assertEquals(1, extractedList.size)
    val cardNode = extractedList[0]
    assertEquals("Card", cardNode.name)
    assertEquals(2, cardNode.children?.size)

    val textNode = cardNode.children!![0]
    assertEquals("Text", textNode.name)
    assertEquals(ComponentBounds(10, 10, 100, 40), textNode.bounds)
    assertEquals("MyScreen.kt", textNode.sourceLocation?.fileName)
    assertEquals(30, textNode.sourceLocation?.lineNumber)
    assertNull("Internal BasicText and Layout should be collapsed", textNode.children)

    val iconNode = cardNode.children!![1]
    assertEquals("Icon", iconNode.name)
    assertEquals(ComponentBounds(120, 10, 150, 40), iconNode.bounds)
    assertEquals("MyScreen.kt", iconNode.sourceLocation?.fileName)
    assertEquals(40, iconNode.sourceLocation?.lineNumber)
    assertNull("Duplicate-bounds internal Box wrapper should be collapsed", iconNode.children)
  }

  private class FakeSemanticsKey(val name: String)

  private class FakeSemanticsEntry(
    override val key: FakeSemanticsKey,
    override val value: Any?,
  ) : Map.Entry<FakeSemanticsKey, Any?>

  private class FakeSemanticsConfiguration(private val entries: List<FakeSemanticsEntry>) : Iterable<FakeSemanticsEntry> {
    override fun iterator(): Iterator<FakeSemanticsEntry> = entries.iterator()
  }

  private class FakeLayoutNode(
    private val semantics: Any? = null,
    private val modifiers: List<Any> = emptyList(),
  ) {
    fun getSemanticsConfiguration(): Any? = semantics

    fun getModifierInfo(): List<Any> = modifiers
  }

  private class FakeModifierInfo(
    val modifier: Any,
    val extra: Any? = null,
  )

  private class FakeBackgroundElement(
    val color: Long? = null,
    val brush: String? = null,
    val shape: Any? = null,
  )

  private class FakePaddingElement(
    val start: Float = 0f,
    val top: Float = 0f,
    val end: Float = 0f,
    val bottom: Float = 0f,
  )

  private class FakeSizeElement(
    val minWidth: Float = 0f,
    val minHeight: Float = 0f,
    val maxWidth: Float = 0f,
    val maxHeight: Float = 0f,
  )

  private class FakeFillElement(
    val direction: String = "Horizontal",
    val fraction: Float = 1.0f,
  )

  private class FakeShadowElement(
    val elevation: Float = 9f,
    val shape: Any? = null,
    val clip: Boolean = true,
  )

  private class FakeClickableElement(
    val enabled: Boolean = true,
    val role: String? = null,
  )

  private class FakeRowColumnParentData(
    val weight: Float = 1.0f,
    val fill: Boolean = true,
  )

  private class FakeModifierNode

  private class FakeTextView(
    private val text: String,
    private val contentDescription: String? = null,
    private val id: Int = -1,
    private val isClickable: Boolean = false,
  ) {
    fun getText(): CharSequence = text

    fun getContentDescription(): CharSequence? = contentDescription

    fun getId(): Int = id

    fun isClickable(): Boolean = isClickable
  }

  @Test
  fun testExtractSemanticsFromLayoutNode() {
    val semanticsConfig =
      FakeSemanticsConfiguration(
        listOf(
          FakeSemanticsEntry(FakeSemanticsKey("Text"), listOf("Bulbasaur")),
          FakeSemanticsEntry(FakeSemanticsKey("Role"), "Button"),
          FakeSemanticsEntry(FakeSemanticsKey("TestTag"), "pokemon_button"),
          FakeSemanticsEntry(FakeSemanticsKey("OnClick"), Unit),
        )
      )
    val layoutNode = FakeLayoutNode(semantics = semanticsConfig)
    val node =
      FakeComposeViewInfo(
        name = "Button",
        bounds = FakeIntRect(0, 0, 100, 50),
        fileName = "MyScreen.kt",
        lineNumber = 25,
        layoutInfo = layoutNode,
      )

    val extracted = ComponentTreeExtractor.parseComposeViewInfo(node)

    assertNotNull(extracted.semantics)
    val sem = extracted.semantics!!
    assertEquals("Bulbasaur", sem["text"])
    assertEquals("Button", sem["role"])
    assertEquals("pokemon_button", sem["testTag"])
    assertEquals("OnClick", sem["actions"])
  }

  @Test
  fun testExtractModifiersFromLayoutNode() {
    val modifiersList =
      listOf(
        FakeModifierInfo(FakeBackgroundElement(color = 0xFFFF0000L)),
        FakeModifierInfo(FakePaddingElement(start = 16f, top = 8f, end = 16f, bottom = 8f)),
        FakeModifierInfo(FakeSizeElement(minWidth = 100f, minHeight = 50f, maxWidth = 100f, maxHeight = 50f)),
        FakeModifierInfo(FakeFillElement(direction = "Horizontal", fraction = 1.0f)),
        FakeModifierInfo(FakeShadowElement(elevation = 9f)),
        FakeModifierInfo(FakeClickableElement(enabled = true, role = "Button")),
        FakeModifierInfo(FakeModifierNode(), extra = FakeRowColumnParentData(weight = 2.5f, fill = true)),
      )
    val layoutNode = FakeLayoutNode(modifiers = modifiersList)
    val node =
      FakeComposeViewInfo(
        name = "Box",
        bounds = FakeIntRect(0, 0, 100, 50),
        fileName = "MyScreen.kt",
        lineNumber = 10,
        layoutInfo = layoutNode,
      )

    val extracted = ComponentTreeExtractor.parseComposeViewInfo(node)

    assertNotNull(extracted.modifiers)
    val mods = extracted.modifiers!!
    assertEquals("#FFFF0000", mods["background"])
    assertEquals("start=16.0.dp, top=8.0.dp, end=16.0.dp, bottom=8.0.dp", mods["padding"])
    assertEquals("100.0.dp", mods["width"])
    assertEquals("50.0.dp", mods["height"])
    assertEquals("Horizontal (1.0)", mods["fill"])
    assertTrue(mods["shadow"]!!.contains("elevation=9.0.dp"))
    assertTrue(mods["clickable"]!!.contains("enabled=true"))
    assertEquals("2.5", mods["weight"])
  }

  @Test
  fun testFallbackToLayoutlibViewInfosWithSemantics() {
    val textView = FakeTextView(text = "Submit", contentDescription = "Submit button", id = 123, isClickable = true)
    val viewInfo =
      ViewInfo(
        "android.widget.TextView",
        null,
        10,
        20,
        90,
        50,
        textView,
        null,
        null,
      )

    val extractedList = ComponentTreeExtractor.fallbackToLayoutlibViewInfos(listOf(viewInfo))
    assertEquals(1, extractedList.size)
    val node = extractedList[0]
    assertNotNull(node.semantics)
    val sem = node.semantics!!
    assertEquals("Submit", sem["text"])
    assertEquals("Submit button", sem["contentDescription"])
    assertEquals("true", sem["clickable"])
  }

  @Test
  fun testIntentionalContainersNotPruned() {
    val text =
      FakeComposeViewInfo(
        name = "Text",
        bounds = FakeIntRect(10, 10, 90, 50),
        fileName = "PokedexDetails.kt",
        lineNumber = 30,
      )
    val row =
      FakeComposeViewInfo(
        name = "Row",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "PokedexDetails.kt",
        lineNumber = 25,
        children = listOf(text),
      )
    val box =
      FakeComposeViewInfo(
        name = "Box",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "PokedexDetails.kt",
        lineNumber = 20,
        children = listOf(row),
      )
    val detailsHeader =
      FakeComposeViewInfo(
        name = "DetailsHeader",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "PokedexDetails.kt",
        lineNumber = 15,
        children = listOf(box),
      )
    val previewTheme =
      FakeComposeViewInfo(
        name = "PokedexPreviewTheme",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "PokedexDetails.kt",
        lineNumber = 10,
        children = listOf(detailsHeader),
      )

    val adapter = FakeComposeViewAdapter(listOf(previewTheme))
    val extracted = ComponentTreeExtractor.extractFromComposeAdapter(adapter)

    assertEquals(1, extracted.size)
    val themeNode = extracted[0]
    assertEquals("PokedexPreviewTheme", themeNode.name)
    assertEquals(1, themeNode.children?.size)

    val headerNode = themeNode.children!![0]
    assertEquals("DetailsHeader", headerNode.name)
    assertEquals(1, headerNode.children?.size)

    val boxNode = headerNode.children!![0]
    assertEquals("Box", boxNode.name)
    val rowNode = boxNode.children!![0]
    assertEquals("Row", rowNode.name)
  }

  @Test
  fun testThemeImplementationDetailsCollapsedAcrossFiles() {
    val text =
      FakeComposeViewInfo(
        name = "Text",
        bounds = FakeIntRect(10, 10, 90, 50),
        fileName = "PokedexDetails.kt",
        lineNumber = 30,
      )
    val detailsHeader =
      FakeComposeViewInfo(
        name = "DetailsHeader",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "PokedexDetails.kt",
        lineNumber = 20,
        children = listOf(text),
      )
    val themeBox =
      FakeComposeViewInfo(
        name = "Box",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "PokedexTheme.kt",
        lineNumber = 55,
        children = listOf(detailsHeader),
      )
    val themeInner =
      FakeComposeViewInfo(
        name = "PokedexTheme",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "PokedexPreviewTheme.kt",
        lineNumber = 47,
        children = listOf(themeBox),
      )
    val previewTheme =
      FakeComposeViewInfo(
        name = "PokedexPreviewTheme",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "PokedexDetails.kt",
        lineNumber = 10,
        children = listOf(themeInner),
      )

    val adapter = FakeComposeViewAdapter(listOf(previewTheme))
    val extracted = ComponentTreeExtractor.extractFromComposeAdapter(adapter)

    assertEquals(1, extracted.size)
    val root = extracted[0]
    assertEquals("PokedexPreviewTheme", root.name)
    assertEquals(1, root.children?.size)

    // Intermediate PokedexTheme and Box from other files should be collapsed into DetailsHeader
    val child = root.children!![0]
    assertEquals("DetailsHeader", child.name)
    assertEquals(1, child.children?.size)
    assertEquals("Text", child.children!![0].name)
  }

  private class FakeParameter(
    val name: String,
    val value: Any? = null,
    val inlineClass: String? = null,
  )

  private class FakeFontWeight(val weight: Int)

  private class FakeSlotGroup(
    val name: String,
    val parameters: List<Any> = emptyList(),
    val data: List<Any> = emptyList(),
    val location: Any? = null,
    val box: Any? = null,
    val children: List<Any> = emptyList(),
  )

  @Test
  fun testFormatParamValueUnspecifiedSentinelsAndColorTransparent() {
    assertEquals("#00000000", ComposeSemanticsExtractor.formatColorValue(0L, null))
    assertNull(ComposeSemanticsExtractor.formatColorValue(16L, null))
    assertNull(ComposeSemanticsExtractor.formatColorValue(0x1000000000L, null))
    assertEquals("#FFFFFFFF", ComposeSemanticsExtractor.formatColorValue(-4294967296L, null))

    assertNull(ComposeParameterExtractor.formatTextUnitValue(0L, null))
    assertNull(ComposeParameterExtractor.formatTextUnitValue(2143289344L, null))
    assertEquals("18.0.sp", ComposeParameterExtractor.formatTextUnitValue(5394923520L, null))

    assertNull(ComposeParameterExtractor.formatParamValue("style", "TextStyle(fontSize=Unspecified)", null, null))
    assertNull(ComposeParameterExtractor.formatParamValue("maxLines", 2147483647, null, null))
    assertNull(ComposeParameterExtractor.formatParamValue("minLines", 1, null, null))
    assertNull(ComposeParameterExtractor.formatParamValue("softWrap", true, null, null))
    assertNull(ComposeParameterExtractor.formatParamValue("overflow", 1, null, null))
    assertEquals(
      "Ellipsis",
      ComposeParameterExtractor.formatParamValue(
        "overflow",
        2,
        "androidx.compose.ui.text.style.TextOverflow",
        null,
      ),
    )
    assertEquals("a@beefed", ComposeParameterExtractor.formatParamValue("email", "a@beefed", null, null))
    assertEquals("contact@cafe12.com", ComposeParameterExtractor.formatParamValue("email", "contact@cafe12.com", null, null))
    assertNull(
      ComposeParameterExtractor.formatParamValue(
        "brush",
        "androidx.compose.ui.graphics.SolidColor@1a2b3c",
        null,
        null,
      )
    )
  }

  @Test
  fun testExtractParametersFromGroupExplicitParameters() {
    val rawParams =
      listOf(
        FakeParameter("text", value = "Bulbasaur"),
        FakeParameter("modifier", value = "Modifier"),
        FakeParameter("color", value = -4294967296L, inlineClass = "androidx.compose.ui.graphics.Color"),
        FakeParameter("backgroundColor", value = 0L, inlineClass = "androidx.compose.ui.graphics.Color"),
        FakeParameter("fontSize", value = 5394923520L, inlineClass = "androidx.compose.ui.unit.TextUnit"),
        FakeParameter("fontWeight", value = FakeFontWeight(700)),
      )
    val group =
      FakeSlotGroup(
        name = "Text",
        parameters = rawParams,
      )

    val extracted = ComposeParameterExtractor.extractParametersFromGroup(group, null)
    assertEquals("Bulbasaur", extracted["text"])
    assertEquals("#FFFFFFFF", extracted["color"])
    assertEquals("#00000000", extracted["backgroundColor"])
    assertEquals("18.0.sp", extracted["fontSize"])
    assertEquals("FontWeight(700)", extracted["fontWeight"])
    assertNull(extracted["modifier"])
  }

  @Test
  fun testFormatShape() {
    val rectShape =
      object {
        override fun toString() = "RectangleShape"
      }
    assertEquals("", ComposeSemanticsExtractor.formatShape(rectShape))

    val customRect =
      object : Any() {
        override fun toString() = "CustomRectangleShape"
      }
    assertEquals("", ComposeSemanticsExtractor.formatShape(customRect))

    val roundedShape =
      object {
        override fun toString() = "RoundedCornerShape(8.0.dp)"
      }
    assertEquals("RoundedCornerShape(8.0.dp)", ComposeSemanticsExtractor.formatShape(roundedShape))

    assertEquals("", ComposeSemanticsExtractor.formatShape(null))
  }

  @Test
  fun testParamLookupBoundsPriorityOverLocation() {
    val lookup = ComposeParameterExtractor.ParamLookup()
    val bounds1 = ComponentBounds(0, 0, 50, 20)
    val bounds2 = ComponentBounds(60, 0, 110, 20)
    val params1 = mapOf("text" to "Bulbasaur")
    val params2 = mapOf("text" to "Ivysaur")

    // Both on line 42 of MainScreen.kt
    lookup.put("MainScreen.kt", 42, "Text", bounds1, params1)
    lookup.put("MainScreen.kt", 42, "Text", bounds2, params2)

    // Exact match for bounds2
    val foundExact = lookup.findParameters("MainScreen.kt", 42, "Text", bounds2)
    assertEquals("Ivysaur", foundExact?.get("text"))

    // Bounds match even if line number does not match
    val foundBoundsOnly = lookup.findParameters("MainScreen.kt", 99, "Text", bounds2)
    assertEquals("Ivysaur", foundBoundsOnly?.get("text"))

    // Bounds do not match, and location has multiple items -> should return null
    val nonExistentBounds = ComponentBounds(200, 200, 250, 220)
    val foundAmbiguous = lookup.findParameters("MainScreen.kt", 42, "Text", nonExistentBounds)
    assertNull(foundAmbiguous)

    // Location with single unique item falls back when bounds differ slightly
    val lookupSingle = ComposeParameterExtractor.ParamLookup()
    val boundsSingle = ComponentBounds(0, 0, 100, 30)
    val singleParams = mapOf("text" to "Unique")
    lookupSingle.put("MainScreen.kt", 10, "Text", boundsSingle, singleParams)

    val foundSingleFallback = lookupSingle.findParameters("MainScreen.kt", 10, "Text", nonExistentBounds)
    assertEquals("Unique", foundSingleFallback?.get("text"))
  }

  @Test
  fun testMultiFileUserComposablesPreserved() {
    val text =
      FakeComposeViewInfo(
        name = "Text",
        bounds = FakeIntRect(10, 10, 90, 40),
        fileName = "HeaderSection.kt",
        lineNumber = 25,
      )
    val column =
      FakeComposeViewInfo(
        name = "Column",
        bounds = FakeIntRect(0, 0, 100, 100),
        fileName = "HeaderSection.kt",
        lineNumber = 20,
        children = listOf(text),
      )
    val headerSection =
      FakeComposeViewInfo(
        name = "HeaderSection",
        bounds = FakeIntRect(0, 0, 100, 100),
        fileName = "HomeScreen.kt",
        lineNumber = 18,
        children = listOf(column),
      )
    val homeScreen =
      FakeComposeViewInfo(
        name = "HomeScreen",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "HomePreviews.kt",
        lineNumber = 12,
        children = listOf(headerSection),
      )

    val adapter = FakeComposeViewAdapter(listOf(homeScreen))
    val extracted = ComponentTreeExtractor.extractFromComposeAdapter(adapter)
    assertEquals(1, extracted.size)
    val root = extracted[0]
    assertEquals("HomeScreen", root.name)
    assertEquals(1, root.children?.size)

    val headerNode = root.children!![0]
    assertEquals("HeaderSection", headerNode.name)
    assertEquals(1, headerNode.children?.size)

    val columnNode = headerNode.children!![0]
    assertEquals("Column", columnNode.name)
    assertEquals(1, columnNode.children?.size)
    assertEquals("Text", columnNode.children!![0].name)
  }

  @Test
  fun testThemeNodeDoesNotHoistChildMetadata() {
    val text =
      FakeComposeViewInfo(
        name = "Text",
        bounds = FakeIntRect(10, 10, 90, 40),
        fileName = "MyScreen.kt",
        lineNumber = 30,
      )
    val wrapperLayoutNode =
      FakeLayoutNode(
        semantics = FakeSemanticsConfiguration(listOf(FakeSemanticsEntry(FakeSemanticsKey("TestTag"), "internal_tag"))),
        modifiers = listOf(FakeModifierInfo(FakeBackgroundElement(color = 0xFF123456L))),
      )
    val internalWrapper =
      FakeComposeViewInfo(
        name = "CompositionLocalProvider",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "CompositionLocal.kt",
        lineNumber = 110,
        layoutInfo = wrapperLayoutNode,
        children = listOf(text),
      )
    val appTheme =
      FakeComposeViewInfo(
        name = "AppTheme",
        bounds = FakeIntRect(0, 0, 100, 200),
        fileName = "MyScreen.kt",
        lineNumber = 10,
        children = listOf(internalWrapper),
      )

    val adapter = FakeComposeViewAdapter(listOf(appTheme))
    val extracted = ComponentTreeExtractor.extractFromComposeAdapter(adapter)
    assertEquals(1, extracted.size)
    val themeNode = extracted[0]
    assertEquals("AppTheme", themeNode.name)
    assertNull("Theme node should not inherit internal wrapper semantics", themeNode.semantics)
    assertNull("Theme node should not inherit internal wrapper modifiers", themeNode.modifiers)
    assertEquals(1, themeNode.children?.size)
    assertEquals("Text", themeNode.children!![0].name)
  }

  @Test
  fun testSourceInformationFalseKeepsNamedNodes() {
    val textWithoutSource =
      FakeComposeViewInfo(
        name = "Text",
        bounds = FakeIntRect(10, 10, 90, 40),
        fileName = "",
        lineNumber = -1,
      )
    val columnWithoutSource =
      FakeComposeViewInfo(
        name = "Column",
        bounds = FakeIntRect(0, 0, 100, 100),
        fileName = "",
        lineNumber = -1,
        children = listOf(textWithoutSource),
      )

    val adapter = FakeComposeViewAdapter(listOf(columnWithoutSource))
    val extracted = ComponentTreeExtractor.extractFromComposeAdapter(adapter)
    assertEquals(1, extracted.size)
    assertEquals("Column", extracted[0].name)
    assertNull(extracted[0].sourceLocation)
    assertEquals(1, extracted[0].children?.size)
    assertEquals("Text", extracted[0].children!![0].name)
  }

  @Test
  fun testExtractFromSharedComposeViewInfoAndPruneInternalNodes() {
    val internalLayout =
      ComposeViewInfo(
        sourceLocation = SourceLocationImpl("Composables.kt", 80, 111),
        bounds = PxBounds(10, 20, 110, 60),
        children = emptyList(),
        name = "ReusableComposeNode",
      )
    val button =
      ComposeViewInfo(
        sourceLocation = SourceLocationImpl("MainScreen.kt", 42, 999),
        bounds = PxBounds(10, 20, 110, 60),
        children = listOf(internalLayout),
        name = "Button",
      )
    val zeroBoundsRemember =
      ComposeViewInfo(
        sourceLocation = SourceLocationImpl("MainScreen.kt", 38, 999),
        bounds = PxBounds(0, 0, 0, 0),
        children = emptyList(),
        name = "remember",
      )
    val column =
      ComposeViewInfo(
        sourceLocation = SourceLocationImpl("MainScreen.kt", 30, 999),
        bounds = PxBounds(0, 0, 200, 300),
        children = listOf(zeroBoundsRemember, button),
        name = "Column",
      )

    val extracted = ComponentTreeExtractor.extractFromComposeViewInfos(listOf(column))
    assertEquals(1, extracted.size)
    val root = extracted[0]
    assertEquals("Column", root.name)
    assertEquals(ComponentBounds(0, 0, 200, 300), root.bounds)
    assertEquals(ComponentSourceLocation("MainScreen.kt", 30, packageHash = 999), root.sourceLocation)
    assertEquals(1, root.children?.size)

    val child = root.children!![0]
    assertEquals("Button", child.name)
    assertEquals(ComponentBounds(10, 20, 110, 60), child.bounds)
    assertEquals(ComponentSourceLocation("MainScreen.kt", 42, packageHash = 999), child.sourceLocation)
    assertNull(child.children)
  }

  @Test
  fun testWalkSlotGroupPopulatesHierarchicalParamLookup() {
    val childGroup =
      FakeSlotGroup(
        name = "Text",
        parameters = listOf(FakeParameter("text", value = "Charmander")),
        location = FakeSourceLocation(sourceFile = "MainScreen.kt", lineNumber = 55),
        box = FakeIntRect(10, 20, 110, 60),
      )
    val rootGroup =
      FakeSlotGroup(
        name = "Column",
        location = FakeSourceLocation(sourceFile = "MainScreen.kt", lineNumber = 50),
        box = FakeIntRect(0, 0, 200, 300),
        children = listOf(childGroup),
      )
    val lookup = ComposeParameterExtractor.ParamLookup()
    ComposeParameterExtractor.walkSlotGroup(rootGroup, null, lookup)

    val found = lookup.findParameters("MainScreen.kt", 55, "Text", ComponentBounds(10, 20, 110, 60))
    assertNotNull(found)
    assertEquals("Charmander", found!!["text"])
  }

  private class FakeNamelessViewInfo(
    val fileName: String = "",
    val lineNumber: Int = -1,
    val bounds: FakeIntRect,
    val layoutInfo: Any? = null,
    val children: List<Any> = emptyList(),
  )

  private class RowMeasurePolicy

  @Test
  fun testNamelessNodeGroupViewInfoInfersNamesFromLayoutNodeSemanticsAndMeasurePolicy() {
    val textLayoutNode = FakeLayoutNode(semantics = FakeSemanticsConfiguration(listOf(FakeSemanticsEntry(FakeSemanticsKey("Text"), "Ok"))))
    val rowLayoutNode =
      object {
        val measurePolicy: Any = RowMeasurePolicy()
      }
    val buttonLayoutNode =
      FakeLayoutNode(
        semantics =
          FakeSemanticsConfiguration(
            listOf(
              FakeSemanticsEntry(FakeSemanticsKey("Role"), "Role.Button"),
              FakeSemanticsEntry(FakeSemanticsKey("Text"), "Ok"),
            )
          )
      )

    val textNode = FakeNamelessViewInfo(bounds = FakeIntRect(20, 25, 60, 55), layoutInfo = textLayoutNode)
    val rowNode = FakeNamelessViewInfo(bounds = FakeIntRect(10, 20, 80, 60), layoutInfo = rowLayoutNode, children = listOf(textNode))
    val buttonNode = FakeNamelessViewInfo(bounds = FakeIntRect(0, 11, 172, 116), layoutInfo = buttonLayoutNode, children = listOf(rowNode))

    val adapter = FakeComposeViewAdapter(listOf(buttonNode))
    val extracted = ComponentTreeExtractor.extractFromComposeAdapter(adapter)
    assertEquals(1, extracted.size)
    val button = extracted[0]
    assertEquals("Button", button.name)
    assertEquals("Button", button.semantics?.get("role"))
    assertEquals(1, button.children?.size)

    val row = button.children!![0]
    assertEquals("Row", row.name)
    assertEquals(1, row.children?.size)

    val text = row.children!![0]
    assertEquals("Text", text.name)
    assertEquals("Ok", text.semantics?.get("text"))
  }
}
