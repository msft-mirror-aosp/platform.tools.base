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

package com.android.tools.uicheck

import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.Reader

/** Parser and deserializer for [PreviewComponentTree] JSON payloads. */
object PreviewComponentTreeParser {

  private val gson = Gson()

  /**
   * Deserializes a [PreviewComponentTree] from [reader], validating that required non-null fields are present.
   *
   * @throws IllegalArgumentException if the JSON payload is null or omits required fields (`previewId`, `methodFQN`, `components`, `name`,
   *   `bounds`).
   */
  fun parse(reader: Reader): PreviewComponentTree {
    try {
      val tree =
        gson.fromJson(reader, PreviewComponentTree::class.java) ?: throw IllegalArgumentException("Component tree JSON is empty or null")
      validateComponentTree(tree)
      return tree
    } catch (e: JsonParseException) {
      throw IllegalArgumentException("Malformed component tree JSON", e)
    }
  }

  /** Validates that required top-level fields on [tree] are non-null after Gson deserialization. */
  private fun validateComponentTree(tree: PreviewComponentTree) {
    requireNotNull(tree.previewId) { "Missing required field: previewId" }
    requireNotNull(tree.methodFqn) { "Missing required field: methodFQN" }
    requireNotNull(tree.components) { "Missing required field: components" }
    tree.components.forEach(::validateComponentTreeNode)
  }

  /** Recursively validates that required fields on [node] and its children are non-null after Gson deserialization. */
  private fun validateComponentTreeNode(node: ComponentTreeNode?) {
    requireNotNull(node) { "Component tree node cannot be null" }
    requireNotNull(node.name) { "Missing required field: component node 'name'" }
    requireNotNull(node.bounds) { "Missing required field: component node 'bounds'" }
    node.sourceLocation?.let {
      requireNotNull(it.fileName) { "Missing required field: sourceLocation 'fileName'" }
    }
    node.children?.forEach(::validateComponentTreeNode)
  }
}
