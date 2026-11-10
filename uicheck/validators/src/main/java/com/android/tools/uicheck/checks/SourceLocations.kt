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

import com.android.tools.uicheck.ComponentTreeNode
import com.android.tools.uicheck.snapshot.UiCheckSourceLocation
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The user's source location for each node of a component tree.
 *
 * A node uses its own call site when that is in user code (see [UserCodeFilter]). Otherwise it inherits its nearest ancestor's, so a
 * finding on a library's inner node (for example the `Surface` inside a Material `Button`) points at the user's `Button(...)` call. Library
 * ancestors above any user code (such as a root `MaterialTheme` wrapper) resolve to `null`, while sibling/leaf nodes without user ancestors
 * or descendants fall back to the first user call site in the tree (preserving that call site's [UiCheckSourceLocation.functionName] so
 * source highlighters match the token on [UiCheckSourceLocation.lineNumber], while `viewClassName` on the issue still names the rendered
 * component itself).
 */
class SourceLocations
private constructor(
  private val byNode: Map<ComponentTreeNode, UiCheckSourceLocation?>,
  private val ancestorsAboveUserCode: Set<ComponentTreeNode>,
  private val fallback: UiCheckSourceLocation?,
) {
  /**
   * Returns the resolved user call-site location for [node], `null` for library ancestors wrapping user code, or [fallback] (the first user
   * call site in the tree, including its call-site `functionName`) for unattributed leaves.
   */
  operator fun get(node: ComponentTreeNode): UiCheckSourceLocation? = byNode[node] ?: if (node in ancestorsAboveUserCode) null else fallback

  companion object {
    @JvmField val EMPTY = SourceLocations(emptyMap(), emptySet(), null)

    /** Resolves source call-site locations for every node in [roots] using [userCode] to identify user Composable calls. */
    @JvmStatic
    fun resolve(roots: List<ComponentTreeNode>, userCode: UserCodeFilter): SourceLocations {
      // Identity collections: distinct nodes can be equal data classes (same name and bounds).
      val byNode = IdentityHashMap<ComponentTreeNode, UiCheckSourceLocation?>()
      val ancestorsAboveUserCode = Collections.newSetFromMap(IdentityHashMap<ComponentTreeNode, Boolean>())
      var fallback: UiCheckSourceLocation? = null
      roots.forEach { root ->
        val result =
          collectNodeLocations(
            node = root,
            inherited = null,
            userCode = userCode,
            byNode = byNode,
            ancestorsAboveUserCode = ancestorsAboveUserCode,
            currentFallback = fallback,
          )
        fallback = result.fallback
      }
      return SourceLocations(byNode, ancestorsAboveUserCode, fallback)
    }

    /** Result of traversing a subtree: the first observed user call site and whether the subtree contained any user code. */
    private data class TraversalResult(
      val fallback: UiCheckSourceLocation?,
      val hasUserCodeInSubtree: Boolean,
    )

    /** Recursively records source locations for [node] and its subtree, tracking library ancestors that wrap user code. */
    private fun collectNodeLocations(
      node: ComponentTreeNode,
      inherited: UiCheckSourceLocation?,
      userCode: UserCodeFilter,
      byNode: IdentityHashMap<ComponentTreeNode, UiCheckSourceLocation?>,
      ancestorsAboveUserCode: MutableSet<ComponentTreeNode>,
      currentFallback: UiCheckSourceLocation?,
    ): TraversalResult {
      val own =
        node.sourceLocation?.takeIf(userCode::isUserCode)?.let {
          UiCheckSourceLocation(
            filePath = it.fileName,
            lineNumber = it.lineNumber,
            functionName = node.simpleComponentName(),
          )
        }
      var updatedFallback = currentFallback ?: own
      val resolved = own ?: inherited
      byNode[node] = resolved
      var childHasUserCode = false
      node.children?.forEach { child ->
        val childResult =
          collectNodeLocations(
            child,
            resolved,
            userCode,
            byNode,
            ancestorsAboveUserCode,
            updatedFallback,
          )
        updatedFallback = childResult.fallback
        if (childResult.hasUserCodeInSubtree) {
          childHasUserCode = true
        }
      }
      if (resolved == null && childHasUserCode) {
        ancestorsAboveUserCode.add(node)
      }
      return TraversalResult(
        fallback = updatedFallback,
        hasUserCodeInSubtree = own != null || childHasUserCode,
      )
    }
  }
}
