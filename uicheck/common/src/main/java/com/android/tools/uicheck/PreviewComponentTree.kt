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

import com.google.gson.annotations.SerializedName

// TODO(b/581299851): These data classes are duplicated from com.android.tools.render.common.PreviewComponentTree
//  in standalone-render to avoid pulling LayoutLib into uicheck. Extract into a shared lightweight
//  module that both standalone-render and uicheck can depend on.

/** Component tree and rendering metadata extracted from a rendered preview. */
data class PreviewComponentTree(
  val previewId: String,
  @SerializedName(value = "methodFQN", alternate = ["methodFqn"]) val methodFqn: String,
  val displayName: String? = null,
  val width: Int,
  val height: Int,
  val densityDpi: Int? = null,
  val fontScale: Float? = null,
  val uiMode: Int? = null,
  val deviceId: String? = null,
  val components: List<ComponentTreeNode>,
)

/** A node within a [PreviewComponentTree]. */
data class ComponentTreeNode(
  val name: String,
  val bounds: ComponentBounds,
  val sourceLocation: ComponentSourceLocation? = null,
  val children: List<ComponentTreeNode>? = null,
  val semantics: Map<String, String>? = null,
  val parameters: Map<String, String>? = null,
  val modifiers: Map<String, String>? = null,
)

/** Pixel bounds of a rendered component relative to the top-left `(0, 0)` origin of the preview root. */
data class ComponentBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val width: Int
    get() = right - left

  val height: Int
    get() = bottom - top
}

/** Source code location associated with a rendered component. */
data class ComponentSourceLocation(
  val fileName: String,
  val lineNumber: Int,
  val packageHash: Int? = null,
)
