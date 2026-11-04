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

package com.android.tools.uicheck.checks

import com.android.tools.uicheck.ComponentBounds
import com.android.tools.uicheck.ComponentTreeNode
import kotlin.math.max
import kotlin.math.min

private val SCROLLABLE_CONTAINER_NAMES =
  setOf(
    "ScrollView",
    "HorizontalScrollView",
    "NestedScrollView",
    "RecyclerView",
    "ListView",
    "GridView",
    "LazyColumn",
    "LazyRow",
    "LazyVerticalGrid",
    "LazyHorizontalGrid",
    "LazyVerticalStaggeredGrid",
    "LazyHorizontalStaggeredGrid",
    "HorizontalPager",
    "VerticalPager",
  )

/** Returns true if this [ComponentBounds] and [other] both have positive dimensions and intersect with non-zero overlapping area. */
private fun ComponentBounds.intersects(other: ComponentBounds): Boolean =
  width > 0 &&
    height > 0 &&
    other.width > 0 &&
    other.height > 0 &&
    left < other.right &&
    right > other.left &&
    top < other.bottom &&
    bottom > other.top

/** Computes the rectangular intersection between this [ComponentBounds] and [other]. */
private fun ComponentBounds.intersection(other: ComponentBounds): ComponentBounds {
  val l = max(left, other.left)
  val t = max(top, other.top)
  val r = max(l, min(right, other.right))
  val b = max(t, min(bottom, other.bottom))
  return ComponentBounds(l, t, r, b)
}

/**
 * Returns every visible node in pre-order whose bounds intersect the screen and any enclosing scrollable container's viewport. The subtree
 * of an invisible or scrolled-off node is skipped.
 */
internal fun CheckContext.visibleNodes(): Sequence<ComponentTreeNode> = sequence {
  val screenClip = ComponentBounds(0, 0, screenWidthPx.coerceAtLeast(0), screenHeightPx.coerceAtLeast(0))
  roots.forEach { yieldVisibleNode(it, screenClip) }
}

/** Recursively yields [node] and its visible descendants that intersect [clip]. */
private suspend fun SequenceScope<ComponentTreeNode>.yieldVisibleNode(
  node: ComponentTreeNode,
  clip: ComponentBounds,
) {
  if (!node.isVisible() || !node.bounds.intersects(clip)) return
  yield(node)
  val childClip = if (node.isScrollableContainer()) clip.intersection(node.bounds) else clip
  node.children?.forEach { yieldVisibleNode(it, childClip) }
}

/** Returns true if this node is non-zero in size and not hidden by visibility or alpha modifiers. */
internal fun ComponentTreeNode.isVisible(): Boolean {
  if (bounds.width <= 0 || bounds.height <= 0) return false
  val vis = semantics?.get("visibility") ?: parameters?.get("visibility")
  if (vis == "INVISIBLE" || vis == "GONE") return false
  val alpha = (modifiers?.get("alpha") ?: semantics?.get("alpha"))?.toFloatOrNull()
  if (alpha != null && alpha <= 0f) return false
  return true
}

/** Returns true if this node exposes an accessibility action matching [actionName]. */
private fun ComponentTreeNode.hasAction(actionName: String): Boolean =
  semantics?.get("actions")?.split(',')?.any {
    it.trim().equals(actionName, ignoreCase = true)
  } == true

/** Returns true if this node represents a vertically or horizontally scrollable viewport. */
private fun ComponentTreeNode.isScrollableContainer(): Boolean =
  modifiers?.get("scroll") == "true" ||
    hasAction("ScrollBy") ||
    hasAction("ScrollToIndex") ||
    simpleComponentName() in SCROLLABLE_CONTAINER_NAMES
