/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.android.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.ui.inspector

/** Recursively walks and formats the unified UiNode layout tree to console. */
internal fun printUiTree(node: UiNode, indent: Int) {
  if (indent == 0) {
    System.out.println("View Hierarchy:")
  }
  val prefix = "  ".repeat(indent)
  when (node) {
    is UiNode.ViewNode -> {
      val resourceStr = node.idResource?.let { " id=$it" } ?: ""
      val layoutResourceStr = node.layoutResource?.let { " layout=$it" } ?: ""
      System.out.println(
        "${prefix}[${node.className}]$resourceStr$layoutResourceStr (${node.bounds.x}, ${node.bounds.y}, ${node.bounds.width}, ${node.bounds.height})"
      )
      node.attributes.forEach { attr ->
        System.out.println("$prefix  prop: ${attr.name}=${attr.value}")

        val sourceStr = attr.directSource ?: ""
        if (sourceStr.isNotEmpty()) {
          System.out.println("$prefix    Defined in: $sourceStr")
        }

        attr.styleChain.forEach { style -> System.out.println("$prefix    Inherited from: $style") }
      }
    }
    is UiNode.ComposeNode -> {
      val sourceLocation =
        node.sourceLocation?.let {
          val lineSuffix = if (it.lineNumber > 0) ":${it.lineNumber}" else ""
          " file=${it.filename}$lineSuffix"
        } ?: ""

      System.out.println(
        "${prefix}[${node.className}]$sourceLocation [compose] (${node.bounds.x}, ${node.bounds.y}, ${node.bounds.width}, ${node.bounds.height})"
      )
    }
  }
  node.children.forEach { printUiTree(it, indent + 1) }
}
