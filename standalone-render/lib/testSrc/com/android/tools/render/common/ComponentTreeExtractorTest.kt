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
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

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
                    touchBounds = ComponentBounds(32, 52, 400, 132),
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
    assertFalse(deserialized.showSystemUi)
    assertFalse(deserialized.isWearDevice)
    assertFalse(deserialized.isScreenRound)
    assertTrue("Leaf node should omit children in serialized JSON", !jsonString.contains("\"children\": []"))
    assertTrue("Leaf node should omit null children in serialized JSON", !jsonString.contains("\"children\": null"))
    assertTrue("Non-leaf node should have children array", jsonString.contains("\"children\": ["))

    val wearTree = originalTree.copy(showSystemUi = true, isWearDevice = true, isScreenRound = true)
    val wearJson = StringWriter().also { writeComponentTreeToJson(it, wearTree) }.toString()
    assertEquals(wearTree, readComponentTreeFromJson(StringReader(wearJson)))
  }

  /** Moves the bounds and touch bounds of every node, and keeps a null touch area null. */
  @Test
  fun testOffsetByMovesTouchBounds() {
    val icon = ComponentTreeNode("IconButton", ComponentBounds(22, 32, 46, 56), touchBounds = ComponentBounds(10, 20, 58, 68))
    val row = ComponentTreeNode("Row", ComponentBounds(10, 20, 100, 80), children = listOf(icon))
    val tree = PreviewComponentTree(previewId = "p", methodFQN = "com.example.P", width = 100, height = 100, components = listOf(row))

    val movedRow = tree.offsetBy(10, 20).components.single()
    assertEquals(ComponentBounds(0, 0, 90, 60), movedRow.bounds)
    assertNull(movedRow.touchBounds)
    assertEquals(ComponentBounds(12, 12, 36, 36), movedRow.children!!.single().bounds)
    assertEquals(ComponentBounds(0, 0, 48, 48), movedRow.children!!.single().touchBounds)
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

  private class FakeSemanticsConfiguration(
    private val entries: List<FakeSemanticsEntry>,
    private val mergesDescendants: Boolean = false,
  ) : Iterable<FakeSemanticsEntry> {
    override fun iterator(): Iterator<FakeSemanticsEntry> = entries.iterator()

    /** Fakes `SemanticsConfiguration.isMergingSemanticsOfDescendants()`. */
    fun isMergingSemanticsOfDescendants(): Boolean = mergesDescendants
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

  /** Fake `ZIndexElement`, the modifier created by `Modifier.zIndex()`. */
  private class FakeZIndexElement(val zIndex: Float)

  /** Fake Compose `ScrollAxisRange`, with a non-`Function0` callable to match Layoutlib's repackaged lambdas. */
  private class FakeScrollRange(max: Float) {
    val maxValue: Any =
      object {
        @Suppress("unused") fun invoke(): Float = max
      }
  }

  /** Builds a `LayoutNode` fake whose semantics hold [entries], keyed by semantics property name. */
  private fun layoutNodeWith(vararg entries: Pair<String, Any>, mergesDescendants: Boolean = false) =
    FakeLayoutNode(FakeSemanticsConfiguration(entries.map { FakeSemanticsEntry(FakeSemanticsKey(it.first), it.second) }, mergesDescendants))

  /** Reads the Compose accessibility flags: editable, scroll axis, hidden from accessibility, and merged descendants. */
  @Test
  fun testExtractSemanticsReadsComposeAccessibilityFlags() {
    val textField =
      layoutNodeWith(
        "EditableText" to "Hi",
        "IsEditable" to true,
        "VerticalScrollAxisRange" to FakeScrollRange(100f),
        "ScrollBy" to Unit,
        "HideFromAccessibility" to Unit,
        mergesDescendants = true,
      )
    assertEquals(
      mapOf(
        "text" to "Hi",
        "actions" to "ScrollBy",
        "editable" to "true",
        "scrollAxis" to "vertical",
        "importantForAccessibility" to "no",
        "mergingSemantics" to "true",
      ),
      ComposeSemanticsExtractor.extractSemantics(textField),
    )

    // A read-only or disabled 1.7+ text field sets IsEditable = false, which wins over the SetText action.
    val readOnly = layoutNodeWith("EditableText" to "Hi", "IsEditable" to false, "SetText" to Unit)
    assertNull(ComposeSemanticsExtractor.extractSemantics(readOnly)?.get("editable"))

    // Compose 1.6 and older have no IsEditable: every text field has the SetText action.
    val legacyTextField = layoutNodeWith("EditableText" to "Hi", "SetText" to Unit)
    assertEquals("true", ComposeSemanticsExtractor.extractSemantics(legacyTextField)?.get("editable"))
    val plainText = layoutNodeWith("Text" to "Hi")
    assertNull(ComposeSemanticsExtractor.extractSemantics(plainText)?.get("editable"))

    // A horizontal range with nothing to scroll does not count.
    val legacyHidden =
      layoutNodeWith(
        "InvisibleToUser" to Unit,
        "HorizontalScrollAxisRange" to FakeScrollRange(0f),
        "VerticalScrollAxisRange" to FakeScrollRange(10f),
        "ScrollBy" to Unit,
      )
    assertEquals(
      mapOf("actions" to "ScrollBy", "importantForAccessibility" to "no", "scrollAxis" to "vertical"),
      ComposeSemanticsExtractor.extractSemantics(legacyHidden),
    )

    val horizontal = layoutNodeWith("HorizontalScrollAxisRange" to FakeScrollRange(50f), "ScrollBy" to Unit)
    assertEquals("horizontal", ComposeSemanticsExtractor.extractSemantics(horizontal)?.get("scrollAxis"))

    // Disabled scroll containers keep a ScrollAxisRange but omit ScrollBy.
    val disabledScroll = layoutNodeWith("VerticalScrollAxisRange" to FakeScrollRange(100f))
    assertNull(ComposeSemanticsExtractor.extractSemantics(disabledScroll)?.get("scrollAxis"))
  }

  /** Adds up all finite `Modifier.zIndex()` values on a node, like Compose does, and skips a zero total. */
  @Test
  fun testExtractModifiersSumsZIndex() {
    val stacked =
      FakeLayoutNode(
        modifiers =
          listOf(
            FakeModifierInfo(FakeZIndexElement(1.5f)),
            FakeModifierInfo(FakeZIndexElement(Float.NaN)),
            FakeModifierInfo(FakeZIndexElement(2f)),
          )
      )
    assertEquals("3.5", ComposeSemanticsExtractor.extractModifiers(stacked, null)?.get("zIndex"))

    val cancelled = FakeLayoutNode(modifiers = listOf(FakeModifierInfo(FakeZIndexElement(2f)), FakeModifierInfo(FakeZIndexElement(-2f))))
    assertNull(ComposeSemanticsExtractor.extractModifiers(cancelled, null))
  }

  /** Fake Compose `Rect`. */
  private class FakeRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

  /** Fake Compose `SemanticsNode`. */
  private class FakeSemanticsNode(
    private val layoutNode: Any?,
    private val touchBounds: FakeRect,
    private val bounds: FakeRect,
    private val children: List<Any> = emptyList(),
  ) {
    fun getLayoutInfo(): Any? = layoutNode

    fun getTouchBoundsInRoot(): FakeRect = touchBounds

    fun getBoundsInRoot(): FakeRect = bounds

    fun getChildren(): List<Any> = children
  }

  /** Fake `AndroidComposeView` at ([x], [y]) in the window. It also acts as its own `SemanticsOwner`. */
  private class FakeComposeView(private val root: Any, private val x: Int, private val y: Int) {
    fun getSemanticsOwner(): Any = this

    fun getUnmergedRootSemanticsNode(): Any = root

    fun getLocationInWindow(location: IntArray) {
      location[0] = x
      location[1] = y
    }
  }

  /** Fake `ComposeViewAdapter` with view infos and one child view. */
  private class FakeAdapterWithChild(private val infos: List<Any>, private val child: Any) {
    fun getViewInfos(): List<Any> = infos

    fun getChildCount(): Int = 1

    fun getChildAt(index: Int): Any = child
  }

  /** Fake `LayoutNode` whose semantics getter throws, to check that one broken node does not drop the others' touch areas. */
  private class ThrowingLayoutNode {
    /** Always throws, like a reflection failure on a single node. */
    fun getSemanticsConfiguration(): Any? = throw IllegalStateException("broken node")
  }

  /** Returns a fake `LayoutNode` with an `OnClick` action, so its touch area is recorded. */
  private fun clickableLayoutNode() =
    FakeLayoutNode(semantics = FakeSemanticsConfiguration(listOf(FakeSemanticsEntry(FakeSemanticsKey("OnClick"), Unit))))

  /**
   * Records expanded touch areas of clickable nodes clipped to the Compose root in window coordinates, drops 1px rounding shifts and
   * scroll-clipped areas smaller than drawn bounds, keeps touch areas off wrappers with other bounds, and ignores non-clickable nodes.
   */
  @Test
  fun testExtractFromComposeAdapterRecordsTouchBounds() {
    val iconLayoutNode = clickableLayoutNode()
    val edgeIconLayoutNode = clickableLayoutNode()
    val bottomRightEdgeLayoutNode = clickableLayoutNode()
    val tallOnlyLayoutNode = clickableLayoutNode()
    val wideOnlyLayoutNode = clickableLayoutNode()
    val roundedLayoutNode = clickableLayoutNode()
    val scrollClippedLayoutNode = clickableLayoutNode()
    val partialOffscreenLayoutNode = clickableLayoutNode()
    val offscreenLayoutNode = clickableLayoutNode()
    val nonFiniteLayoutNode = clickableLayoutNode()
    val buttonLayoutNode = clickableLayoutNode()
    val decorativeLayoutNode = FakeLayoutNode()
    val buttonBounds = FakeRect(0f, 100f, 200f, 160f)
    val semanticsRoot =
      FakeSemanticsNode(
        layoutNode = null,
        touchBounds = FakeRect(0f, 0f, 300f, 300f),
        bounds = FakeRect(0f, 0f, 300f, 300f),
        children =
          listOf(
            // Centered 24px icon expanded to 48x48px.
            FakeSemanticsNode(iconLayoutNode, touchBounds = FakeRect(0f, 0f, 48f, 48f), bounds = FakeRect(12f, 12f, 36f, 36f)),
            // Top-left edge-aligned 24px icon: raw touch area extends to -12px and must be clipped to the root (0, 0).
            FakeSemanticsNode(edgeIconLayoutNode, touchBounds = FakeRect(-12f, -12f, 36f, 36f), bounds = FakeRect(0f, 0f, 24f, 24f)),
            // Bottom-right edge-aligned 24px icon: raw touch area extends to 312px and must be clipped to the root (300, 300).
            FakeSemanticsNode(
              bottomRightEdgeLayoutNode,
              touchBounds = FakeRect(264f, 264f, 312f, 312f),
              bounds = FakeRect(276f, 276f, 300f, 300f),
            ),
            // Height-only expansion (200x40 -> 200x48).
            FakeSemanticsNode(tallOnlyLayoutNode, touchBounds = FakeRect(0f, 46f, 200f, 94f), bounds = FakeRect(0f, 50f, 200f, 90f)),
            // Width-only expansion (24x48 -> 48x48).
            FakeSemanticsNode(wideOnlyLayoutNode, touchBounds = FakeRect(200f, 50f, 248f, 98f), bounds = FakeRect(212f, 50f, 236f, 98f)),
            // 420dpi odd-pixel centering: 1px wider after rounding (0..127 vs 0..126) must still be ignored.
            FakeSemanticsNode(
              roundedLayoutNode,
              touchBounds = FakeRect(0.4f, 170f, 126.5f, 296f),
              bounds = FakeRect(0f, 170f, 126f, 296f),
            ),
            // Scroll-clipped 120x40 card (visible 0..60 x 4..44): touch shrinks horizontally (26..60) while expanding vertically (0..48),
            // so containment must reject it even though height grew by 8px.
            FakeSemanticsNode(
              scrollClippedLayoutNode,
              touchBounds = FakeRect(26f, 0f, 60f, 48f),
              bounds = FakeRect(50f, 4f, 60f, 44f),
            ),
            // Partially off-screen 24px icon (-12..12): its visible 0..12 slice is expanded to 0..24.
            FakeSemanticsNode(
              partialOffscreenLayoutNode,
              touchBounds = FakeRect(-24f, 0f, 24f, 48f),
              bounds = FakeRect(-12f, 12f, 12f, 36f),
            ),
            // Fully off-screen icon outside the root must not produce a touch area.
            FakeSemanticsNode(offscreenLayoutNode, touchBounds = FakeRect(308f, 0f, 356f, 48f), bounds = FakeRect(320f, 12f, 344f, 36f)),
            // Non-finite Rect coordinates must be ignored without throwing.
            FakeSemanticsNode(nonFiniteLayoutNode, touchBounds = FakeRect(Float.NaN, 0f, 48f, 48f), bounds = FakeRect(12f, 12f, 36f, 36f)),
            // Unchanged touch area.
            FakeSemanticsNode(buttonLayoutNode, touchBounds = buttonBounds, bounds = buttonBounds),
            // Non-clickable icon whose touch area Compose 1.13 still grows (decorative semantics only) must not record touchBounds.
            FakeSemanticsNode(decorativeLayoutNode, touchBounds = FakeRect(100f, 0f, 148f, 48f), bounds = FakeRect(112f, 12f, 136f, 36f)),
          ),
      )
    // The Compose view is 210px down the window, for example below the status bar.
    val iconLayout = FakeComposeViewInfo("Layout", FakeIntRect(12, 222, 36, 246), "IconButton.kt", 1, layoutInfo = iconLayoutNode)
    val iconButton = FakeComposeViewInfo("IconButton", FakeIntRect(12, 222, 36, 246), "Screen.kt", 10, children = listOf(iconLayout))
    val biggerWrapper = FakeComposeViewInfo("Card", FakeIntRect(0, 210, 300, 300), "Screen.kt", 15, children = listOf(iconLayout))
    val edgeIcon = FakeComposeViewInfo("IconButton", FakeIntRect(0, 210, 24, 234), "Screen.kt", 16, layoutInfo = edgeIconLayoutNode)
    val bottomRightEdge =
      FakeComposeViewInfo("IconButton", FakeIntRect(276, 486, 300, 510), "Screen.kt", 17, layoutInfo = bottomRightEdgeLayoutNode)
    val tallOnly = FakeComposeViewInfo("Button", FakeIntRect(0, 260, 200, 300), "Screen.kt", 18, layoutInfo = tallOnlyLayoutNode)
    val wideOnly = FakeComposeViewInfo("IconButton", FakeIntRect(212, 260, 236, 308), "Screen.kt", 19, layoutInfo = wideOnlyLayoutNode)
    val rounded = FakeComposeViewInfo("IconButton", FakeIntRect(0, 380, 126, 506), "Screen.kt", 20, layoutInfo = roundedLayoutNode)
    val scrollClipped = FakeComposeViewInfo("Card", FakeIntRect(-60, 214, 60, 254), "Screen.kt", 21, layoutInfo = scrollClippedLayoutNode)
    val partialOffscreen =
      FakeComposeViewInfo("IconButton", FakeIntRect(-12, 222, 12, 246), "Screen.kt", 22, layoutInfo = partialOffscreenLayoutNode)
    val offscreen = FakeComposeViewInfo("IconButton", FakeIntRect(320, 222, 344, 246), "Screen.kt", 23, layoutInfo = offscreenLayoutNode)
    val nonFinite = FakeComposeViewInfo("IconButton", FakeIntRect(12, 222, 36, 246), "Screen.kt", 24, layoutInfo = nonFiniteLayoutNode)
    val button = FakeComposeViewInfo("Button", FakeIntRect(0, 310, 200, 370), "Screen.kt", 25, layoutInfo = buttonLayoutNode)
    val decorative = FakeComposeViewInfo("Icon", FakeIntRect(112, 222, 136, 246), "Screen.kt", 26, layoutInfo = decorativeLayoutNode)
    val column =
      FakeComposeViewInfo(
        "Column",
        FakeIntRect(0, 210, 300, 510),
        "Screen.kt",
        5,
        children =
          listOf(
            iconButton,
            biggerWrapper,
            edgeIcon,
            bottomRightEdge,
            tallOnly,
            wideOnly,
            rounded,
            scrollClipped,
            partialOffscreen,
            offscreen,
            nonFinite,
            button,
            decorative,
          ),
      )
    val adapter = FakeAdapterWithChild(listOf(column), FakeComposeView(semanticsRoot, x = 0, y = 210))

    val nodes = ComponentTreeExtractor.extractFromComposeAdapter(adapter).single().children!!
    assertEquals(ComponentBounds(0, 210, 48, 258), nodes[0].touchBounds)
    assertNull(nodes[1].touchBounds)
    assertEquals(ComponentBounds(0, 210, 36, 246), nodes[2].touchBounds)
    assertEquals(ComponentBounds(264, 474, 300, 510), nodes[3].touchBounds)
    assertEquals(ComponentBounds(0, 256, 200, 304), nodes[4].touchBounds)
    assertEquals(ComponentBounds(200, 260, 248, 308), nodes[5].touchBounds)
    assertNull(nodes[6].touchBounds)
    assertNull(nodes[7].touchBounds)
    assertEquals(ComponentBounds(0, 210, 24, 258), nodes[8].touchBounds)
    assertNull(nodes[9].touchBounds)
    assertNull(nodes[10].touchBounds)
    assertNull(nodes[11].touchBounds)
    assertNull(nodes[12].touchBounds)
  }

  /** Records no touch areas when the Compose root has zero size: clipping to the empty root drops every touch area. */
  @Test
  fun testExtractFromComposeAdapterIgnoresTouchBoundsForEmptyRoot() {
    val iconLayoutNode = clickableLayoutNode()
    val semanticsRoot =
      FakeSemanticsNode(
        layoutNode = null,
        touchBounds = FakeRect(0f, 0f, 0f, 0f),
        bounds = FakeRect(0f, 0f, 0f, 0f),
        children =
          listOf(FakeSemanticsNode(iconLayoutNode, touchBounds = FakeRect(0f, 0f, 48f, 48f), bounds = FakeRect(12f, 12f, 36f, 36f))),
      )
    val icon = FakeComposeViewInfo("IconButton", FakeIntRect(12, 12, 36, 36), "Screen.kt", 10, layoutInfo = iconLayoutNode)
    val adapter = FakeAdapterWithChild(listOf(icon), FakeComposeView(semanticsRoot, x = 0, y = 0))

    assertNull(ComponentTreeExtractor.extractFromComposeAdapter(adapter).single().touchBounds)
  }

  /**
   * Hoists touch areas from a leaf `NodeGroup` that got a name but shares its parent's `LayoutNode`, and keeps touch areas of padded
   * clickables (non-null `OnClick` / `OnLongClick`) whose semantics bounds already match their touch area. Non-clickable padded nodes and
   * nodes whose `OnClick` value is null get none, and a node whose semantics cannot be read does not drop the others.
   */
  @Test
  fun testExtractFromComposeAdapterHoistsRenamedNodeGroupAndKeepsPaddedClickables() {
    val clickableRowSemantics =
      FakeSemanticsConfiguration(
        listOf(FakeSemanticsEntry(FakeSemanticsKey("Role"), "Button"), FakeSemanticsEntry(FakeSemanticsKey("OnClick"), Unit))
      )
    val onClickSemantics = FakeSemanticsConfiguration(listOf(FakeSemanticsEntry(FakeSemanticsKey("OnClick"), Unit)))
    val onLongClickSemantics = FakeSemanticsConfiguration(listOf(FakeSemanticsEntry(FakeSemanticsKey("OnLongClick"), Unit)))
    val nonClickableSemantics = FakeSemanticsConfiguration(listOf(FakeSemanticsEntry(FakeSemanticsKey("TestTag"), "padded_box")))

    val rowLayoutNode = FakeLayoutNode(semantics = clickableRowSemantics)
    val paddedClickLayoutNode = FakeLayoutNode(semantics = onClickSemantics)
    val paddedLongClickLayoutNode = FakeLayoutNode(semantics = onLongClickSemantics)
    val paddedBoxLayoutNode = FakeLayoutNode(semantics = nonClickableSemantics)
    val nullClickLayoutNode =
      FakeLayoutNode(semantics = FakeSemanticsConfiguration(listOf(FakeSemanticsEntry(FakeSemanticsKey("OnClick"), null))))

    val semanticsRoot =
      FakeSemanticsNode(
        layoutNode = null,
        touchBounds = FakeRect(0f, 0f, 300f, 300f),
        bounds = FakeRect(0f, 0f, 300f, 300f),
        children =
          listOf(
            // A node whose semantics getter throws must not drop the touch areas of the nodes after it.
            FakeSemanticsNode(ThrowingLayoutNode(), touchBounds = FakeRect(0f, 250f, 48f, 298f), bounds = FakeRect(12f, 262f, 36f, 286f)),
            // Multi-child 100x32 clickable Row expanded to 100x48 (shifted +210 -> 202..250).
            FakeSemanticsNode(rowLayoutNode, touchBounds = FakeRect(0f, 2f, 100f, 50f), bounds = FakeRect(0f, 10f, 100f, 42f)),
            // Padded clickable (e.g. Modifier.clickable {}.padding(12.dp).size(24.dp) or RadioButton): semantics bounds == touchBounds
            // (48x48), while ComponentTreeNode.bounds (innerCoordinator) is 24x24.
            FakeSemanticsNode(paddedClickLayoutNode, touchBounds = FakeRect(0f, 100f, 48f, 148f), bounds = FakeRect(0f, 100f, 48f, 148f)),
            // Same for OnLongClick.
            FakeSemanticsNode(
              paddedLongClickLayoutNode,
              touchBounds = FakeRect(200f, 100f, 248f, 148f),
              bounds = FakeRect(200f, 100f, 248f, 148f),
            ),
            // Non-clickable padded container: semantics bounds == touchBounds (56x56) > inner 24x24, must NOT record touchBounds.
            FakeSemanticsNode(
              paddedBoxLayoutNode,
              touchBounds = FakeRect(100f, 100f, 156f, 156f),
              bounds = FakeRect(100f, 100f, 156f, 156f),
            ),
            // Padded node whose OnClick value is null is not clickable, so it must NOT record touchBounds.
            FakeSemanticsNode(nullClickLayoutNode, touchBounds = FakeRect(0f, 200f, 48f, 248f), bounds = FakeRect(0f, 200f, 48f, 248f)),
          ),
      )

    // Real ComposeViewAdapter shape: Row -> Layout -> ReusableComposeNode -> NodeGroup(name = "", layoutInfo = rowLayoutNode).
    // inferNameFromLayoutNode names the NodeGroup "Button", so hoisting must match shared layoutNode as well as internal names.
    val rowChild1 = FakeComposeViewInfo("Icon", FakeIntRect(0, 220, 24, 244), "Screen.kt", 12)
    val rowChild2 = FakeComposeViewInfo("Text", FakeIntRect(30, 220, 80, 244), "Screen.kt", 13)
    val rowNodeGroup =
      FakeComposeViewInfo(
        "",
        FakeIntRect(0, 220, 100, 252),
        "Row.kt",
        1,
        children = listOf(rowChild1, rowChild2),
        layoutInfo = rowLayoutNode,
      )
    val rowReusable =
      FakeComposeViewInfo("ReusableComposeNode", FakeIntRect(0, 220, 100, 252), "Row.kt", 1, children = listOf(rowNodeGroup))
    val rowLayout = FakeComposeViewInfo("Layout", FakeIntRect(0, 220, 100, 252), "Row.kt", 1, children = listOf(rowReusable))
    val row = FakeComposeViewInfo("Row", FakeIntRect(0, 220, 100, 252), "Screen.kt", 10, children = listOf(rowLayout))

    val paddedClick = FakeComposeViewInfo("RadioButton", FakeIntRect(12, 322, 36, 346), "Screen.kt", 20, layoutInfo = paddedClickLayoutNode)
    val paddedLongClick =
      FakeComposeViewInfo("Icon", FakeIntRect(212, 322, 236, 346), "Screen.kt", 21, layoutInfo = paddedLongClickLayoutNode)
    val paddedBox = FakeComposeViewInfo("Box", FakeIntRect(116, 326, 140, 350), "Screen.kt", 22, layoutInfo = paddedBoxLayoutNode)
    val nullClick = FakeComposeViewInfo("Icon", FakeIntRect(12, 422, 36, 446), "Screen.kt", 23, layoutInfo = nullClickLayoutNode)

    val column =
      FakeComposeViewInfo(
        "Column",
        FakeIntRect(0, 210, 300, 510),
        "Screen.kt",
        5,
        children = listOf(row, paddedClick, paddedLongClick, paddedBox, nullClick),
      )
    val adapter = FakeAdapterWithChild(listOf(column), FakeComposeView(semanticsRoot, x = 0, y = 210))

    val nodes = ComponentTreeExtractor.extractFromComposeAdapter(adapter).single().children!!
    assertEquals(ComponentBounds(0, 212, 100, 260), nodes[0].touchBounds)
    assertEquals(ComponentBounds(0, 310, 48, 358), nodes[1].touchBounds)
    assertEquals(ComponentBounds(200, 310, 248, 358), nodes[2].touchBounds)
    assertNull(nodes[3].touchBounds)
    assertNull(nodes[4].touchBounds)
  }

  /**
   * A wrapper that shares its child's `LayoutNode` does not copy the semantics the child took from its own internal child, so semantics do
   * not spread up a chain of full-screen wrappers. (Touch areas still move up; see the renamed `NodeGroup` test above.)
   */
  @Test
  fun testExtractFromComposeAdapterDoesNotHoistSemanticsFromChildSharingLayoutNode() {
    val sharedLayoutNode = FakeLayoutNode()
    val iconSemantics =
      FakeSemanticsConfiguration(
        listOf(
          FakeSemanticsEntry(FakeSemanticsKey("ContentDescription"), listOf("Close")),
          FakeSemanticsEntry(FakeSemanticsKey("OnClick"), Unit),
        )
      )
    val iconLayoutNode = FakeLayoutNode(semantics = iconSemantics)
    val semanticsRoot =
      FakeSemanticsNode(
        layoutNode = null,
        touchBounds = FakeRect(0f, 0f, 300f, 300f),
        bounds = FakeRect(0f, 0f, 300f, 300f),
        children =
          listOf(FakeSemanticsNode(sharedLayoutNode, touchBounds = FakeRect(0f, 0f, 300f, 300f), bounds = FakeRect(0f, 0f, 300f, 300f))),
      )
    // Box takes the icon's semantics from its internal Layout child (same bounds); Surface shares Box's LayoutNode.
    val iconLayout = FakeComposeViewInfo("Layout", FakeIntRect(0, 0, 100, 100), "Icon.kt", 1, layoutInfo = iconLayoutNode)
    val box =
      FakeComposeViewInfo("Box", FakeIntRect(0, 0, 100, 100), "Screen.kt", 10, children = listOf(iconLayout), layoutInfo = sharedLayoutNode)
    val surface =
      FakeComposeViewInfo("Surface", FakeIntRect(0, 0, 100, 100), "Screen.kt", 5, children = listOf(box), layoutInfo = sharedLayoutNode)
    val adapter = FakeAdapterWithChild(listOf(surface), FakeComposeView(semanticsRoot, x = 0, y = 0))

    val surfaceNode = ComponentTreeExtractor.extractFromComposeAdapter(adapter).single()
    val boxNode = surfaceNode.children!!.single()
    assertEquals("Surface", surfaceNode.name)
    assertEquals("Close", boxNode.semantics?.get("contentDescription"))
    assertNull(surfaceNode.semantics)
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

  private class FakeElevatedView(private val elevationValue: Float) {
    /** Returns the simulated view elevation in pixels. */
    fun getElevation(): Float = elevationValue
  }

  /** Verifies that non-zero `View.getElevation()` values are extracted into node semantics while zero elevation is omitted. */
  @Test
  fun testExtractViewSemanticsExtractsNonZeroElevation() {
    val elevatedSemantics = ComposeSemanticsExtractor.extractViewSemantics(FakeElevatedView(12.5f))
    assertNotNull(elevatedSemantics)
    assertEquals("12.5", elevatedSemantics!!["elevation"])

    val flatSemantics = ComposeSemanticsExtractor.extractViewSemantics(FakeElevatedView(0f))
    assertNull(flatSemantics)
  }

  /** Fake `AccessibilityNodeInfo` with the getters read by `extractViewSemantics`. */
  private class FakeNodeInfo(
    private val className: String?,
    private val clickable: Boolean = false,
    private val editable: Boolean = false,
    private val checkable: Boolean = false,
    private val checked: Boolean = false,
    private val scrollable: Boolean = false,
  ) {
    fun getClassName(): CharSequence? = className

    fun isClickable(): Boolean = clickable

    fun isEditable(): Boolean = editable

    fun isCheckable(): Boolean = checkable

    fun isChecked(): Boolean = checked

    fun isScrollable(): Boolean = scrollable
  }

  /** Fake `View` with the accessibility getters read by `extractViewSemantics`. */
  private class FakeA11yView(
    private val info: FakeNodeInfo?,
    private val importantMode: Int = 0,
    private val clickable: Boolean = false,
    private val scrollsVertically: Boolean = false,
    private val scrollsHorizontally: Boolean = false,
    private val textEditor: Boolean = false,
    private val backwardOnly: Boolean = false,
  ) {
    fun createAccessibilityNodeInfo(): FakeNodeInfo? = info

    fun getAccessibilityClassName(): CharSequence = "android.widget.Button"

    fun getImportantForAccessibility(): Int = importantMode

    fun isImportantForAccessibility(): Boolean = false

    fun isClickable(): Boolean = clickable

    fun onCheckIsTextEditor(): Boolean = textEditor

    fun canScrollVertically(direction: Int): Boolean = scrollsVertically

    fun canScrollHorizontally(direction: Int): Boolean = scrollsHorizontally && (!backwardOnly || direction < 0)
  }

  /** Reads the class name and flags from the node info, which wins over plain `View` getters. */
  @Test
  fun testExtractViewSemanticsReadsAccessibilityNodeInfo() {
    val info = FakeNodeInfo("android.widget.Switch", clickable = true, editable = true, checkable = true, scrollable = true)
    val semantics = ComposeSemanticsExtractor.extractViewSemantics(FakeA11yView(info, importantMode = 4, scrollsVertically = true))

    // Checkable but not checked, so the two flags can't be mixed up.
    assertEquals(
      mapOf(
        "accessibilityClassName" to "android.widget.Switch",
        "importantForAccessibility" to "noHideDescendants",
        "clickable" to "true",
        "editable" to "true",
        "checkable" to "true",
        "scrollable" to "true",
        "scrollAxis" to "vertical",
      ),
      semantics,
    )
    val checkedInfo = FakeNodeInfo("android.widget.CheckBox", checkable = true, checked = true)
    assertEquals("true", ComposeSemanticsExtractor.extractViewSemantics(FakeA11yView(checkedInfo))?.get("checked"))

    // A selectable TextView: the node info clears clickable, and clipped text can scroll but is not a scroll container.
    val textInfo = FakeNodeInfo("android.widget.TextView")
    val textSemantics = ComposeSemanticsExtractor.extractViewSemantics(FakeA11yView(textInfo, clickable = true, scrollsVertically = true))
    assertEquals(mapOf("accessibilityClassName" to "android.widget.TextView"), textSemantics)
  }

  /** Reports the scroll axis as `horizontal` or `both`, including for a view that can only scroll backward. */
  @Test
  fun testExtractViewSemanticsReadsScrollAxis() {
    val info = FakeNodeInfo("android.widget.HorizontalScrollView", scrollable = true)
    val horizontal = ComposeSemanticsExtractor.extractViewSemantics(FakeA11yView(info, scrollsHorizontally = true))
    assertEquals("horizontal", horizontal?.get("scrollAxis"))

    val both = ComposeSemanticsExtractor.extractViewSemantics(FakeA11yView(info, scrollsVertically = true, scrollsHorizontally = true))
    assertEquals("both", both?.get("scrollAxis"))

    // A view that opens at its scroll end, like an RTL HorizontalScrollView, can only scroll backward.
    val backward = ComposeSemanticsExtractor.extractViewSemantics(FakeA11yView(info, scrollsHorizontally = true, backwardOnly = true))
    assertEquals("horizontal", backward?.get("scrollAxis"))
  }

  /** Falls back to `View` getters when the node info is missing, and for the class name when the node info has none. */
  @Test
  fun testExtractViewSemanticsFallsBackWithoutNodeInfo() {
    val expected = mapOf("accessibilityClassName" to "android.widget.Button", "importantForAccessibility" to "no", "clickable" to "true")
    assertEquals(expected, ComposeSemanticsExtractor.extractViewSemantics(FakeA11yView(null, importantMode = 2, clickable = true)))

    // Node info without a class name: only the class name falls back, and the node info flags are kept.
    val noClassNameInfo = FakeNodeInfo(className = null, editable = true)
    val semantics = ComposeSemanticsExtractor.extractViewSemantics(FakeA11yView(noClassNameInfo, clickable = true))
    assertEquals(mapOf("accessibilityClassName" to "android.widget.Button", "editable" to "true"), semantics)

    val editorSemantics = ComposeSemanticsExtractor.extractViewSemantics(FakeA11yView(null, importantMode = 1, textEditor = true))
    assertEquals("yes", editorSemantics?.get("importantForAccessibility"))
    assertEquals("true", editorSemantics?.get("editable"))
  }

  /** Adds a public method [name] with [descriptor] to this class, emitting its body with [body]. */
  private fun ClassWriter.addMethod(name: String, descriptor: String, body: MethodVisitor.() -> Unit) {
    visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null).apply {
      visitCode()
      body()
      visitMaxs(0, 0)
      visitEnd()
    }
  }

  private fun generateDynamicNodeClassBytes(internalName: String): ByteArray {
    val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)

    // public <init>()
    val initMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    initMv.visitCode()
    initMv.visitVarInsn(Opcodes.ALOAD, 0)
    initMv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    initMv.visitInsn(Opcodes.RETURN)
    initMv.visitMaxs(1, 1)
    initMv.visitEnd()

    // public int customField;
    cw.visitField(Opcodes.ACC_PUBLIC, "customField", "I", null, null).visitEnd()

    // public String getName() { return "DynamicNode"; }
    val getNameMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getName", "()Ljava/lang/String;", null, null)
    getNameMv.visitCode()
    getNameMv.visitLdcInsn("DynamicNode")
    getNameMv.visitInsn(Opcodes.ARETURN)
    getNameMv.visitMaxs(1, 1)
    getNameMv.visitEnd()

    // public java.lang.Iterable getSemanticsConfiguration() { return java.util.Collections.emptyList(); }
    val semMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getSemanticsConfiguration", "()Ljava/lang/Iterable;", null, null)
    semMv.visitCode()
    semMv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
    semMv.visitInsn(Opcodes.ARETURN)
    semMv.visitMaxs(1, 1)
    semMv.visitEnd()

    // View accessibility getters, so extractViewSemantics() caches Method handles from this class.
    cw.addMethod("createAccessibilityNodeInfo", "()Ljava/lang/Object;") {
      visitVarInsn(Opcodes.ALOAD, 0)
      visitInsn(Opcodes.ARETURN)
    }
    cw.addMethod("getClassName", "()Ljava/lang/CharSequence;") {
      visitLdcInsn("DynamicView")
      visitInsn(Opcodes.ARETURN)
    }
    cw.addMethod("isScrollable", "()Z") {
      visitInsn(Opcodes.ICONST_1)
      visitInsn(Opcodes.IRETURN)
    }
    cw.addMethod("canScrollVertically", "(I)Z") {
      visitInsn(Opcodes.ICONST_1)
      visitInsn(Opcodes.IRETURN)
    }
    // Compose view getters, so ComposeTouchBounds.extract() caches Method handles from this class.
    cw.addMethod("getSemanticsOwner", "()Ljava/lang/Object;") {
      visitVarInsn(Opcodes.ALOAD, 0)
      visitInsn(Opcodes.ARETURN)
    }
    cw.addMethod("getUnmergedRootSemanticsNode", "()Ljava/lang/Object;") {
      visitVarInsn(Opcodes.ALOAD, 0)
      visitInsn(Opcodes.ARETURN)
    }
    cw.addMethod("getLocationInWindow", "([I)V") { visitInsn(Opcodes.RETURN) }
    // Semantics node and Rect getters, so extract() gets past the root bounds and walks the tree.
    for (getter in listOf("getBoundsInRoot", "getLayoutInfo")) {
      cw.addMethod(getter, "()Ljava/lang/Object;") {
        visitVarInsn(Opcodes.ALOAD, 0)
        visitInsn(Opcodes.ARETURN)
      }
    }
    for (getter in listOf("getLeft", "getTop", "getRight", "getBottom")) {
      cw.addMethod(getter, "()F") {
        visitInsn(Opcodes.FCONST_1)
        visitInsn(Opcodes.FRETURN)
      }
    }

    cw.visitEnd()
    return cw.toByteArray()
  }

  private fun exerciseClassInThrowawayLoader(
    className: String,
    action: (Class<*>, Any) -> Unit,
  ): Pair<WeakReference<ClassLoader>, WeakReference<Class<*>>> {
    val internalName = className.replace('.', '/')
    val bytes = generateDynamicNodeClassBytes(internalName)
    val loader =
      object : ClassLoader(null) {
        fun define(name: String, b: ByteArray): Class<*> = defineClass(name, b, 0, b.size)
      }
    val clazz = loader.define(className, bytes)
    val instance = clazz.getDeclaredConstructor().newInstance()
    action(clazz, instance)
    return WeakReference<ClassLoader>(loader) to WeakReference(clazz)
  }

  private fun assertClassLoaderCollected(weakLoader: WeakReference<ClassLoader>, weakClass: WeakReference<Class<*>>) {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadlineNanos) {
      if (weakLoader.get() == null && weakClass.get() == null) break
      System.gc()
      Thread.sleep(10)
    }
    assertNull("Throwaway ClassLoader should be garbage collected after dropping references", weakLoader.get())
    assertNull("Class loaded by throwaway ClassLoader should be garbage collected", weakClass.get())
  }

  @Test
  fun testComponentTreeExtractorDoesNotRetainClassLoader() {
    val (weakLoader, weakClass) =
      exerciseClassInThrowawayLoader("com.example.test.DynamicComponentNode") { clazz, instance ->
        assertEquals("DynamicNode", ComponentTreeExtractor.invokeGetter(instance, "name"))
        assertNotNull(ComponentTreeExtractor.findZeroArgMethod(clazz, "getName"))
        assertNotNull(ComponentTreeExtractor.findField(clazz, "customField"))
        assertNull(ComponentTreeExtractor.findField(clazz, "nonExistentField"))
      }
    assertClassLoaderCollected(weakLoader, weakClass)
  }

  @Test
  fun testComposeSemanticsExtractorDoesNotRetainClassLoader() {
    val (weakLoader, weakClass) =
      exerciseClassInThrowawayLoader("com.example.test.DynamicSemanticsNode") { _, instance ->
        assertNull(ComposeSemanticsExtractor.extractSemantics(instance))
        assertEquals("vertical", ComposeSemanticsExtractor.extractViewSemantics(instance)?.get("scrollAxis"))
        assertNull(ComposeTouchBounds.extract(instance).findExpandedBounds(instance, ComponentBounds(0, 0, 1, 1)))
      }
    assertClassLoaderCollected(weakLoader, weakClass)
  }
}
