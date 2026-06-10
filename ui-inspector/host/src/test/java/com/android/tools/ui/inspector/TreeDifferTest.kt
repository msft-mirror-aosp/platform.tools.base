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

class TreeDifferTest {

  private val bounds1 = UiNode.Bounds(0, 0, 100, 100)
  private val bounds2 = UiNode.Bounds(0, 0, 150, 100)

  @Test
  fun testNoChanges() {
    val node =
      UiNode.ViewNode(
        id = 1L,
        className = "Button",
        bounds = bounds1,
        idResource = "btn",
        layoutResource = null,
        attributes = listOf(UiNode.Attribute("text", "Click")),
      )
    val prev = listOf(node)
    val curr = listOf(node.copy())

    val diff = diffTrees(prev, curr)
    assertThat(diff.added).isEmpty()
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).isEmpty()
  }

  @Test
  fun testAddedNode() {
    val prev =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container",
          layoutResource = null,
          attributes = emptyList(),
        )
      )
    val addedChild =
      UiNode.ViewNode(id = 2L, className = "Button", bounds = bounds1, idResource = "btn", layoutResource = null, attributes = emptyList())
    val curr =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container",
          layoutResource = null,
          attributes = emptyList(),
          children = mutableListOf(addedChild),
        )
      )

    val diff = diffTrees(prev, curr)
    assertThat(diff.added).containsExactly(addedChild)
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).isEmpty()
  }

  @Test
  fun testRemovedNode() {
    val child =
      UiNode.ViewNode(id = 2L, className = "Button", bounds = bounds1, idResource = "btn", layoutResource = null, attributes = emptyList())
    val prev =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container",
          layoutResource = null,
          attributes = emptyList(),
          children = mutableListOf(child),
        )
      )
    val curr =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container",
          layoutResource = null,
          attributes = emptyList(),
        )
      )

    val diff = diffTrees(prev, curr)
    assertThat(diff.added).isEmpty()
    assertThat(diff.removed).containsExactly(child)
    assertThat(diff.modified).isEmpty()
  }

  @Test
  fun testModifiedBounds() {
    val prev =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "Button",
          bounds = bounds1,
          idResource = "btn",
          layoutResource = null,
          attributes = emptyList(),
        )
      )
    val curr =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "Button",
          bounds = bounds2,
          idResource = "btn",
          layoutResource = null,
          attributes = emptyList(),
        )
      )

    val diff = diffTrees(prev, curr)
    assertThat(diff.added).isEmpty()
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).hasSize(1)

    val mod = diff.modified.first()
    assertThat(mod.node.id).isEqualTo(1L)
    assertThat(mod.changes).containsExactly(NodeChange.BoundsChange(bounds1, bounds2))
  }

  @Test
  fun testModifiedParent() {
    val child =
      UiNode.ViewNode(
        id = 3L,
        className = "TextView",
        bounds = bounds1,
        idResource = "txt",
        layoutResource = null,
        attributes = emptyList(),
      )
    // Child is inside container 1
    val prev =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container1",
          layoutResource = null,
          attributes = emptyList(),
          children = mutableListOf(child),
        ),
        UiNode.ViewNode(
          id = 2L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container2",
          layoutResource = null,
          attributes = emptyList(),
        ),
      )
    // Child moved to container 2
    val curr =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container1",
          layoutResource = null,
          attributes = emptyList(),
        ),
        UiNode.ViewNode(
          id = 2L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container2",
          layoutResource = null,
          attributes = emptyList(),
          children = mutableListOf(child),
        ),
      )

    val diff = diffTrees(prev, curr)
    assertThat(diff.added).isEmpty()
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).hasSize(1)

    val mod = diff.modified.first()
    assertThat(mod.node.id).isEqualTo(3L)
    assertThat(mod.changes).containsExactly(NodeChange.ParentChange(1L, 2L))
  }

  @Test
  fun testModifiedAttributes() {
    val prev =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "Button",
          bounds = bounds1,
          idResource = "btn",
          layoutResource = null,
          attributes = listOf(UiNode.Attribute("text", "Click"), UiNode.Attribute("enabled", "true")),
        )
      )
    val curr =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "Button",
          bounds = bounds1,
          idResource = "btn",
          layoutResource = null,
          attributes = listOf(UiNode.Attribute("text", "Clicked"), UiNode.Attribute("visible", "true")),
        )
      )

    val diff = diffTrees(prev, curr)
    assertThat(diff.added).isEmpty()
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).hasSize(1)

    val mod = diff.modified.first()
    assertThat(mod.node.id).isEqualTo(1L)
    assertThat(mod.changes)
      .containsExactly(
        NodeChange.PropertyChange.Modified("text", "Click", "Clicked"),
        NodeChange.PropertyChange.Removed("enabled", "true"),
        NodeChange.PropertyChange.Added("visible", "true"),
      )
  }

  @Test
  fun testModifiedComposeParameters() {
    val prev =
      listOf(
        UiNode.ComposeNode(
          id = 10L,
          className = "Text",
          bounds = bounds1,
          parameters =
            listOf(
              UiNode.ComposeParameter.Single("text", UiNode.ComposeParameter.Value.StringVal("Hello")),
              UiNode.ComposeParameter.Single("color", UiNode.ComposeParameter.Value.ColorVal(0xFF0000)),
            ),
          mergedSemantics = emptyList(),
          unmergedSemantics = emptyList(),
        )
      )
    val curr =
      listOf(
        UiNode.ComposeNode(
          id = 10L,
          className = "Text",
          bounds = bounds1,
          parameters =
            listOf(
              UiNode.ComposeParameter.Single("text", UiNode.ComposeParameter.Value.StringVal("World")),
              UiNode.ComposeParameter.Single("alpha", UiNode.ComposeParameter.Value.NumberVal(0.5f)),
            ),
          mergedSemantics = emptyList(),
          unmergedSemantics = emptyList(),
        )
      )

    val diff = diffTrees(prev, curr)
    assertThat(diff.added).isEmpty()
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).hasSize(1)

    val mod = diff.modified.first()
    assertThat(mod.node.id).isEqualTo(10L)
    assertThat(mod.changes)
      .containsExactly(
        NodeChange.PropertyChange.Modified(
          "text",
          UiNode.ComposeParameter.Single("text", UiNode.ComposeParameter.Value.StringVal("Hello")),
          UiNode.ComposeParameter.Single("text", UiNode.ComposeParameter.Value.StringVal("World")),
        ),
        NodeChange.PropertyChange.Removed(
          "color",
          UiNode.ComposeParameter.Single("color", UiNode.ComposeParameter.Value.ColorVal(0xFF0000)),
        ),
        NodeChange.PropertyChange.Added("alpha", UiNode.ComposeParameter.Single("alpha", UiNode.ComposeParameter.Value.NumberVal(0.5f))),
      )
  }

  @Test
  fun testModifiedClassName() {
    val prev =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "TextView",
          bounds = bounds1,
          idResource = "txt",
          layoutResource = null,
          attributes = emptyList(),
        )
      )
    val curr =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "Button",
          bounds = bounds1,
          idResource = "txt",
          layoutResource = null,
          attributes = emptyList(),
        )
      )

    val diff = diffTrees(prev, curr)
    assertThat(diff.added).isEmpty()
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).hasSize(1)

    val mod = diff.modified.first()
    assertThat(mod.node.id).isEqualTo(1L)
    assertThat(mod.changes).containsExactly(NodeChange.ClassChange("TextView", "Button"))
  }

  @Test
  fun testAddedSubtree() {
    val prev =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container",
          layoutResource = null,
          attributes = emptyList(),
        )
      )

    // A whole nested hierarchy is added: Button (id=2) -> TextView (id=3)
    val childText =
      UiNode.ViewNode(
        id = 3L,
        className = "TextView",
        bounds = bounds1,
        idResource = "txt",
        layoutResource = null,
        attributes = emptyList(),
      )
    val childButton =
      UiNode.ViewNode(
        id = 2L,
        className = "Button",
        bounds = bounds1,
        idResource = "btn",
        layoutResource = null,
        attributes = emptyList(),
        children = mutableListOf(childText),
      )

    val curr =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container",
          layoutResource = null,
          attributes = emptyList(),
          children = mutableListOf(childButton),
        )
      )

    val diff = diffTrees(prev, curr)
    // The differ flat-indexes everything, so both childButton and childText are marked as added
    assertThat(diff.added).containsExactly(childButton, childText)
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).isEmpty()
  }

  @Test
  fun testEmptyTrees() {
    val prev = emptyList<UiNode>()
    val curr = emptyList<UiNode>()
    val diff = diffTrees(prev, curr)
    assertThat(diff.added).isEmpty()
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).isEmpty()
  }

  @Test
  fun testEmptyToNonEmpty() {
    val prev = emptyList<UiNode>()
    val node =
      UiNode.ViewNode(id = 1L, className = "Button", bounds = bounds1, idResource = "btn", layoutResource = null, attributes = emptyList())
    val curr = listOf(node)
    val diff = diffTrees(prev, curr)
    assertThat(diff.added).containsExactly(node)
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).isEmpty()
  }

  @Test
  fun testNonEmptyToEmpty() {
    val node =
      UiNode.ViewNode(id = 1L, className = "Button", bounds = bounds1, idResource = "btn", layoutResource = null, attributes = emptyList())
    val prev = listOf(node)
    val curr = emptyList<UiNode>()
    val diff = diffTrees(prev, curr)
    assertThat(diff.added).isEmpty()
    assertThat(diff.removed).containsExactly(node)
    assertThat(diff.modified).isEmpty()
  }

  @Test
  fun testSiblingReordering() {
    val childA =
      UiNode.ViewNode(id = 2L, className = "Button", bounds = bounds1, idResource = "btnA", layoutResource = null, attributes = emptyList())
    val childB =
      UiNode.ViewNode(id = 3L, className = "Button", bounds = bounds1, idResource = "btnB", layoutResource = null, attributes = emptyList())

    val prev =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container",
          layoutResource = null,
          attributes = emptyList(),
          children = mutableListOf(childA, childB),
        )
      )

    val curr =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "LinearLayout",
          bounds = bounds1,
          idResource = "container",
          layoutResource = null,
          attributes = emptyList(),
          children = mutableListOf(childB, childA),
        )
      )

    val diff = diffTrees(prev, curr)
    // Sibling reordering doesn't change classes, bounds, parent IDs, or attributes.
    // Hence, it should not report any added, removed, or modified nodes.
    assertThat(diff.added).isEmpty()
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).isEmpty()
  }

  @Test
  fun testTypeTransitionSameId() {
    val prev =
      listOf(
        UiNode.ViewNode(
          id = 1L,
          className = "Button",
          bounds = bounds1,
          idResource = "btn",
          layoutResource = null,
          attributes = listOf(UiNode.Attribute("text", "Click")),
        )
      )

    val curr =
      listOf(
        UiNode.ComposeNode(
          id = 1L,
          className = "ButtonCompose",
          bounds = bounds1,
          parameters = listOf(UiNode.ComposeParameter.Single("text", UiNode.ComposeParameter.Value.StringVal("Click"))),
          mergedSemantics = emptyList(),
          unmergedSemantics = emptyList(),
        )
      )

    val diff = diffTrees(prev, curr)
    assertThat(diff.added).isEmpty()
    assertThat(diff.removed).isEmpty()
    assertThat(diff.modified).hasSize(1)

    val mod = diff.modified.first()
    assertThat(mod.node.id).isEqualTo(1L)
    // Should capture the className change, but does not capture property additions/removals
    // because view-to-compose comparison block is skipped.
    assertThat(mod.changes).containsExactly(NodeChange.ClassChange("Button", "ButtonCompose"))
  }
}
