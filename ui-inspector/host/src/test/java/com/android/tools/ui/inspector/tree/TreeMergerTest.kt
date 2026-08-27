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

package com.android.tools.ui.inspector.tree

import com.android.tools.ui.inspector.model.UiNode
import com.google.common.truth.Truth.assertThat
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import org.junit.Test

class TreeMergerTest {

  @Test
  fun testAttachComposeTreeGraftsNodesCorrectly() {
    // 1. Setup standard View tree:
    // Root (FrameLayout)
    //   -> AndroidComposeView (id = 123)
    //   -> OtherView (id = 456)
    val rootNode =
      UiNode.ViewNode(
        id = 1,
        className = "android.widget.FrameLayout",
        bounds = UiNode.Bounds(0, 0, 1080, 1920),
        idResource = "root",
        layoutResource = "main_layout",
        attributes = emptyList(),
        children =
          mutableListOf(
            UiNode.ViewNode(
              id = 123,
              className = "androidx.compose.ui.platform.AndroidComposeView",
              bounds = UiNode.Bounds(0, 0, 1080, 1000),
              idResource = null,
              layoutResource = null,
              attributes = emptyList(),
              children = mutableListOf(),
            ),
            UiNode.ViewNode(
              id = 456,
              className = "android.widget.Button",
              bounds = UiNode.Bounds(0, 1000, 1080, 1920),
              idResource = "submit_btn",
              layoutResource = null,
              attributes = emptyList(),
              children = mutableListOf(),
            ),
          ),
      )

    // 2. Setup Composable children nodes to graft:
    // Composable Column (id = 200)
    //   -> Text (id = 201)
    val stringTable = mapOf(1 to "Column", 2 to "Text", 3 to "MainActivity.kt")
    val composeNodes: List<LayoutInspectorComposeProtocol.ComposableNode> =
      listOf(
        LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
          .setId(200)
          .setName(1) // Column
          .setFilename(3) // MainActivity.kt
          .setLineNumber(10)
          .setBounds(
            LayoutInspectorComposeProtocol.Bounds.newBuilder()
              .setLayout(LayoutInspectorComposeProtocol.Rect.newBuilder().setX(0).setY(0).setW(1080).setH(500))
          )
          .addChildren(
            LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
              .setId(201)
              .setName(2) // Text
              .setBounds(
                LayoutInspectorComposeProtocol.Bounds.newBuilder()
                  .setLayout(LayoutInspectorComposeProtocol.Rect.newBuilder().setX(50).setY(50).setW(200).setH(50))
              )
          )
          .build()
      )

    // Build mock parameters response for Composable node ID 201 (Text)
    val mockAllParamsResponse =
      LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
        .addParameterGroups(
          LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(201)
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(3) // index for "text"
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(4) // index for "Hello"
            )
        )
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("text"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("Hello"))
        .build()

    // 3. Execute grafting
    val wasAttached =
      attachComposeTree(
        viewNode = rootNode,
        targetViewId = 123,
        composeNodes = composeNodes,
        stringTable = stringTable,
        viewsToSkip = emptyList(),
        parameters = mockAllParamsResponse,
        includeParameters = true,
        includeSemantics = true,
      )

    // 4. Assertions
    assertThat(wasAttached).isTrue()

    // Verify that FrameLayout child count is still 2
    assertThat(rootNode.children).hasSize(2)

    // Verify that the target AndroidComposeView now has 1 grafted ComposeNode child
    val composeView = rootNode.children[0] as UiNode.ViewNode
    assertThat(composeView.children).hasSize(1)

    val graftedRoot = composeView.children[0] as UiNode.ComposeNode
    assertThat(graftedRoot.id).isEqualTo(200)
    assertThat(graftedRoot.className).isEqualTo("Column")
    assertThat(graftedRoot.bounds.width).isEqualTo(1080)
    assertThat(graftedRoot.sourceLocation?.filename).isEqualTo("MainActivity.kt")
    assertThat(graftedRoot.sourceLocation?.lineNumber).isEqualTo(10)

