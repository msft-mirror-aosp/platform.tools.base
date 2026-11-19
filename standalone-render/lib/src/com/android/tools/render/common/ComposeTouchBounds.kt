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

package com.android.tools.render.common

import com.android.tools.render.common.ComponentTreeExtractor.findOneArgMethod
import com.android.tools.render.common.ComponentTreeExtractor.findZeroArgMethod
import com.android.tools.render.common.ComponentTreeExtractor.invokeGetter
import java.util.IdentityHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.roundToInt

/**
 * Touch areas that Compose computed during one render, in window coordinates. Compose makes small clickable elements easier to tap, for
 * example a 24dp icon button that responds over 48dp. Create it with [extract].
 *
 * @property byLayoutNode The touch area of each tappable `LayoutNode`, keyed by identity and clipped to the Compose root.
 * @property rootBounds The Compose root's bounds in window coordinates, or null if nothing was extracted.
 */
internal class ComposeTouchBounds(
  private val byLayoutNode: Map<Any, ComponentBounds> = emptyMap(),
  private val rootBounds: ComponentBounds? = null,
) {
  /**
   * Returns the touch area for [layoutNode] if it contains [bounds] (clipped to [rootBounds]) within 1px on every edge and exceeds its
   * width or height by more than 1px, or null otherwise.
   */
  fun findExpandedBounds(layoutNode: Any, bounds: ComponentBounds): ComponentBounds? {
    val touch = byLayoutNode[layoutNode] ?: return null
    val visible = rootBounds?.let { bounds.intersect(it) } ?: return null
    return touch.takeIf { it.expands(visible) }
  }

  companion object {
    private val logger = Logger.getLogger(ComposeTouchBounds::class.java.name)

    /**
     * Safety limit on views visited while looking for the Compose view. `AndroidComposeView` is normally about the third view visited
     * (`ComposeViewAdapter` -> `ComposeView` -> `AndroidComposeView`), so this only stops a runaway search on an unexpected view tree.
     */
    private const val MAX_VIEWS_TO_SEARCH = 1024

    /** Reads the touch areas from the Compose semantics tree under [adapter], or returns an empty result if it has none. */
    fun extract(adapter: Any): ComposeTouchBounds {
      try {
        val composeView = findComposeView(adapter) ?: return ComposeTouchBounds()
        val owner = invokeGetter(composeView, "getSemanticsOwner") ?: return ComposeTouchBounds()
        val root =
          invokeGetter(owner, "getUnmergedRootSemanticsNode") ?: invokeGetter(owner, "getRootSemanticsNode") ?: return ComposeTouchBounds()
        val rootBounds = toBounds(invokeGetter(root, "getBoundsInRoot")) ?: return ComposeTouchBounds()
        // Semantics bounds are relative to the Compose view, while component tree bounds are relative to the window.
        val (dx, dy) = windowLocation(composeView)
        return ComposeTouchBounds(collectTouchBounds(root, rootBounds, dx, dy), rootBounds.translate(dx, dy))
      } catch (e: Exception) {
        logger.log(Level.FINE, "Failed to extract Compose touch bounds", e)
      } catch (e: LinkageError) {
        logger.log(Level.FINE, "Linkage error extracting Compose touch bounds", e)
      }
      return ComposeTouchBounds()
    }

    /** Finds the Compose view (`AndroidComposeView`) under [root]: the first view that has `getSemanticsOwner()`. */
    private fun findComposeView(root: Any): Any? {
      val queue = ArrayDeque<Any>(listOf(root))
      var searched = 0
      while (queue.isNotEmpty() && searched++ < MAX_VIEWS_TO_SEARCH) {
        val view = queue.removeFirst()
        if (findZeroArgMethod(view.javaClass, "getSemanticsOwner") != null) return view
        val childCount = invokeGetter(view, "getChildCount") as? Int ?: continue
        val getChildAt = findOneArgMethod(view.javaClass, "getChildAt", Int::class.javaPrimitiveType!!) ?: continue
        for (i in 0 until childCount) {
          getChildAt.invoke(view, i)?.let { queue.add(it) }
        }
      }
      return null
    }

    /** Returns the position of [view] in its window from `View.getLocationInWindow()`, or (0, 0) if it has no such method. */
    private fun windowLocation(view: Any): Pair<Int, Int> {
      val method = findOneArgMethod(view.javaClass, "getLocationInWindow", IntArray::class.java) ?: return 0 to 0
      val location = IntArray(2)
      method.invoke(view, location)
      return location[0] to location[1]
    }

    /**
     * Walks the semantics tree from [root] and returns, clipped to [rootBounds] and moved by ([dx], [dy]), the touch area of each node with
     * a tap action. Only tappable nodes are kept: newer Compose (such as 1.13) also grows the touch area of non-clickable nodes that have
     * only decorative semantics, and a touch area only matters for nodes that can be tapped.
     */
    private fun collectTouchBounds(root: Any, rootBounds: ComponentBounds, dx: Int, dy: Int): Map<Any, ComponentBounds> {
      val result = IdentityHashMap<Any, ComponentBounds>()
      val queue = ArrayDeque<Any>(listOf(root))
      while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        (invokeGetter(node, "getChildren") as? List<*>)?.filterNotNullTo(queue)
        val layoutNode = invokeGetter(node, "getLayoutInfo") ?: continue
        if (!ComposeSemanticsExtractor.hasTapAction(layoutNode)) continue
        // Clip to the Compose root to match Android touch dispatch and Compose's accessibility traversal.
        val touchBounds = toBounds(invokeGetter(node, "getTouchBoundsInRoot"))?.intersect(rootBounds) ?: continue
        result.putIfAbsent(layoutNode, touchBounds.translate(dx, dy))
      }
      return result
    }

    /** Converts a Compose `Rect` to rounded [ComponentBounds], or null if it is missing or not finite. */
    private fun toBounds(rect: Any?): ComponentBounds? {
      if (rect == null) return null
      val left = rectCoord(rect, "left") ?: return null
      val top = rectCoord(rect, "top") ?: return null
      val right = rectCoord(rect, "right") ?: return null
      val bottom = rectCoord(rect, "bottom") ?: return null
      return ComponentBounds(left, top, right, bottom)
    }

    /** Reads a finite float coordinate from a Compose `Rect` and rounds it to an integer pixel, or returns null. */
    private fun rectCoord(rect: Any, name: String): Int? {
      val value = (invokeGetter(rect, name) as? Number)?.toFloat() ?: return null
      return if (value.isFinite()) value.roundToInt() else null
    }
  }
}

/** Returns the non-empty intersection of this rectangle and [other], or null if they do not overlap. */
private fun ComponentBounds.intersect(other: ComponentBounds): ComponentBounds? {
  val l = maxOf(left, other.left)
  val t = maxOf(top, other.top)
  val r = minOf(right, other.right)
  val b = minOf(bottom, other.bottom)
  return if (r > l && b > t) ComponentBounds(l, t, r, b) else null
}

/**
 * Returns true if this area contains [other] within 1px on every edge and is wider or taller by more than 1px. The 1px tolerance avoids
 * recording odd-pixel rounding drift (for example at 420dpi), and containment avoids recording scroll-clipped areas smaller than [other].
 */
private fun ComponentBounds.expands(other: ComponentBounds): Boolean =
  left <= other.left + 1 &&
    top <= other.top + 1 &&
    right >= other.right - 1 &&
    bottom >= other.bottom - 1 &&
    (width > other.width + 1 || height > other.height + 1)

/** Returns this rectangle moved right by [dx] and down by [dy]. */
private fun ComponentBounds.translate(dx: Int, dy: Int): ComponentBounds = ComponentBounds(left + dx, top + dy, right + dx, bottom + dy)
