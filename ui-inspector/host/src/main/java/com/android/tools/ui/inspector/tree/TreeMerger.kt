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
import com.android.tools.ui.inspector.proto.convertComposeNode
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol

/**
 * Finds the View node matching [targetViewId] and grafts the Composable nodes under it, moving any Android View subtrees hosted by the
 * composition under their owning Composable nodes.
 *
 * Returns false when [targetViewId] does not exist in the tree.
 */
internal fun attachComposeTree(
  viewNode: UiNode.ViewNode,
  targetViewId: Long,
  composeNodes: List<LayoutInspectorComposeProtocol.ComposableNode>,
  stringTable: Map<Int, String>,
  viewsToSkip: List<Long>,
  parameters: LayoutInspectorComposeProtocol.GetAllParametersResponse?,
  includeParameters: Boolean,
  includeSemantics: Boolean,
): Boolean {
  // Nested interop (a ComposeView inside a hosted AndroidView) places the target below already-grafted Compose nodes, so the search must
  // traverse the mixed tree, not only View children.
  val target = findViewNode(viewNode, targetViewId) ?: return false

  target.children.removeAll { child -> child is UiNode.ViewNode && viewsToSkip.contains(child.id) }

  val hostedViews = extractHostedViewSubtrees(target, collectHostedViewIds(composeNodes))

  composeNodes.forEach { composeNode ->
    val parsedComposeNode =
      convertComposeNode(
        composeNode,
        stringTable,
        hostedViews,
        viewNode.bounds.x,
        viewNode.bounds.y,
        parameters,
        includeParameters,
        includeSemantics,
      )
    target.children.add(parsedComposeNode)
  }
  return true
}

/** Finds the View node with [viewId], traversing View and already-grafted Compose children alike. */
private fun findViewNode(node: UiNode, viewId: Long): UiNode.ViewNode? {
  if (node is UiNode.ViewNode && node.id == viewId) {
    return node
  }
  for (child in node.children) {
    val found = findViewNode(child, viewId)
    if (found != null) {
      return found
    }
  }
  return null
}

/**
 * Recursively collects the IDs of all Android views hosted within the given Composable nodes.
 *
 * A hosted view ID represents an Android View embedded in a Composable hierarchy (e.g., via an `AndroidView` composable).
 */
private fun collectHostedViewIds(nodes: List<LayoutInspectorComposeProtocol.ComposableNode>): Set<Long> {
  val ids = mutableSetOf<Long>()
  nodes.forEach { collectHostedViewIds(it, ids) }
  return ids
}

/** Traverses the Composable node hierarchy to collect hosted view IDs. */
private fun collectHostedViewIds(node: LayoutInspectorComposeProtocol.ComposableNode, accumulator: MutableSet<Long>) {
  if (node.viewId != 0L) {
    accumulator.add(node.viewId)
  }
  node.childrenList.forEach { collectHostedViewIds(it, accumulator) }
}

/** The carrier View that Compose inserts between an AndroidComposeView and the Android Views hosted by the composition. */
private const val ANDROID_VIEWS_HANDLER = "AndroidViewsHandler"

private fun UiNode.isAndroidViewsHandler(): Boolean =
  this is UiNode.ViewNode && (className == ANDROID_VIEWS_HANDLER || className.endsWith(".$ANDROID_VIEWS_HANDLER"))

/**
 * Detaches the hosted Android View subtrees referenced by [hostedViewIds] so they can be grafted under their Composable owners.
 *
 * Compose keeps hosted Views inside AndroidViewsHandler carriers anywhere below the target: each direct child of a handler is the root of
 * one hosted subtree (a ViewFactoryHolder on current Compose, the payload View itself on older versions) and is what
 * `ComposableNode.view_id` references. Matching Studio's merger, only a handler's direct children are matched, a matched subtree is moved
 * wholesale, and a handler left without children is dropped.
 */
private fun extractHostedViewSubtrees(target: UiNode.ViewNode, hostedViewIds: Set<Long>): Map<Long, UiNode.ViewNode> {
  val hostedViews = mutableMapOf<Long, UiNode.ViewNode>()
  extractHostedViewSubtrees(target, hostedViewIds, hostedViews)
  return hostedViews
}

private fun extractHostedViewSubtrees(node: UiNode, hostedViewIds: Set<Long>, accumulator: MutableMap<Long, UiNode.ViewNode>) {
  if (node.isAndroidViewsHandler()) {
    node.children.removeAll { child ->
      if (child is UiNode.ViewNode && hostedViewIds.contains(child.id)) {
        accumulator[child.id] = child
        true
      } else {
        false
      }
    }
  }
  // Extracted subtrees are no longer in the tree at this point: carriers inside them (from nested compositions) are left for the attach
  // pass of their own compose root.
  node.children.forEach { child -> extractHostedViewSubtrees(child, hostedViewIds, accumulator) }
  node.children.removeAll { child -> child.isAndroidViewsHandler() && child.children.isEmpty() }
}
