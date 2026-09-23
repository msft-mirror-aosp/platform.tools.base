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

package com.android.tools.ui.inspector.printer.text

import com.android.tools.ui.inspector.model.UiNode
import java.util.Locale

/** Formats a node's header details (class name, resource/source locations, and bounds) consistently for text printing. */
internal fun UiNode.formatHeader(): String {
  val boundsStr = "(${bounds.x}, ${bounds.y}, ${bounds.width}, ${bounds.height})"
  return when (this) {
    is UiNode.ViewNode -> {
      val resourceStr = idResource?.let { " id=$it" } ?: ""
      val layoutResourceStr = layoutResource?.let { " layout=$it" } ?: ""
      "[$className]$resourceStr$layoutResourceStr $boundsStr"
    }
    is UiNode.ComposeNode -> {
      val sourceLocation =
        sourceLocation?.let {
          val lineSuffix = if (it.lineNumber > 0) ":${it.lineNumber}" else ""
          " file=${it.filename}$lineSuffix"
        } ?: ""
      "[$className]$sourceLocation [compose] $boundsStr"
    }
  }
}

/** Formats an attribute key-value pair consistently for text printing. */
internal fun UiNode.Attribute.format(): String {
  return "prop: $name=${value.format()}"
}

/** Formats an attribute value consistently for text printing. */
internal fun UiNode.AttributeValue.format(): String {
  return when (this) {
    is UiNode.AttributeValue.StringVal -> value
    is UiNode.AttributeValue.BooleanVal -> value.toString()
    is UiNode.AttributeValue.ColorVal -> "#%08X".format(Locale.US, colorInt)
    is UiNode.AttributeValue.DimensionVal -> {
      val num = value
      val spValue = sp
      val dpValue = dp
      val numStr = if (num % 1.0f == 0.0f) num.toInt().toString() else "%.2f".format(Locale.US, num)
      if (spValue != null) {
        val spStr = if (spValue % 1.0f == 0.0f) spValue.toInt().toString() else "%.2f".format(Locale.US, spValue)
        "${numStr}px (${spStr}sp)"
      } else if (dpValue != null) {
        val dpStr = if (dpValue % 1.0f == 0.0f) dpValue.toInt().toString() else "%.2f".format(Locale.US, dpValue)
        "${numStr}px (${dpStr}dp)"
      } else {
        "${numStr}px"
      }
    }
    is UiNode.AttributeValue.NumberVal -> {
      val num = value
      if (num is Double || num is Float) "%.2f".format(Locale.US, num.toDouble()) else num.toString()
    }
    UiNode.AttributeValue.NullVal -> ""
  }
}

/** Formats a Compose parameter key-value pair consistently for text printing (returns empty if value is empty/null). */
internal fun UiNode.ComposeParameter.format(): String {
  val formattedValue = formatComposeParameter(this)
  return if (formattedValue.isNotEmpty()) "param: $name=$formattedValue" else ""
}

/** Recursively formats a rich ComposeParameter to its text display string. */
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
      val unitName = value.unit.name.lowercase(Locale.ROOT)
      "${value.value}$unitName"
    }
    is UiNode.ComposeParameter.Value.ColorVal -> {
      "#%08X".format(Locale.US, value.colorInt)
    }
    is UiNode.ComposeParameter.Value.ResourceVal -> {
      val namespace = value.namespace?.let { "$it:" } ?: ""
      val type = value.type?.let { "$it/" } ?: ""
      "@$namespace$type${value.name}"
    }
    is UiNode.ComposeParameter.Value.LambdaVal -> {
      if (value.fileName != null) {
        val startLineNumber = value.startLineNumber
        val lineSuffix = if (startLineNumber != null && startLineNumber > 0) ":$startLineNumber" else ""
        "[lambda in ${value.fileName}$lineSuffix]"
      } else {
        "[lambda]"
      }
    }
    is UiNode.ComposeParameter.Value.NullVal -> ""
  }
}
