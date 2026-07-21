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

import com.android.tools.ui.inspector.ConfigurationDiff
import com.android.tools.ui.inspector.DeviceLocale
import com.android.tools.ui.inspector.Dimension
import com.android.tools.ui.inspector.NodeChange
import com.android.tools.ui.inspector.TimedUiDump
import com.android.tools.ui.inspector.TreeDiff
import com.android.tools.ui.inspector.UiNode
import com.android.tools.ui.inspector.createConfigurationDiff
import com.android.tools.ui.inspector.diffTrees
import java.io.PrintStream

/** Prints the detailed layout diffs between sequential frames collected during tracking. */
internal fun printTrackedChanges(samples: List<TimedUiDump>, out: PrintStream) {
  if (samples.isEmpty()) {
    out.println("No samples collected.")
    return
  }

  out.println("--- Frame 1 (+0ms) ---")
  val firstSample = samples.first()
  firstSample.uiDump.configuration?.let { printDeviceConfiguration(it, out) }
  firstSample.uiDump.roots.forEach { printUiTree(it, 0, out) }
  out.println()

  var prevSample = firstSample
  for (i in 1 until samples.size) {
    val sample = samples[i]
    out.println("--- Frame ${i + 1} (+${sample.elapsedTime.inWholeMilliseconds}ms) ---")
    val configDiff = createConfigurationDiff(prevSample.uiDump.configuration, sample.uiDump.configuration)
    printConfigurationDiff(configDiff, out)
    val diff = diffTrees(prevSample.uiDump.roots, sample.uiDump.roots)
    printTreeDiff(diff, out)
    out.println()
    prevSample = sample
  }
}

/** Prints a [ConfigurationDiff] to the console in a human-readable format. */
internal fun printConfigurationDiff(diff: ConfigurationDiff?, out: PrintStream) {
  if (diff == null || diff.differences.isEmpty()) return
  out.println(" Modified Configuration:")
  for (diffItem in diff.differences) {
    val displayName = formatPropertyName(diffItem.name)
    val oldStr = formatFieldValue(diffItem.name, diffItem.oldValue)
    val newStr = formatFieldValue(diffItem.name, diffItem.newValue)
    out.println("  $displayName: $oldStr -> $newStr")
  }
}

internal fun formatPropertyName(propertyName: String): String =
  propertyName.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/** Formats a configuration field value into a human-readable string. */
private fun formatFieldValue(fieldName: String, value: Any?): String {
  if (value == null) return "null"
  return when (value) {
    is Enum<*> -> value.name.lowercase()
    is DeviceLocale -> value.format()
    is Dimension.Dp -> "${value.value} dp"
    is Dimension.Dpi -> "${value.value} dpi"
    else -> value.toString()
  }
}

/** Prints the added, removed, and modified nodes from a [TreeDiff] to the console. */
private fun printTreeDiff(diff: TreeDiff, out: PrintStream) {
  if (diff.added.isEmpty() && diff.removed.isEmpty() && diff.modified.isEmpty()) {
    out.println(" No changes")
    return
  }

  if (diff.removed.isNotEmpty()) {
    out.println(" Removed nodes:")
    diff.removed.forEach { node -> out.println("  - ${node.formatHeader()} (id=${node.id})") }
  }

  if (diff.added.isNotEmpty()) {
    out.println(" Added nodes:")
    diff.added.forEach { node ->
      out.println("  + ${node.formatHeader()} (id=${node.id})")
      when (node) {
        is UiNode.ViewNode -> {
          node.attributes.forEach { attr -> out.println("  ${attr.format()}") }
        }
        is UiNode.ComposeNode -> {
          node.parameters.forEach { param ->
            val formatted = param.format()
            if (formatted.isNotEmpty()) {
              out.println("  $formatted")
            }
          }
        }
      }
    }
  }

  if (diff.modified.isNotEmpty()) {
    out.println(" Modified nodes:")
    diff.modified.forEach { mod ->
      val node = mod.node
      out.println("  * ${node.formatHeader()} (id=${node.id})")
      mod.changes.forEach { change ->
        val changeStr = formatNodeChange(node, change)
        out.println("   $changeStr")
      }
    }
  }
}

/** Formats a single [NodeChange] (class, bounds, parent, or property changes) into a human-readable string. */
internal fun formatNodeChange(node: UiNode, change: NodeChange): String {
  return when (change) {
    is NodeChange.ClassChange -> "class: ${change.oldClassName} -> ${change.newClassName}"
    is NodeChange.BoundsChange ->
      "bounds: (${change.oldBounds.x}, ${change.oldBounds.y}, ${change.oldBounds.width}, ${change.oldBounds.height}) -> (${change.newBounds.x}, ${change.newBounds.y}, ${change.newBounds.width}, ${change.newBounds.height})"
    is NodeChange.ParentChange -> {
      val oldParentStr = change.oldParentId?.let { "$it" } ?: "null"
      val newParentStr = change.newParentId?.let { "$it" } ?: "null"
      "parent: $oldParentStr -> $newParentStr"
    }
    is NodeChange.PropertyChange.Removed -> {
      val prefix = if (node is UiNode.ComposeNode) "param" else "prop"
      val valStr = formatRawPropertyValue(change.name, change.oldValue)
      "$prefix: ${change.name} removed (was $valStr)"
    }
    is NodeChange.PropertyChange.Added -> {
      val prefix = if (node is UiNode.ComposeNode) "param" else "prop"
      val valStr = formatRawPropertyValue(change.name, change.newValue)
      "$prefix: ${change.name}=$valStr added"
    }
    is NodeChange.PropertyChange.Modified -> {
      val prefix = if (node is UiNode.ComposeNode) "param" else "prop"
      val oldStr = formatRawPropertyValue(change.name, change.oldValue)
      val newStr = formatRawPropertyValue(change.name, change.newValue)
      "$prefix: ${change.name}=$oldStr -> $newStr"
    }
  }
}

internal fun formatRawPropertyValue(name: String, value: Any): String {
  return when (value) {
    is UiNode.ComposeParameter -> formatComposeParameter(value)
    is UiNode.AttributeValue -> value.format()
    else -> value.toString()
  }
}
