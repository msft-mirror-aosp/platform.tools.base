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

/** Recursively walks and formats the unified UiNode layout tree to console. */
internal fun printUiTree(node: UiNode, indent: Int, includeAttributes: Boolean, includeSemantics: Boolean) {
  if (indent == 0) {
    System.out.println("View Hierarchy:")
  }
  val prefix = " ".repeat(indent)
  when (node) {
    is UiNode.ViewNode -> {
      val resourceStr = node.idResource?.let { " id=$it" } ?: ""
      val layoutResourceStr = node.layoutResource?.let { " layout=$it" } ?: ""
      System.out.println(
        "${prefix}[${node.className}]$resourceStr$layoutResourceStr (${node.bounds.x}, ${node.bounds.y}, ${node.bounds.width}, ${node.bounds.height})"
      )
      if (includeAttributes) {
        node.attributes.forEach { attr ->
          System.out.println("$prefix prop: ${attr.name}=${attr.value}")

          val sourceStr = attr.directSource ?: ""
          if (sourceStr.isNotEmpty()) {
            System.out.println("$prefix  Defined in: $sourceStr")
          }

          attr.styleChain.forEach { style -> System.out.println("$prefix  Inherited from: $style") }
        }
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
      if (includeAttributes) {
        node.parameters.forEach { param ->
          val formattedValue = formatComposeParameter(param)
          if (formattedValue.isNotEmpty()) {
            System.out.println("$prefix param: ${param.name}=$formattedValue")
          }
        }
      }

      if (includeSemantics) {
        // Print Merged Semantics (preferred for general accessibility audits)
        node.mergedSemantics.forEach { param ->
          val formattedValue = formatComposeParameter(param)
          if (formattedValue.isNotEmpty()) {
            System.out.println("$prefix semantics: ${param.name}=$formattedValue")
          }
        }

        // Optionally, if merged is empty but unmerged has elements, print unmerged
        if (node.mergedSemantics.isEmpty()) {
          node.unmergedSemantics.forEach { param ->
            val formattedValue = formatComposeParameter(param)
            if (formattedValue.isNotEmpty()) {
              System.out.println("$prefix semantics: ${param.name}=$formattedValue")
            }
          }
        }
      }
    }
  }
  node.children.forEach { printUiTree(it, indent + 1, includeAttributes, includeSemantics) }
}

/** Recursively formats a rich ComposeParameter to its display string. */
internal fun formatComposeParameter(param: UiNode.ComposeParameter): String {
  return when (param) {
    is UiNode.ComposeParameter.Single -> {
      formatComposeValue(param.value)
    }
    is UiNode.ComposeParameter.Group -> {
      if (param.isCollection) {
        param.elements.joinToString(prefix = "[", postfix = "]") { formatComposeParameter(it) }
      } else {
        val fields = param.elements.joinToString(", ") { "${it.name}=${formatComposeParameter(it)}" }
        "{$fields}"
      }
    }
  }
}

private fun formatComposeValue(value: UiNode.ComposeParameter.Value): String {
  return when (value) {
    is UiNode.ComposeParameter.Value.StringVal -> value.value
    is UiNode.ComposeParameter.Value.BooleanVal -> value.value.toString()
    is UiNode.ComposeParameter.Value.NumberVal -> value.value.toString()
    is UiNode.ComposeParameter.Value.DimensionVal -> {
      val unitName = value.unit.name.lowercase()
      "${value.value}$unitName"
    }
    is UiNode.ComposeParameter.Value.ColorVal -> {
      "#%08X".format(value.colorInt)
    }
    is UiNode.ComposeParameter.Value.ResourceVal -> {
      val namespace = value.namespace?.let { "$it:" } ?: ""
      val type = value.type?.let { "$it/" } ?: ""
      "@$namespace$type${value.name}"
    }
    is UiNode.ComposeParameter.Value.LambdaVal -> {
      if (value.fileName != null) {
        val lineSuffix = if (value.startLineNumber != null && value.startLineNumber > 0) ":${value.startLineNumber}" else ""
        "[lambda in ${value.fileName}$lineSuffix]"
      } else {
        "[lambda]"
      }
    }
    is UiNode.ComposeParameter.Value.NullVal -> ""
  }
}
