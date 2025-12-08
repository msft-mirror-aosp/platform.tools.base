/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tools.agent.appinspection.proto

import android.content.Context
import android.content.res.Resources
import android.graphics.Matrix
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.AndroidRuntimeException
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import com.android.tools.agent.appinspection.framework.getChildren
import com.android.tools.agent.appinspection.framework.getTextValue
import com.android.tools.agent.appinspection.proto.property.PropertyCache
import com.android.tools.agent.appinspection.proto.property.SimplePropertyReader
import com.android.tools.agent.appinspection.proto.resource.convert
import com.android.tools.agent.appinspection.util.ThreadUtils
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol.AppContext
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol.Bounds
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol.GetPropertiesResponse
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol.PropertyGroup
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol.Quad
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol.Rect
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol.Resource
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol.ViewNode
import kotlin.math.roundToInt

/**
 * Convert the target [View] into a proto [ViewNode].
 *
 * This method must be called on the main thread to avoid race conditions when querying the tree.
 */
fun View.toNode(stringTable: StringTable): ViewNode {
    ThreadUtils.assertOnMainThread()

    // Screen location is (0, 0) for main window but useful inside floating dialogs
    val screenLocation = IntArray(2)
    getLocationOnScreen(screenLocation)

    val absPos = Point(screenLocation[0], screenLocation[1])
    return toNodeImpl(stringTable, absPos).build()
}

/**
 * Directly convert a view to a node, recursively adding children.
 */
private fun View.toNodeImpl(
    stringTable: StringTable,
    absOffset: Point
): ViewNode.Builder {
    val view = this
    val viewClass = view::class.java
    val absPos = Point(absOffset.x + view.left, absOffset.y + view.top)

    return ViewNode.newBuilder().apply {
        id = uniqueDrawingId

        createResource(stringTable, view.id)?.let { resource = it }
        className = stringTable.put(viewClass.simpleName)
        viewClass.`package`?.name?.let { packageName = stringTable.put(it) }

        bounds = Bounds.newBuilder().apply {
            layout = Rect.newBuilder().apply {
                x = absPos.x
                y = absPos.y
                w = view.width
                h = view.height
            }.build()

            val transform = Matrix()
            view.transformMatrixToGlobal(transform)

            // If the view is rotated (View#setRotation), we don't want to render the actual layout bounds,
            // instead we want to rotate them, to highlight the fact that the view is rotated.
            // In this case layout bound != render bound. In all other cases layout bounds == render bounds.
            // To determine if a view is rotated we take the view transformation and subtract the view's location.
            // If the result is not the identity matrix, it means the view is rotated.
            transform.postTranslate(-absPos.x.toFloat(), -absPos.y.toFloat())
            if (!transform.isIdentity) {
                transform.postTranslate(absPos.x.toFloat(), absPos.y.toFloat())
                val w = view.width.toFloat()
                val h = view.height.toFloat()
                val corners = floatArrayOf(
                    0f, 0f,
                    w, 0f,
                    w, h,
                    0f, h,
                )
                transform.mapPoints(corners)
                if (corners.none { it.isNaN() }) {
                    // If the view is rotated its bounds are not a rectangle anymore, for this reason we use a quad.
                    render = Quad.newBuilder().apply {
                        x0 = corners[0].roundToInt()
                        y0 = corners[1].roundToInt()
                        x1 = corners[2].roundToInt()
                        y1 = corners[3].roundToInt()
                        x2 = corners[4].roundToInt()
                        y2 = corners[5].roundToInt()
                        x3 = corners[6].roundToInt()
                        y3 = corners[7].roundToInt()
                    }.build()
                }
            }
        }.build()

        createResource(stringTable, view.sourceLayoutResId)?.let { layoutResource = it }
        (view.layoutParams as? WindowManager.LayoutParams)?.let { params ->
            layoutFlags = params.flags
        }
        if (view is WebView) {
            flags = ViewNode.Flag.IS_WEBVIEW.number
        }

        view.getTextValue()?.let { text ->
            textValue = stringTable.put(text)
        }
        if (view is ViewGroup) {
            view.getChildren().forEach { child ->
                addChildren(child.toNodeImpl(stringTable, Point(absPos.x - scrollX, absPos.y - scrollY)))
            }
        }
    }
}

/**
 * Search this view for a resource with matching [resourceId] and, if found, return its
 * proto representation.
 */
fun View.createResource(stringTable: StringTable, resourceId: Int): Resource? {
    if (!isValidResourceId(resourceId)) return null

    return try {
        return Resource.newBuilder().apply {
            type = stringTable.put(resources.getResourceTypeName(resourceId))
            namespace = stringTable.put(resources.getResourcePackageName(resourceId))
            name = stringTable.put(resources.getResourceEntryName(resourceId))
        }.build()
    } catch (ex: Resources.NotFoundException) {
        null
    }
}

