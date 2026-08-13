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

class StripSystemComposablesTest {

  @Test
  fun testSystemNodeIsSplicedInPlace() {
    val root = view(1, compose(10), compose(20, isSystemCreated = true, children = arrayOf(compose(21), compose(22))), compose(30))

    val stripped = stripSystemComposables(dump(root))

    // The system node's children take its position, between its former siblings.
    assertThat(stripped.windows.single().root.children.map { it.id }).containsExactly(10L, 21L, 22L, 30L).inOrder()
  }

  @Test
  fun testNestedAndConsecutiveSystemNodesRecurse() {
    val inner = compose(200, isSystemCreated = true, children = arrayOf(compose(201)))
    val outer =
      compose(
        100,
        isSystemCreated = true,
        children = arrayOf(inner, compose(102, isSystemCreated = true, children = arrayOf(compose(103)))),
      )
    val root = view(1, outer)

    val stripped = stripSystemComposables(dump(root))

    assertThat(stripped.windows.single().root.children.map { it.id }).containsExactly(201L, 103L).inOrder()
  }

  @Test
  fun testSystemLeafDisappears() {
    val root = view(1, compose(10), compose(20, isSystemCreated = true), compose(30))

    val stripped = stripSystemComposables(dump(root))

    assertThat(stripped.windows.single().root.children.map { it.id }).containsExactly(10L, 30L).inOrder()
  }

  @Test
  fun testViewsUnderSplicedSystemNodeAreRetained() {
    // A grafted hosted View is a child of its compose owner; splicing the owner must hoist it with everything else.
    val hostedView = view(99)
    val root = view(1, compose(10, isSystemCreated = true, children = arrayOf(compose(11), hostedView)))

    val stripped = stripSystemComposables(dump(root))

    assertThat(stripped.windows.single().root.children.map { it.id }).containsExactly(11L, 99L).inOrder()
  }

  @Test
  fun testStrippingDoesNotModifyTheInput() {
    val system = compose(20, isSystemCreated = true, children = arrayOf(compose(21)))
    val root = view(1, compose(10), system)
    val input = dump(root)
    val childrenBefore = root.children.toList()

    stripSystemComposables(input)

    // Same instances, same lists: the capture is untouched.
    assertThat(input.windows.single().root).isSameAs(root)
    assertThat(root.children).isEqualTo(childrenBefore)
    assertThat((root.children[1] as UiNode.ComposeNode).children.map { it.id }).containsExactly(21L)
  }

  @Test
  fun testStrippedCopiesPreserveEveryField() {
    val child =
      UiNode.ComposeNode(
        id = 10,
        className = "Text",
        bounds = UiNode.Bounds(1, 2, 3, 4),
        children = mutableListOf(),
        sourceLocation = UiNode.SourceLocation("Main.kt", 7),
        parameters = emptyList(),
        mergedSemantics = emptyList(),
        unmergedSemantics = emptyList(),
        isSystemCreated = false,
      )
    val root =
      UiNode.ViewNode(
        id = 1,
        className = "DecorView",
        bounds = UiNode.Bounds(0, 0, 100, 200),
        children = mutableListOf(child),
        idResource = "root",
        layoutResource = "main",
        attributes = emptyList(),
      )

    val configuration = DeviceConfiguration(density = Dimension.Dpi(420), fontScale = 1.2f)
    val displays = listOf(DisplayInfo(id = 0, widthPx = 1080, heightPx = 1920, orientation = 0))
    val input =
      UiDump(windows = listOf(UiWindow(root = root, configuration = configuration, theme = "@style/Theme.Main")), displays = displays)

    val stripped = stripSystemComposables(input)

    val strippedRoot = stripped.windows.single().root
    assertThat(strippedRoot).isEqualTo(root)
    assertThat(strippedRoot.children.single()).isEqualTo(child)
    assertThat(stripped.windows.single().configuration).isSameAs(configuration)
    assertThat(stripped.windows.single().theme).isEqualTo("@style/Theme.Main")
    assertThat(stripped.displays).isSameAs(displays)
  }

  private fun dump(vararg roots: UiNode.ViewNode): UiDump =
    UiDump(windows = roots.map { UiWindow(root = it, configuration = null, theme = null) }, displays = emptyList())

  private fun view(id: Long, vararg children: UiNode): UiNode.ViewNode =
    UiNode.ViewNode(
      id = id,
      className = "View",
      bounds = UiNode.Bounds(0, 0, 10, 10),
      children = children.toMutableList(),
      idResource = null,
      layoutResource = null,
      attributes = emptyList(),
    )

  private fun compose(id: Long, isSystemCreated: Boolean = false, children: Array<UiNode> = emptyArray()): UiNode.ComposeNode =
    UiNode.ComposeNode(
      id = id,
      className = "Composable",
      bounds = UiNode.Bounds(0, 0, 10, 10),
      children = children.toMutableList(),
      sourceLocation = null,
      parameters = emptyList(),
      mergedSemantics = emptyList(),
      unmergedSemantics = emptyList(),
      isSystemCreated = isSystemCreated,
    )
}
