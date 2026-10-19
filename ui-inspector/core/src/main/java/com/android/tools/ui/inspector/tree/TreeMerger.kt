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
 * Returns a copy of [root] with the Composable nodes grafted under the View node matching [targetViewId] (the AndroidComposeView that hosts
 * the composition). Android Views that the composition embeds through the `AndroidView` composable sit in the View tree under an
 * AndroidViewsHandler next to the composition; each is moved under the `AndroidView` Composable node that owns it. Returns null when
 * [targetViewId] does not exist in the tree.
 */
internal fun attachComposeTree(
  root: UiNode.ViewNode,
  targetViewId: Long,
  composeNodes: List<LayoutInspectorComposeProtocol.ComposableNode>,
  stringTable: Map<Int, String>,
  viewsToSkip: List<Long>,
  parameters: LayoutInspectorComposeProtocol.GetAllParametersResponse?,
  includeParameters: Boolean,
  includeSemantics: Boolean,
): UiNode.ViewNode? {
  // Nested interop (a ComposeView inside a hosted AndroidView) places the target below already-grafted Compose nodes, so the search must
  // traverse the mixed tree, not only View children.
  if (findViewNode(root, targetViewId) == null) return null

  val hostedViewIds = collectHostedViewIds(composeNodes)
  val hostedViews = mutableMapOf<Long, UiNode.ViewNode>()
  val grafted = root.rebuilding { node ->
    if (node !is UiNode.ViewNode || node.id != targetViewId) return@rebuilding null
    val children =
      node.children
        .filterNot { child -> child is UiNode.ViewNode && viewsToSkip.contains(child.id) }
        .let { remaining -> detachHostedViews(node, remaining, hostedViewIds, hostedViews) }
        .mapNotNull { child -> child.withoutHostedViews(hostedViewIds, hostedViews) }
    val composeChildren = composeNodes.map { composeNode ->
      convertComposeNode(
        composeNode,
        stringTable,
        hostedViews,
        root.bounds.x,
        root.bounds.y,
        parameters,
        includeParameters,
        includeSemantics,
      )
    }
    node.copy(children = children + composeChildren)
  }
  return grafted as UiNode.ViewNode
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

/** Rebuilds this tree recursively. When [replace] returns a node, that node is used as is, without looking at its children. */
private fun UiNode.rebuilding(replace: (UiNode) -> UiNode?): UiNode =
  replace(this) ?: withChildren(children.map { child -> child.rebuilding(replace) })

private fun UiNode.withChildren(children: List<UiNode>): UiNode =
  when (this) {
    is UiNode.ViewNode -> copy(children = children)
    is UiNode.ComposeNode -> copy(children = children)
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
 * Returns [children] without the hosted views, when [owner] is an AndroidViewsHandler; the removed views land in [hostedViews], keyed by
 * id, for the Composable nodes that own them. Any other owner keeps its children as they are.
 *
 * Compose keeps hosted Views inside AndroidViewsHandler carriers anywhere below the target: each direct child of a handler is the root of
 * one hosted subtree (a ViewFactoryHolder on current Compose, the payload View itself on older versions) and is what
 * `ComposableNode.view_id` references. Only a handler's direct children are matched, and a matched subtree is moved wholesale.
 */
private fun detachHostedViews(
  owner: UiNode,
  children: List<UiNode>,
  hostedViewIds: Set<Long>,
  hostedViews: MutableMap<Long, UiNode.ViewNode>,
): List<UiNode> {
  if (!owner.isAndroidViewsHandler()) return children
  val (hosted, kept) = children.partition { child -> child is UiNode.ViewNode && hostedViewIds.contains(child.id) }
  hosted.forEach { child -> hostedViews[child.id] = child as UiNode.ViewNode }
  return kept
}

/**
 * Returns a copy of this subtree with the hosted views detached (see [detachHostedViews]) at every level; null when this node is an
 * AndroidViewsHandler left without children, which is dropped. Detached subtrees are not descended into: carriers inside them (from nested
 * compositions) are left for the attach pass of their own compose root.
 */
private fun UiNode.withoutHostedViews(hostedViewIds: Set<Long>, hostedViews: MutableMap<Long, UiNode.ViewNode>): UiNode? {
  val kept =
    detachHostedViews(this, children, hostedViewIds, hostedViews).mapNotNull { child ->
      child.withoutHostedViews(hostedViewIds, hostedViews)
    }
  return if (isAndroidViewsHandler() && kept.isEmpty()) null else withChildren(kept)
}