    // Verify recursive children grafting
    assertThat(graftedRoot.children).hasSize(1)
    val graftedText = graftedRoot.children[0] as UiNode.ComposeNode
    assertThat(graftedText.id).isEqualTo(201)
    assertThat(graftedText.className).isEqualTo("Text")

    // Verify parameters are successfully converted and grafted on Composable node
    assertThat(graftedText.parameters).hasSize(1)
    val param = graftedText.parameters[0] as UiNode.ComposeParameter.Single
    assertThat(param.name).isEqualTo("text")
    assertThat(param.value).isEqualTo(UiNode.ComposeParameter.Value.StringVal("Hello"))

    // Verify that Button (OtherView) has zero grafted children
    val buttonView = rootNode.children[1] as UiNode.ViewNode
    assertThat(buttonView.children).isEmpty()
  }

  @Test
  fun testAttachComposeTreeAlignsBoundsInNonOriginWindow() {
    // The target view's origin deliberately differs from the window root's: the render offset must come from the window root.
    val target =
      UiNode.ViewNode(
        id = 123,
        className = "androidx.compose.ui.platform.AndroidComposeView",
        bounds = UiNode.Bounds(350, 450, 200, 200),
        idResource = null,
        layoutResource = null,
        attributes = emptyList(),
      )
    val rootNode =
      UiNode.ViewNode(
        id = 1,
        className = "android.widget.FrameLayout",
        bounds = UiNode.Bounds(300, 400, 500, 600),
        idResource = null,
        layoutResource = null,
        attributes = emptyList(),
        children = mutableListOf(target),
      )
    val composeNodes =
      listOf(
        LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
          .setId(200)
          .setName(1)
          .setBounds(
            LayoutInspectorComposeProtocol.Bounds.newBuilder()
              .setRender(
                LayoutInspectorComposeProtocol.Quad.newBuilder()
                  .setX0(10)
                  .setY0(20)
                  .setX1(50)
                  .setY1(10)
                  .setX2(60)
                  .setY2(40)
                  .setX3(20)
                  .setY3(50)
              )
          )
          .build(),
        LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
          .setId(201)
          .setName(2)
          .setBounds(
            LayoutInspectorComposeProtocol.Bounds.newBuilder()
              .setLayout(LayoutInspectorComposeProtocol.Rect.newBuilder().setX(320).setY(430).setW(20).setH(10))
          )
          .build(),
      )

    val wasAttached =
      attachComposeTree(
        viewNode = rootNode,
        targetViewId = 123,
        composeNodes = composeNodes,
        stringTable = mapOf(1 to "Rotated", 2 to "Fallback"),
        viewsToSkip = emptyList(),
        parameters = null,
        includeParameters = false,
        includeSemantics = false,
      )

    assertThat(wasAttached).isTrue()
    assertThat((target.children[0] as UiNode.ComposeNode).bounds).isEqualTo(UiNode.Bounds(310, 410, 50, 40))
    assertThat((target.children[1] as UiNode.ComposeNode).bounds).isEqualTo(UiNode.Bounds(320, 430, 20, 10))
  }

  @Test
  fun testAttachComposeTreeSkipsNonMatchingId() {
    val rootNode =
      UiNode.ViewNode(
        id = 1,
        className = "android.widget.FrameLayout",
        bounds = UiNode.Bounds(0, 0, 1080, 1920),
        idResource = "root",
        layoutResource = null,
        attributes = emptyList(),
        children = mutableListOf(),
      )

    val composeNodes = listOf(LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(200).setName(1).build())

    // Try grafting to a non-existent target view id 999
    val wasAttached =
      attachComposeTree(
        viewNode = rootNode,
        targetViewId = 999,
        composeNodes = composeNodes,
        stringTable = mapOf(1 to "Column"),
        viewsToSkip = emptyList(),
        parameters = null,
        includeParameters = true,
        includeSemantics = true,
      )

    // Must skip grafting because the view id 999 does not exist in the View tree!
    assertThat(wasAttached).isFalse()
    assertThat(rootNode.children).isEmpty()
  }

  @Test
  fun testAttachComposeTreeFiltersOutViewsToSkip() {
    // 1. Setup standard View tree:
    // Root (FrameLayout)
    //   -> AndroidComposeView (id = 123)
    //        -> ShadowView (id = 777) -- should be skipped
    //        -> NormalView (id = 888) -- should keep
    val rootNode =
      UiNode.ViewNode(
        id = 1,
        className = "android.widget.FrameLayout",
        bounds = UiNode.Bounds(0, 0, 1080, 1920),
        idResource = "root",
        layoutResource = "main_layout",
        attributes = emptyList(),
        children =
          mutableListOf(
            UiNode.ViewNode(
              id = 123,
              className = "androidx.compose.ui.platform.AndroidComposeView",
              bounds = UiNode.Bounds(0, 0, 1080, 1000),
              idResource = null,
              layoutResource = null,
              attributes = emptyList(),
              children =
                mutableListOf(
                  UiNode.ViewNode(
                    id = 777,
                    className = "android.view.View", // e.g., paint shadow
                    bounds = UiNode.Bounds(0, 0, 1080, 1000),
                    idResource = null,
                    layoutResource = null,
                    attributes = emptyList(),
                    children = mutableListOf(),
                  ),
                  UiNode.ViewNode(
                    id = 888,
                    className = "android.widget.TextView",
                    bounds = UiNode.Bounds(100, 100, 500, 200),
                    idResource = null,
                    layoutResource = null,
                    attributes = emptyList(),
                    children = mutableListOf(),
                  ),
                ),
            )
          ),
      )

    // 2. Setup Composable children nodes to graft:
    val stringTable = mapOf(1 to "Column")
    val composeNodes =
      listOf(
        LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
          .setId(200)
          .setName(1) // Column
          .setBounds(
            LayoutInspectorComposeProtocol.Bounds.newBuilder()
              .setLayout(LayoutInspectorComposeProtocol.Rect.newBuilder().setX(0).setY(0).setW(1080).setH(500))
          )
          .build()
      )

    // 3. Execute grafting with viewsToSkip list containing 777
    val wasAttached =
      attachComposeTree(
        viewNode = rootNode,
        targetViewId = 123,
        composeNodes = composeNodes,
        stringTable = stringTable,
        viewsToSkip = listOf(777L),
        parameters = null,
        includeParameters = true,
        includeSemantics = true,
      )

    // 4. Assertions
    assertThat(wasAttached).isTrue()

    val composeView = rootNode.children[0] as UiNode.ViewNode
    // The children of AndroidComposeView should be:
    // - NormalView (id = 888)
    // - Grafted ComposeNode (id = 200)
    // The ShadowView (id = 777) must be skipped (removed)
    assertThat(composeView.children).hasSize(2)

    val child1 = composeView.children[0] as UiNode.ViewNode
    assertThat(child1.id).isEqualTo(888)

    val child2 = composeView.children[1] as UiNode.ComposeNode
    assertThat(child2.id).isEqualTo(200)
  }

  @Test
  fun testHostedViewGraftedThroughAndroidViewsHandler() {
    // The shape Compose actually produces: the hosted subtree sits under an AndroidViewsHandler carrier, and
    // ComposableNode.view_id references the handler's direct child (the ViewFactoryHolder), not the payload.
    val rootNode =
      view(
        1,
        "FrameLayout",
        view(10, "AndroidComposeView", view(11, "AndroidViewsHandler", view(12, "ViewFactoryHolder", view(13, "TextView")))),
      )
    val composeNodes = listOf(composable(100, COLUMN, composable(101, ANDROID_VIEW, viewId = 12)))

    val wasAttached = attach(rootNode, targetViewId = 10, composeNodes = composeNodes)

    assertThat(wasAttached).isTrue()
    val composeView = rootNode.children[0] as UiNode.ViewNode
    // The emptied AndroidViewsHandler is gone; the grafted Compose root is the only child.
    assertThat(composeView.children.map { it.id }).containsExactly(100L)
    val androidView = (composeView.children[0] as UiNode.ComposeNode).children[0] as UiNode.ComposeNode
    assertThat(androidView.id).isEqualTo(101)
    // The whole holder subtree moved under the AndroidView composable.
    val holder = androidView.children.single() as UiNode.ViewNode
    assertThat(holder.id).isEqualTo(12)
    assertThat((holder.children.single() as UiNode.ViewNode).id).isEqualTo(13)
    assertThat(viewIds(rootNode)).containsExactly(1L, 10L, 12L, 13L)
    assertThat(composeIds(rootNode)).containsExactly(100L, 101L)
  }

  @Test
  fun testHostedViewGraftedWhenTargetIsAncestorOfComposeView() {
    // The lean fetch currently anchors the Compose root above the AndroidComposeView (e.g. the DecorView), so carrier
    // discovery must search the whole target subtree. This pins hosted grafting for that shape, not the anchoring itself.
    val rootNode =
      view(
        1,
        "DecorView",
        view(
          2,
          "LinearLayout",
          view(10, "AndroidComposeView", view(11, "AndroidViewsHandler", view(12, "ViewFactoryHolder", view(13, "TextView")))),
        ),
      )
    val composeNodes = listOf(composable(100, COLUMN, composable(101, ANDROID_VIEW, viewId = 12)))

    val wasAttached = attach(rootNode, targetViewId = 1, composeNodes = composeNodes)

    assertThat(wasAttached).isTrue()
    val androidView = findCompose(rootNode, 101)
    assertThat((androidView.children.single() as UiNode.ViewNode).id).isEqualTo(12)
    assertThat(viewIds(rootNode)).containsExactly(1L, 2L, 10L, 12L, 13L)
  }

  @Test
  fun testLegacyHostedShapeWithoutHolderIsGrafted() {
    // Older Compose versions place the payload View directly under the handler; view_id then references the payload.
    val rootNode =
      view(1, "FrameLayout", view(10, "AndroidComposeView", view(11, "AndroidViewsHandler", view(12, "Button", view(13, "TextView")))))
    val composeNodes = listOf(composable(100, COLUMN, composable(101, ANDROID_VIEW, viewId = 12)))

    val wasAttached = attach(rootNode, targetViewId = 10, composeNodes = composeNodes)

    assertThat(wasAttached).isTrue()
    val androidView = findCompose(rootNode, 101)
    val payload = androidView.children.single() as UiNode.ViewNode
    assertThat(payload.id).isEqualTo(12)
    assertThat((payload.children.single() as UiNode.ViewNode).id).isEqualTo(13)
    assertThat(viewIds(rootNode)).containsExactly(1L, 10L, 12L, 13L)
  }

  @Test
  fun testMultipleHostedViewsMatchedByIdNotOrder() {
    // Compose order deliberately differs from handler child order: ownership must follow ids.
    val rootNode =
      view(
        1,
        "FrameLayout",
        view(10, "AndroidComposeView", view(11, "AndroidViewsHandler", view(12, "ViewFactoryHolder"), view(13, "ViewFactoryHolder"))),
      )
    val composeNodes =
      listOf(composable(100, COLUMN, composable(101, ANDROID_VIEW, viewId = 13), composable(102, ANDROID_VIEW, viewId = 12)))

    val wasAttached = attach(rootNode, targetViewId = 10, composeNodes = composeNodes)

    assertThat(wasAttached).isTrue()
    assertThat((findCompose(rootNode, 101).children.single() as UiNode.ViewNode).id).isEqualTo(13)
    assertThat((findCompose(rootNode, 102).children.single() as UiNode.ViewNode).id).isEqualTo(12)
    // Each holder appears exactly once and the emptied handler is gone.
    assertThat(viewIds(rootNode)).containsExactly(1L, 10L, 12L, 13L)
  }

  @Test
  fun testPartiallyEmptiedHandlerIsKept() {
    val rootNode =
      view(
        1,
        "FrameLayout",
        view(10, "AndroidComposeView", view(11, "AndroidViewsHandler", view(12, "ViewFactoryHolder"), view(14, "SurfaceView"))),
      )
    val composeNodes = listOf(composable(100, COLUMN, composable(101, ANDROID_VIEW, viewId = 12)))

    val wasAttached = attach(rootNode, targetViewId = 10, composeNodes = composeNodes)

    assertThat(wasAttached).isTrue()
    val composeView = rootNode.children[0] as UiNode.ViewNode
    // The handler keeps its unmatched child.
    val handler = composeView.children.filterIsInstance<UiNode.ViewNode>().single { it.id == 11L }
    assertThat(handler.children.map { it.id }).containsExactly(14L)
    assertThat((findCompose(rootNode, 101).children.single() as UiNode.ViewNode).id).isEqualTo(12)
  }

  @Test
  fun testNestedInteropAttachesInEitherOrder() {
    // ComposeView inside a hosted AndroidView: the inner AndroidComposeView is only reachable through the grafted
    // Compose subtree once the outer root is attached, so attachment must be order-independent.
    val outer = ComposeRootFixture(targetViewId = 10, nodes = listOf(composable(100, COLUMN, composable(101, ANDROID_VIEW, viewId = 12))))
    val inner = ComposeRootFixture(targetViewId = 15, nodes = listOf(composable(200, TEXT)))

    val outerFirst = nestedFixture()
    assertThat(attach(outerFirst, outer.targetViewId, outer.nodes)).isTrue()
    assertThat(attach(outerFirst, inner.targetViewId, inner.nodes)).isTrue()

    val innerFirst = nestedFixture()
    assertThat(attach(innerFirst, inner.targetViewId, inner.nodes)).isTrue()
    assertThat(attach(innerFirst, outer.targetViewId, outer.nodes)).isTrue()

    assertThat(parentPairs(innerFirst)).isEqualTo(parentPairs(outerFirst))
    // The inner Compose tree hangs off the inner AndroidComposeView, which stays inside the hosted subtree.
    assertThat(parentPairs(outerFirst))
      .containsAllOf("C200" to "V15", "V15" to "V14", "V14" to "V13", "V13" to "V12", "V12" to "C101", "C101" to "C100", "C100" to "V10")
    assertThat(viewIds(outerFirst)).containsExactly(1L, 10L, 12L, 13L, 14L, 15L)
    assertThat(composeIds(outerFirst)).containsExactly(100L, 101L, 200L)
  }

  @Test
  fun testThreeLevelNestingIsOrderIndependent() {
    val roots =
      listOf(
        ComposeRootFixture(targetViewId = 10, nodes = listOf(composable(100, COLUMN, composable(101, ANDROID_VIEW, viewId = 12)))),
        ComposeRootFixture(targetViewId = 15, nodes = listOf(composable(200, BOX, composable(201, ANDROID_VIEW, viewId = 17)))),
        ComposeRootFixture(targetViewId = 20, nodes = listOf(composable(300, TEXT))),
      )

    val expected = threeLevelFixture().also { fixture -> roots.forEach { assertThat(attach(fixture, it.targetViewId, it.nodes)).isTrue() } }

    permutations(roots).forEach { order ->
      val fixture = threeLevelFixture()
      order.forEach { assertThat(attach(fixture, it.targetViewId, it.nodes)).isTrue() }
      assertThat(parentPairs(fixture)).isEqualTo(parentPairs(expected))
      // Both emptied handlers (11, 16) are gone; everything else appears exactly once.
      assertThat(viewIds(fixture)).containsExactly(1L, 10L, 12L, 13L, 14L, 15L, 17L, 18L, 19L, 20L)
      assertThat(composeIds(fixture)).containsExactly(100L, 101L, 200L, 201L, 300L)
    }
  }

  private companion object {
    const val COLUMN = 1
    const val ANDROID_VIEW = 2
    const val TEXT = 3
    const val BOX = 4

    val STRING_TABLE = mapOf(COLUMN to "Column", ANDROID_VIEW to "AndroidView", TEXT to "Text", BOX to "Box")
  }

  private data class ComposeRootFixture(val targetViewId: Long, val nodes: List<LayoutInspectorComposeProtocol.ComposableNode>)

  private fun attach(
    root: UiNode.ViewNode,
    targetViewId: Long,
    composeNodes: List<LayoutInspectorComposeProtocol.ComposableNode>,
  ): Boolean =
    attachComposeTree(
      viewNode = root,
      targetViewId = targetViewId,
      composeNodes = composeNodes,
      stringTable = STRING_TABLE,
      viewsToSkip = emptyList(),
      parameters = null,
      includeParameters = false,
      includeSemantics = false,
    )

  private fun view(id: Long, className: String, vararg children: UiNode): UiNode.ViewNode =
    UiNode.ViewNode(
      id = id,
      className = className,
      bounds = UiNode.Bounds(0, 0, 100, 100),
      idResource = null,
      layoutResource = null,
      attributes = emptyList(),
      children = children.toMutableList(),
    )

  private fun composable(
    id: Long,
    name: Int,
    vararg children: LayoutInspectorComposeProtocol.ComposableNode,
    viewId: Long = 0,
  ): LayoutInspectorComposeProtocol.ComposableNode =
    LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
      .setId(id)
      .setName(name)
      .setViewId(viewId)
      .addAllChildren(children.toList())
      .build()

  /** DecorView > AndroidComposeView(10) > handler(11) > holder(12) > FrameLayout(13) > ComposeView(14) > AndroidComposeView(15). */
  private fun nestedFixture(): UiNode.ViewNode =
    view(
      1,
      "DecorView",
      view(
        10,
        "AndroidComposeView",
        view(
          11,
          "AndroidViewsHandler",
          view(12, "ViewFactoryHolder", view(13, "FrameLayout", view(14, "ComposeView", view(15, "AndroidComposeView")))),
        ),
      ),
    )

  /** [nestedFixture] with a second hosting level inside the inner AndroidComposeView(15), ending in AndroidComposeView(20). */
  private fun threeLevelFixture(): UiNode.ViewNode =
    view(
      1,
      "DecorView",
      view(
        10,
        "AndroidComposeView",
        view(
          11,
          "AndroidViewsHandler",
          view(
            12,
            "ViewFactoryHolder",
            view(
              13,
              "FrameLayout",
              view(
                14,
                "ComposeView",
                view(
                  15,
                  "AndroidComposeView",
                  view(
                    16,
                    "AndroidViewsHandler",
                    view(17, "ViewFactoryHolder", view(18, "FrameLayout", view(19, "ComposeView", view(20, "AndroidComposeView")))),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    )

  private fun findCompose(node: UiNode, id: Long): UiNode.ComposeNode =
    findComposeOrNull(node, id) ?: throw AssertionError("ComposeNode $id not found")

  private fun findComposeOrNull(node: UiNode, id: Long): UiNode.ComposeNode? {
    if (node is UiNode.ComposeNode && node.id == id) return node
    for (child in node.children) {
      findComposeOrNull(child, id)?.let {
        return it
      }
    }
    return null
  }

  /** Pre-order ids of all View nodes in the tree. */
  private fun viewIds(node: UiNode): List<Long> =
    (if (node is UiNode.ViewNode) listOf(node.id) else emptyList()) + node.children.flatMap { viewIds(it) }

  /** Pre-order ids of all Compose nodes in the tree. */
  private fun composeIds(node: UiNode): List<Long> =
    (if (node is UiNode.ComposeNode) listOf(node.id) else emptyList()) + node.children.flatMap { composeIds(it) }

  /** Child-to-parent labels ("V<id>"/"C<id>") for structural equality across attach orders. */
  private fun parentPairs(node: UiNode, parent: String? = null): List<Pair<String, String?>> {
    val label = (if (node is UiNode.ViewNode) "V" else "C") + node.id
    return listOf(label to parent) + node.children.flatMap { parentPairs(it, label) }
  }

  private fun <T> permutations(items: List<T>): List<List<T>> =
    if (items.size <= 1) listOf(items) else items.flatMap { head -> permutations(items - head).map { rest -> listOf(head) + rest } }
}
