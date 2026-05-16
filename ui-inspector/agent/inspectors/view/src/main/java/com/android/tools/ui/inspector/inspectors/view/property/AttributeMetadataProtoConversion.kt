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

package com.android.tools.ui.inspector.inspectors.view.property

import android.view.View
import com.android.tools.ui.inspector.inspectors.view.StringTable
import com.android.tools.ui.inspector.inspectors.view.resolveResourceToString
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.Attribute

/** Resolves the framework value and builds a simplified [Attribute] proto message. */
internal fun AttributeMetadata.toProtoAttribute(
  stringTable: StringTable,
  view: View,
  value: Any,
  sourceMap: Map<Int, Int>,
  includeResolutionStack: Boolean,
): Attribute? {
  val resolvedValueString = resolveValueToString(view, value) ?: return null
  val builder = Attribute.newBuilder().setName(stringTable.put(name)).setValue(stringTable.put(resolvedValueString))

  // Set direct source if present in the map
  sourceMap[attributeId]?.let { sourceResId ->
    view.resolveResourceToString(sourceResId)?.let { resourceStr -> builder.setDirectSource(stringTable.put(resourceStr)) }
  }

  if (includeResolutionStack) {
    // Requires debug_view_attributes flag to be enabled on the device to return non-empty stacks.
    val stack = view.getAttributeResolutionStack(attributeId)
    for (resId in stack) {
      view.resolveResourceToString(resId)?.let { resourceStr -> builder.addStyleChain(stringTable.put(resourceStr)) }
    }
  }

  return builder.build()
}

/**
 * Decodes and formats the raw framework property value into its string representation (e.g., colors to hex, enums to names, flags to joined
 * strings).
 */
private fun AttributeMetadata.resolveValueToString(view: View, value: Any): String? {
  return when (type) {
    PropertyType.STRING -> value as String
    PropertyType.INT_ENUM -> if (value is String) value else value.toString()
    PropertyType.INT32,
    PropertyType.INT16,
    PropertyType.BYTE,
    PropertyType.CHAR,
    PropertyType.DIMENSION -> value.toString()
    PropertyType.BOOLEAN -> {
      val boolVal = if (value is Int) value != 0 else value as Boolean
      boolVal.toString()
    }
    PropertyType.GRAVITY,
    PropertyType.INT_FLAG -> (value as Set<*>).joinToString("|")
    PropertyType.INT64 -> (value as Long).toString()
    PropertyType.DOUBLE -> (value as Double).toString()
    PropertyType.FLOAT -> (value as Float).toString()
    PropertyType.RESOURCE -> {
      if (value is Int) {
        // Fall back to formatting the raw integer as a hex ID if resource resolution fails
        view.resolveResourceToString(value) ?: String.format("0x%08X", value)
      } else {
        value.toString()
      }
    }
    // Format the 32-bit ARGB color integer into a standard hexadecimal string (e.g. #FFFFFFFF)
    PropertyType.COLOR -> String.format("#%08X", value as Int)
    PropertyType.DRAWABLE,
    PropertyType.ANIM,
    PropertyType.ANIMATOR,
    PropertyType.INTERPOLATOR -> value.javaClass.name
    else -> value.toString()
  }
}
