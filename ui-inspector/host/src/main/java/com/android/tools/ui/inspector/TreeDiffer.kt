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

/** Holds the set of added, removed, and modified nodes representing the difference between two trees. */
internal class TreeDiff(val added: List<UiNode>, val removed: List<UiNode>, val modified: List<NodeModification>)

/** Represents the set of property or layout changes detected on a single UI node. */
internal class NodeModification(val node: UiNode, val changes: List<NodeChange>)

/** Represents a specific layout or property change on a UI node. */
internal sealed class NodeChange {
  data class ClassChange(val oldClassName: String, val newClassName: String) : NodeChange()

  data class BoundsChange(val oldBounds: UiNode.Bounds, val newBounds: UiNode.Bounds) : NodeChange()

  data class ParentChange(val oldParentId: Long?, val newParentId: Long?) : NodeChange()

  sealed class PropertyChange : NodeChange() {
    abstract val name: String

    data class Removed(override val name: String, val oldValue: Any) : PropertyChange()

    data class Added(override val name: String, val newValue: Any) : PropertyChange()

    data class Modified(override val name: String, val oldValue: Any, val newValue: Any) : PropertyChange()
  }
}

/**
 * Computes a diff between a previous and a current UI hierarchy tree. Matches nodes by their ID to identify added, removed, or modified
 * nodes.
 */
internal fun diffTrees(prev: List<UiNode>, curr: List<UiNode>): TreeDiff {
  val oldNodes = indexNodes(prev)
  val newNodes = indexNodes(curr)

  val added = newNodes.filterKeys { !oldNodes.containsKey(it) }.values.toList()
  val removed = oldNodes.filterKeys { !newNodes.containsKey(it) }.values.toList()

  val oldParents = getParentMap(prev)
  val newParents = getParentMap(curr)

  val modified = mutableListOf<NodeModification>()

  newNodes.forEach { (id, newNode) ->
    val oldNode = oldNodes[id]
    if (oldNode != null) {
      val changes = compareNodes(oldNode, newNode, oldParents[id], newParents[id])
      if (changes.isNotEmpty()) {
        modified.add(NodeModification(newNode, changes))
      }
    }
  }

  return TreeDiff(added, removed, modified)
}

/** Indexes all nodes in the given hierarchies recursively by their unique IDs. */
private fun indexNodes(nodes: List<UiNode>): Map<Long, UiNode> {
  val map = mutableMapOf<Long, UiNode>()
  fun traverse(node: UiNode) {
    map[node.id] = node
    node.children.forEach { traverse(it) }
  }
  nodes.forEach { traverse(it) }
  return map
}

/** Recursively maps each node's ID to its parent's ID in the given hierarchies. */
private fun getParentMap(nodes: List<UiNode>): Map<Long, Long?> {
  val map = mutableMapOf<Long, Long?>()
  fun traverse(node: UiNode, parentId: Long?) {
    map[node.id] = parentId
    node.children.forEach { traverse(it, node.id) }
  }
  nodes.forEach { traverse(it, null) }
  return map
}

/** Compares two versions of a node to identify differences in class, bounds, parentage, or properties. */
private fun compareNodes(node1: UiNode, node2: UiNode, parentId1: Long?, parentId2: Long?): List<NodeChange> {
  val changes = mutableListOf<NodeChange>()

  if (node1.className != node2.className) {
    changes.add(NodeChange.ClassChange(node1.className, node2.className))
  }

  if (node1.bounds != node2.bounds) {
    changes.add(NodeChange.BoundsChange(node1.bounds, node2.bounds))
  }

  if (parentId1 != parentId2) {
    changes.add(NodeChange.ParentChange(parentId1, parentId2))
  }

  if (node1 is UiNode.ViewNode && node2 is UiNode.ViewNode) {
    val attrs1 = node1.attributes.associate { it.name to it.value }
    val attrs2 = node2.attributes.associate { it.name to it.value }

    attrs1.forEach { (name, val1) ->
      val val2 = attrs2[name]
      if (val2 == null) {
        changes.add(NodeChange.PropertyChange.Removed(name, val1))
      } else if (val1 != val2) {
        changes.add(NodeChange.PropertyChange.Modified(name, val1, val2))
      }
    }

    attrs2.forEach { (name, val2) ->
      if (!attrs1.containsKey(name)) {
        changes.add(NodeChange.PropertyChange.Added(name, val2))
      }
    }
  } else if (node1 is UiNode.ComposeNode && node2 is UiNode.ComposeNode) {
    val params1 = node1.parameters.associateBy { it.name }
    val params2 = node2.parameters.associateBy { it.name }

    params1.forEach { (name, val1) ->
      val val2 = params2[name]
      if (val2 == null) {
        changes.add(NodeChange.PropertyChange.Removed(name, val1))
      } else if (val1 != val2) {
        changes.add(NodeChange.PropertyChange.Modified(name, val1, val2))
      }
    }

    params2.forEach { (name, val2) ->
      if (!params1.containsKey(name)) {
        changes.add(NodeChange.PropertyChange.Added(name, val2))
      }
    }
  }

  return changes
}