private fun isValidResourceId(resourceId: Int): Boolean {
    if (resourceId == Resources.ID_NULL) {
        return false
    }
    // The package and type should be non zero, and the package should not be 0xff.
    // See the function: is_valid_resid in the frameworks ResourceUtils.h
    // and the function: AssetManager2::FindEntry (disallows 0xff for package id)
    return (resourceId and Resources.ID_PACKAGE_MASK) != 0 &&
            (resourceId and Resources.ID_PACKAGE_MASK) != Resources.ID_PACKAGE_MASK &&
            (resourceId and Resources.ID_TYPE_MASK) != 0
}

fun View.getNamespace(attributeId: Int): String =
    if (attributeId != 0) resources.getResourcePackageName(attributeId) else ""

fun View.createAppContext(stringTable: StringTable): AppContext {
    val displayInfo = buildDisplayInfo(context)

    return AppContext.newBuilder().apply {
        createResource(stringTable, context.themeResId)?.let { themeResource ->
            theme = themeResource
        }
        addAllDisplayInfo(displayInfo)
    }.build()
}

fun View.createConfiguration(stringTable: StringTable) =
    context.resources.configuration.convert(stringTable)

fun View.createGetPropertiesResponse(): GetPropertiesResponse {
    val stringTable = StringTable()
    val view = this

    return GetPropertiesResponse.newBuilder().apply {
        propertyGroup = view.createPropertyGroup(stringTable)
        addAllStrings(stringTable.toStringEntries())
    }.build()
}

fun View.createPropertyGroup(stringTable: StringTable): PropertyGroup {
    // In general, run off the main thread so we don't block the app doing expensive work.
    ThreadUtils.assertOffMainThread()
    return if (this !is WebView) {
        try {
            createPropertyGroupImpl(stringTable)
        }
        catch (ex: AndroidRuntimeException) {
            // Some properties require work performed on the UIThread before it is accessible.
            // Example: View.resolvePadding() is called from View.getPaddingLeft()
            ThreadUtils.runOnMainThread { createPropertyGroupImpl(stringTable) }.get()
        }
    }
    else {
        // WebView uniquely throws exceptions if you try to read its properties off the main thread,
        // so we have no choice in this case.
        ThreadUtils.runOnMainThread { createPropertyGroupImpl(stringTable) }.get()
    }
}

fun buildDisplayInfo(context: Context): List<LayoutInspectorViewProtocol.Display> {
    val displays = context.getAllDisplays()
    return displays.map {
        val displaySize = it.physicalSizePx()
        LayoutInspectorViewProtocol.Display.newBuilder().apply {
            id = it.displayId
            orientation = it.getOrientation()
            width = displaySize.x
            height = displaySize.y
        }.build()
    }
}

fun Context.getAllDisplays(): List<Display> {
    val displayManager = getSystemService(DisplayManager::class.java)
    return displayManager?.displays?.toList() ?: emptyList()
}

fun Display.getOrientation(): Int {
    return when (rotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> -1
    }
}

/** Physical resolution in pixels for this Display. */
fun Display.physicalSizePx(): Point {
  return if (Build.VERSION.SDK_INT >= 23) {
      val m = mode
      Point(m.physicalWidth, m.physicalHeight)
  }
  else {
      val p = Point()
      getRealSize(p)
      Point(p.x, p.y)
  }
}

private fun View.createPropertyGroupImpl(stringTable: StringTable): PropertyGroup {
    val viewCacheMap = PropertyCache.createViewCache()
    val layoutCacheMap = PropertyCache.createLayoutParamsCache()

    val viewCache = viewCacheMap.typeOf(this)
    val layoutCache = layoutCacheMap.typeOf(layoutParams)

    val viewProperties = viewCache.properties
    val layoutProperties = layoutCache.properties

    val viewReader =
        SimplePropertyReader(
            stringTable,
            this,
            viewProperties,
            SimplePropertyReader.PropertyCategory.VIEW
        )
    viewCache.readProperties(this, viewReader)
    val layoutReader =
        SimplePropertyReader(
            stringTable,
            this,
            layoutProperties,
            SimplePropertyReader.PropertyCategory.LAYOUT_PARAMS
        )
    layoutCache.readProperties(layoutParams, layoutReader)

    val view = this
    return PropertyGroup.newBuilder().apply {
        this.viewId = view.uniqueDrawingId

        view.createResource(stringTable, sourceLayoutResId)?.let { layoutResource ->
            layout = layoutResource
        }

        (viewProperties + layoutProperties)
            .mapNotNull { property -> property.build(stringTable, view) }
            .forEach { property -> this.addProperty(property) }
    }.build()
}

fun android.graphics.Rect.toRect(): Rect = Rect.newBuilder().apply {
    x = left
    y = top
    w = width()
    h = height()
}.build()

/** Returns the display this view is attached to, or null if the view is not attached */
fun View.getDisplayCompat(): Display? {
    return if (Build.VERSION.SDK_INT >= 17) {
        this.display
    }
    else if (this.windowToken != null) {
        // This code path is for completeness only, since embedded LI currently requires API 29+.
        // For older APIs, we fall back to the default display.
        // A view must be attached to a window to have a display.
        val windowManager = this.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay
    }
    else {
        // If not attached, we can't determine the display.
        null
    }
}
