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

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.Reader
import java.io.Writer

/** Represents the extracted component tree and rendering metadata for a single preview. */
data class PreviewComponentTree(
  val previewId: String,
  @SerializedName(value = "methodFQN", alternate = ["methodFqn"]) val methodFQN: String,
  val displayName: String? = null,
  val width: Int,
  val height: Int,
  val densityDpi: Int? = null,
  val fontScale: Float? = null,
  val uiMode: Int? = null,
  val deviceId: String? = null,
  val components: List<ComponentTreeNode>,
)

/** A single node within the preview component tree. */
data class ComponentTreeNode(
  val name: String,
  val bounds: ComponentBounds,
  val sourceLocation: ComponentSourceLocation? = null,
  val children: List<ComponentTreeNode>? = null,
  val semantics: Map<String, String>? = null,
  val parameters: Map<String, String>? = null,
  val modifiers: Map<String, String>? = null,
)

/**
 * Pixel bounds of a rendered component in physical pixels (`px`) relative to the top-left `(0, 0)` origin of the preview root.
 *
 * [width] and [height] are denormalized convenience properties (`right - left` and `bottom - top`) included for JSON consumers.
 */
data class ComponentBounds(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
  val width: Int = right - left,
  val height: Int = bottom - top,
)

/** Source code location associated with a rendered component. */
data class ComponentSourceLocation(
  val fileName: String,
  val lineNumber: Int,
  val packageHash: Int? = null,
)

private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

/** Serializes [PreviewComponentTree] to JSON with standard 2-space indentation. */
fun writeComponentTreeToJson(writer: Writer, tree: PreviewComponentTree) {
  gson.toJson(tree, writer)
}

/**
 * Deserializes a [PreviewComponentTree] from JSON, validating that required non-null fields are present.
 *
 * @throws IllegalArgumentException if the JSON payload is null or omits required fields (`previewId`, `methodFQN`, `components`, `name`,
 *   `bounds`).
 */
fun readComponentTreeFromJson(reader: Reader): PreviewComponentTree {
  val tree =
    gson.fromJson(reader, PreviewComponentTree::class.java) ?: throw IllegalArgumentException("Component tree JSON is empty or null")
  validateComponentTree(tree)
  return tree
}

@Suppress("SENSELESS_COMPARISON")
private fun validateComponentTree(tree: PreviewComponentTree) {
  if (tree.previewId == null) throw IllegalArgumentException("Missing required field: previewId")
  if (tree.methodFQN == null) throw IllegalArgumentException("Missing required field: methodFQN")
  if (tree.components == null) throw IllegalArgumentException("Missing required field: components")
  for (node in tree.components) {
    validateComponentTreeNode(node)
  }
}

@Suppress("SENSELESS_COMPARISON")
private fun validateComponentTreeNode(node: ComponentTreeNode) {
  if (node == null) throw IllegalArgumentException("Component tree node cannot be null")
  if (node.name == null) throw IllegalArgumentException("Missing required field: component node 'name'")
  if (node.bounds == null) throw IllegalArgumentException("Missing required field: component node 'bounds'")
  val loc = node.sourceLocation
  if (loc != null && loc.fileName == null) {
    throw IllegalArgumentException("Missing required field: sourceLocation 'fileName'")
  }
  node.children?.forEach { validateComponentTreeNode(it) }
}
