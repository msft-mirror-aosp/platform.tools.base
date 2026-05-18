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

import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol

/** Converts a protobuf [ViewInspectorProtocol.ViewNode] into a domain [UiNode.ViewNode]. */
internal fun convertViewNode(node: ViewInspectorProtocol.ViewNode, stringTable: Map<Int, String>): UiNode.ViewNode {
  val className = stringTable[node.className] ?: "Unknown"
  val bounds = UiNode.Bounds(x = node.bounds.x, y = node.bounds.y, width = node.bounds.width, height = node.bounds.height)
  val idResource = stringTable[node.idResource]
  val layoutResource = stringTable[node.layoutResource]
  val attributes =
    node.attributesList.map { attr ->
      val directSource = stringTable[attr.directSource]
      val styleChain = attr.styleChainList.map { stringTable[it] ?: "unknown" }
      UiNode.Attribute(
        name = stringTable[attr.name] ?: "unknown",
        value = if (attr.value == 0) "" else stringTable[attr.value] ?: "unknown",
        directSource = directSource,
        styleChain = styleChain,
      )
    }
  val children = node.childrenList.map { convertViewNode(it, stringTable) }.toMutableList<UiNode>()
  return UiNode.ViewNode(
    id = node.id,
    className = className,
    bounds = bounds,
    idResource = idResource,
    layoutResource = layoutResource,
    attributes = attributes,
    children = children,
  )
}

/**
 * Converts a protobuf [LayoutInspectorComposeProtocol.ComposableNode] into a domain [UiNode.ComposeNode].
 *
 * @param node the protobuf Composable node to convert.
 * @param stringTable string table containing all the text resources indexed by ID.
 * @param hostedViews a map of View ID to [UiNode.ViewNode] representing all Android views hosted within the entire Compose tree (e.g., via
 *   `AndroidView` composables), used for quick O(1) lookups. If a Composable node has a matching `viewId`, its corresponding [ViewNode] is
 *   extracted from this map and grafted as a child of that Composable node, moving it from its original location under
 *   `AndroidComposeView`.
 */
internal fun convertComposeNode(
  node: LayoutInspectorComposeProtocol.ComposableNode,
  stringTable: Map<Int, String>,
  hostedViews: Map<Long, UiNode.ViewNode>,
): UiNode.ComposeNode {
  val name = stringTable[node.name] ?: "Composable"
  val bounds =
    if (node.hasBounds()) {
      val layout = node.bounds.layout
      UiNode.Bounds(x = layout.x, y = layout.y, width = layout.w, height = layout.h)
    } else {
      UiNode.Bounds(x = 0, y = 0, width = 0, height = 0)
    }
  val children = node.childrenList.map { convertComposeNode(it, stringTable, hostedViews) }.toMutableList<UiNode>()
  if (node.viewId != 0L) {
    hostedViews[node.viewId]?.let { hostedView -> children.add(hostedView) }
  }
  return UiNode.ComposeNode(id = node.id, className = name, bounds = bounds, children = children)
}
