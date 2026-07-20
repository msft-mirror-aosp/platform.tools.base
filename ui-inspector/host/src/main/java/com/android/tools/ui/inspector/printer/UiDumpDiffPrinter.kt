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

package com.android.tools.ui.inspector.printer

import com.android.tools.ui.inspector.ConfigurationDiff
import com.android.tools.ui.inspector.DeviceLocale
import com.android.tools.ui.inspector.Dimension
import com.android.tools.ui.inspector.NodeChange
import com.android.tools.ui.inspector.TimedUiDump
import com.android.tools.ui.inspector.TreeDiff
import com.android.tools.ui.inspector.UiNode
import com.android.tools.ui.inspector.createConfigurationDiff
import com.android.tools.ui.inspector.diffTrees

/** Prints the detailed layout diffs between sequential frames collected during tracking. */
internal fun printTrackedChanges(samples: List<TimedUiDump>, includeAttributes: Boolean, includeSemantics: Boolean) {
  if (samples.isEmpty()) {
    System.out.println("No samples collected.")
    return
  }

  System.out.println("--- Frame 1 (+0ms) ---")
  val firstSample = samples.first()
  firstSample.uiDump.configuration?.let { printDeviceConfiguration(it) }
  firstSample.uiDump.roots.forEach { printUiTree(it, 0, includeAttributes, includeSemantics) }
  System.out.println()

  var prevSample = firstSample
  for (i in 1 until samples.size) {
    val sample = samples[i]
    System.out.println("--- Frame ${i + 1} (+${sample.elapsedTime.inWholeMilliseconds}ms) ---")
    val configDiff = createConfigurationDiff(prevSample.uiDump.configuration, sample.uiDump.configuration)
    printConfigurationDiff(configDiff)
    val diff = diffTrees(prevSample.uiDump.roots, sample.uiDump.roots)
    printTreeDiff(diff, includeAttributes, includeSemantics)
    System.out.println()
    prevSample = sample
  }
}

/** Prints a [ConfigurationDiff] to the console in a human-readable format. */
internal fun printConfigurationDiff(diff: ConfigurationDiff?) {
  if (diff == null || diff.differences.isEmpty()) return
  System.out.println(" Modified Configuration:")
  for (diffItem in diff.differences) {
    val displayName = formatPropertyName(diffItem.name)
    val oldStr = formatFieldValue(diffItem.name, diffItem.oldValue)
    val newStr = formatFieldValue(diffItem.name, diffItem.newValue)
    System.out.println("  $displayName: $oldStr -> $newStr")
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
private fun printTreeDiff(diff: TreeDiff, includeAttributes: Boolean, includeSemantics: Boolean) {
  if (diff.added.isEmpty() && diff.removed.isEmpty() && diff.modified.isEmpty()) {
    System.out.println(" No changes")
    return
  }

  if (diff.removed.isNotEmpty()) {
    System.out.println(" Removed nodes:")
    diff.removed.forEach { node -> System.out.println("  - ${node.formatHeader()} (id=${node.id})") }
  }

  if (diff.added.isNotEmpty()) {
    System.out.println(" Added nodes:")
    diff.added.forEach { node ->
      System.out.println("  + ${node.formatHeader()} (id=${node.id})")
      if (includeAttributes) {
        when (node) {
          is UiNode.ViewNode -> {
            node.attributes.forEach { attr -> System.out.println("  ${attr.format()}") }
          }
          is UiNode.ComposeNode -> {
            node.parameters.forEach { param ->
              val formatted = param.format()
              if (formatted.isNotEmpty()) {
                System.out.println("  $formatted")
              }
            }
          }
        }
      }
    }
  }

  if (diff.modified.isNotEmpty()) {
    System.out.println(" Modified nodes:")
    diff.modified.forEach { mod ->
      val node = mod.node
      System.out.println("  * ${node.formatHeader()} (id=${node.id})")
      mod.changes.forEach { change ->
        val changeStr = formatNodeChange(node, change)
        System.out.println("   $changeStr")
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
