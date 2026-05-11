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

package com.android.tools.ui.inspector.inspectors.view

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import com.android.tools.ui.inspector.inspectors.view.property.PropertyCache
import com.android.tools.ui.inspector.inspectors.view.property.ProtoAttributeReader
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Resource
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.Attribute

/** Models the configuration for attribute extraction. */
internal sealed class AttributeExtraction {
  data class Enabled(val propertyCache: PropertyCache<View>) : AttributeExtraction()

  object Disabled : AttributeExtraction()
}

/** Flattens a view hierarchy into a [ViewNode] proto. */
internal fun View.toViewNode(stringTable: StringTable, includeAttributes: Boolean = true): ViewNode {
  val attributeExtraction =
    if (includeAttributes) {
      AttributeExtraction.Enabled(PropertyCache.createViewPropertyCache())
    } else {
      AttributeExtraction.Disabled
    }
  return createViewNode(view = this, stringTable = stringTable, attributeExtraction = attributeExtraction).build()
}

/**
 * Internal recursive implementation to flatten the view hierarchy.
 *
 * Returns a [ViewNode.Builder] to allow the parent to add it directly to its children list without eager building, optimizing memory
 * allocations during traversal.
 */
private fun createViewNode(view: View, stringTable: StringTable, attributeExtraction: AttributeExtraction): ViewNode.Builder {
  val viewClass = view::class.java

  val location = IntArray(2)
  view.getLocationOnScreen(location)
  val absPosX = location[0]
  val absPosY = location[1]

  return ViewNode.newBuilder().apply {
    id = view.uniqueDrawingId
    className = stringTable.put(viewClass.simpleName)
    val pkg = viewClass.`package`
    if (pkg != null) {
      packageName = stringTable.put(pkg.name)
    }

    bounds =
      Rect.newBuilder()
        .apply {
          x = absPosX
          y = absPosY
          width = view.width
          height = view.height
        }
        .build()

    // Create and set view id resource
    val res = view.createResource(stringTable, view.id)
    if (res != null) {
      idResource = res
    }

    // Create and set source layout id
    val layoutRes = view.createResource(stringTable, view.sourceLayoutResId)
    if (layoutRes != null) {
      layoutResource = layoutRes
    }

    visibility =
      when (view.visibility) {
        View.VISIBLE -> ViewNode.Visibility.VISIBLE
        View.INVISIBLE -> ViewNode.Visibility.INVISIBLE
        View.GONE -> ViewNode.Visibility.GONE
        else -> ViewNode.Visibility.VISIBLE // Fallback
      }
    // TODO: add support for View flags (e.g. isWebView)
    // TODO: add support for attribute resolution stack (where properties come from)
    // TODO: add support for theme and style resolution

    when (attributeExtraction) {
      is AttributeExtraction.Enabled -> populateAttributes(this, view, stringTable, attributeExtraction.propertyCache)
      AttributeExtraction.Disabled -> {}
    }

    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        addChildren(createViewNode(view = view.getChildAt(i), stringTable = stringTable, attributeExtraction = attributeExtraction))
      }
    }
  }
}

/**
 * Resolves and populates attributes into the [ViewNode.Builder].
 *
 * Traverses the View hierarchy recursively using the [PropertyCache] and writes mapped values as simplified [ViewNode.Attribute] key-value
 * string pairs.
 */
private fun populateAttributes(
  viewNodeBuilder: ViewNode.Builder,
  view: View,
  stringTable: StringTable,
  viewPropertyCache: PropertyCache<View>,
) {
  val viewPropertyData = viewPropertyCache.getOrResolve(view)
  view.forEachProtoAttribute(viewPropertyData, stringTable) { attribute -> viewNodeBuilder.addAttributes(attribute) }
}

/** Resolves and iterates over Android framework properties for this view, converting them into Proto [Attribute] messages. */
private fun View.forEachProtoAttribute(
  propertyData: PropertyCache.PropertyData<View>,
  stringTable: StringTable,
  onAttributeResolved: (Attribute) -> Unit,
) {
  val reader = ProtoAttributeReader(this, propertyData.properties, stringTable, onAttributeResolved)
  for (companion in propertyData.companions) {
    companion.readProperties(this, reader)
  }
}

/**
 * Resolves a resource ID into a [Resource] proto message containing namespace, type, and name. Returns null if the resource ID is invalid
 * or cannot be found.
 *
 * TODO: We should simplify this in the future and migrate to [resolveResourceToString] to avoid the redundant [Resource] proto message. At
 *   the moment the Resource is simply converted to string by the host.
 */
private fun View.createResource(stringTable: StringTable, resourceId: Int): Resource? {
  if (!isValidResourceId(resourceId)) {
    return null
  }

  return try {
    return Resource.newBuilder()
      .apply {
        type = stringTable.put(resources.getResourceTypeName(resourceId))
        namespace = stringTable.put(resources.getResourcePackageName(resourceId))
        name = stringTable.put(resources.getResourceEntryName(resourceId))
      }
      .build()
  } catch (_: Resources.NotFoundException) {
    null
  }
}

/** Resolves a framework resource ID directly into its string representation (e.g., `"@id/my_view"`). */
internal fun View.resolveResourceToString(resourceId: Int): String? {
  if (!isValidResourceId(resourceId)) {
    return null
  }
  return try {
    val type = resources.getResourceTypeName(resourceId)
    val pkg = resources.getResourcePackageName(resourceId)
    val name = resources.getResourceEntryName(resourceId)
    if (pkg == "android") {
      "@android:$type/$name"
    } else if (pkg == context.packageName) {
      "@$type/$name"
    } else {
      "@$pkg:$type/$name"
    }
  } catch (ex: Resources.NotFoundException) {
    null
  }
}

/**
 * Performs a fast check to determine if a resource ID is potentially valid.
 *
 * This is a performance optimization to avoid calling expensive resource resolution APIs (which throw [Resources.NotFoundException] and
 * spam logcat for invalid IDs) for views that don't have IDs.
 *
 * All valid Android resource IDs are positive integers.
 *
 * Note: We use strict bitwise checks similar to Layout Inspector to filter out positive integers that are not valid resource IDs,
 * preventing logcat spam (see b/299309384 and b/311414906).
 */
@VisibleForTesting
internal fun isValidResourceId(resourceId: Int): Boolean {
  if (resourceId <= 0) return false

  // A valid resource ID is structured as 0xPPTTEEEE where:
  // PP: Package ID, TT: Type ID, EEEE: Entry ID.
  val packageId = (resourceId shr 24) and 0xFF
  val typeId = (resourceId shr 16) and 0xFF

  // Both package and type must be non-zero.
  // Package ID 0xFF is disallowed in AssetManager2.
  return packageId != 0 && packageId != 0xFF && typeId != 0
}
