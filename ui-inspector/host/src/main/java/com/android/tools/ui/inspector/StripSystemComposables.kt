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

/** Returns a copy of [uiDump] without system-created Compose nodes: each one is replaced by its own children, in order. */
internal fun stripSystemComposables(uiDump: UiDump): UiDump =
  uiDump.copy(windows = uiDump.windows.map { window -> window.copy(root = stripViewNode(window.root)) })

private fun stripViewNode(node: UiNode.ViewNode): UiNode.ViewNode =
  node.copy(children = node.children.flatMap { stripNode(it) }.toMutableList())

private fun stripNode(node: UiNode): List<UiNode> =
  when (node) {
    is UiNode.ViewNode -> listOf(stripViewNode(node))
    is UiNode.ComposeNode -> {
      val strippedChildren = node.children.flatMap { stripNode(it) }
      if (node.isSystemCreated) {
        strippedChildren
      } else {
        listOf(node.copy(children = strippedChildren.toMutableList()))
      }
    }
  }
