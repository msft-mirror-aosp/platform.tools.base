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

import android.view.View
import android.view.ViewGroup
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode

/** Flattens a view hierarchy into a [ViewNode] proto. */
internal fun View.toViewNode(stringTable: StringTable): ViewNode {
  return createViewNode(view = this, stringTable = stringTable).build()
}

/**
 * Internal recursive implementation to flatten the view hierarchy.
 *
 * Returns a [ViewNode.Builder] to allow the parent to add it directly to its children list without eager building, optimizing memory
 * allocations during traversal.
 */
private fun createViewNode(view: View, stringTable: StringTable): ViewNode.Builder {
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

    visibility =
      when (view.visibility) {
        View.VISIBLE -> ViewNode.Visibility.VISIBLE
        View.INVISIBLE -> ViewNode.Visibility.INVISIBLE
        View.GONE -> ViewNode.Visibility.GONE
        else -> ViewNode.Visibility.VISIBLE // Fallback
      }
    // TODO: add support for full Resource IDs (namespace, type, name)
    // TODO: add support for Layout Resources (sourceLayoutResId)
    // TODO: add support for View flags (e.g. isWebView)
    // TODO: add support for identifying properties (e.g. textValue for TextViews)
    // TODO: add support for full Property inspection (attributes)

    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        addChildren(createViewNode(view = view.getChildAt(i), stringTable = stringTable))
      }
    }
  }
}
