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

/** Base class representing any node in the unified layout tree. */
sealed class UiNode {
  abstract val id: Long
  abstract val className: String
  abstract val bounds: Bounds
  abstract val children: MutableList<UiNode>

  data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

  data class Attribute(val name: String, val value: String, val directSource: String? = null, val styleChain: List<String> = emptyList())

  /** Represents the location in the source code where a layout node is defined. */
  data class SourceLocation(val filename: String, val lineNumber: Int)

  /** Represents a standard Android View node. */
  data class ViewNode(
    override val id: Long,
    override val className: String,
    override val bounds: Bounds,
    override val children: MutableList<UiNode> = mutableListOf(),
    val idResource: String?,
    val layoutResource: String?,
    val attributes: List<Attribute>,
  ) : UiNode()

  /** Represents a Jetpack Compose Composable node. */
  data class ComposeNode(
    override val id: Long,
    override val className: String,
    override val bounds: Bounds,
    override val children: MutableList<UiNode> = mutableListOf(),
    val sourceLocation: SourceLocation? = null,
  ) : UiNode()
}
