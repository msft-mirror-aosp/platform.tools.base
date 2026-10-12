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

package com.android.tools.render.common

import com.android.tools.render.common.ComponentTreeExtractor.extractBoundCoord
import com.android.tools.render.common.ComponentTreeExtractor.findField
import com.android.tools.render.common.ComponentTreeExtractor.invokeGetter
import com.android.tools.render.common.ComponentTreeExtractor.isMeaningfulGroupName
import com.android.tools.render.common.ComposeSemanticsExtractor.DEFAULT_OBJECT_TO_STRING_REGEX
import com.android.tools.render.common.ComposeSemanticsExtractor.formatColorValue
import java.util.IdentityHashMap
import java.util.logging.Level
import java.util.logging.Logger

/** Extracts composable call parameters from Compose `SlotTable` trees (`SlotTreeKt.asTree`). */
internal object ComposeParameterExtractor {
  private val logger = Logger.getLogger(ComposeParameterExtractor::class.java.name)
  private val syntheticParamRegex = Regex("""^(?:arg\$|f\$?)(\d+)$""")

  /**
   * Compose `TextUnit` packs its unit type in the upper 32 bits (`0` = Unspecified, `0x100000000L` -> `1` = Sp, `0x200000000L` -> `2` = Em)
   * and the IEEE-754 `Float` bit representation in the lower 32 bits (`0x7FC00000` = `Float.NaN`).
   */
  private const val FLOAT_NAN_BITS = 0x7FC00000L

  internal class ParamLookup {
    private val exactMap = mutableMapOf<String, Map<String, String>>()
    private val nameLocationMap = mutableMapOf<String, MutableList<Map<String, String>>>()
    private val nameBoundsMap = mutableMapOf<String, MutableList<Map<String, String>>>()
    private val groupNameByExactLocationAndBounds = mutableMapOf<String, String>()
    private val groupNameByLocation = mutableMapOf<String, String>()
    private val groupNameByLayoutNode = IdentityHashMap<Any, String>()

    fun put(fileName: String, lineNumber: Int, name: String, bounds: ComponentBounds, params: Map<String, String>) {
      val boundsKey = "$name:${bounds.left},${bounds.top},${bounds.width},${bounds.height}"
      if (fileName.isNotBlank() && lineNumber >= 0) {
        exactMap["$fileName:$lineNumber:$boundsKey"] = params
        nameLocationMap.getOrPut("$fileName:$lineNumber:$name") { mutableListOf() }.add(params)
      }
      nameBoundsMap.getOrPut(boundsKey) { mutableListOf() }.add(params)
    }

    fun putGroupName(fileName: String, lineNumber: Int, bounds: ComponentBounds, name: String) {
      if (fileName.isNotBlank() && lineNumber >= 0) {
        val coordKey = "${bounds.left},${bounds.top},${bounds.width},${bounds.height}"
        groupNameByExactLocationAndBounds.putIfAbsent("$fileName:$lineNumber:$coordKey", name)
        groupNameByLocation.putIfAbsent("$fileName:$lineNumber", name)
      }
    }

    fun putLayoutNodeGroupName(layoutNode: Any, name: String) {
      groupNameByLayoutNode[layoutNode] = name
    }

    fun findGroupName(
      fileName: String?,
      lineNumber: Int?,
      bounds: ComponentBounds,
      layoutNode: Any? = null,
    ): String? {
      if (!fileName.isNullOrBlank() && lineNumber != null && lineNumber >= 0) {
        val coordKey = "${bounds.left},${bounds.top},${bounds.width},${bounds.height}"
        groupNameByExactLocationAndBounds["$fileName:$lineNumber:$coordKey"]?.let {
          return it
        }
        groupNameByLocation["$fileName:$lineNumber"]?.let {
          return it
        }
      }
      if (layoutNode != null) {
        groupNameByLayoutNode[layoutNode]?.let {
          return it
        }
      }
      return null
    }

    fun findParameters(
      fileName: String?,
      lineNumber: Int?,
      name: String?,
      bounds: ComponentBounds,
    ): Map<String, String>? {
      if (name.isNullOrBlank()) return null
      val boundsKey = "$name:${bounds.left},${bounds.top},${bounds.width},${bounds.height}"
      if (!fileName.isNullOrBlank() && lineNumber != null && lineNumber >= 0) {
        exactMap["$fileName:$lineNumber:$boundsKey"]?.let {
          return it
        }
      }
      nameBoundsMap[boundsKey]?.firstOrNull()?.let {
        return it
      }
      if (!fileName.isNullOrBlank() && lineNumber != null && lineNumber >= 0) {
        val list = nameLocationMap["$fileName:$lineNumber:$name"]
        if (list?.size == 1) {
          return list[0]
        }
      }
      return null
    }
  }

  fun extractParametersFromSlotTable(adapter: Any, cl: ClassLoader?): ParamLookup {
    val lookup = ParamLookup()
    try {
      val slotField = findField(adapter.javaClass, "slotTableRecord")
      val record = slotField?.get(adapter) ?: invokeGetter(adapter, "slotTableRecord") ?: return lookup
      val store = invokeGetter(record, "store") as? Set<*> ?: return lookup

      val slotTreeClass =
        try {
          Class.forName("androidx.compose.ui.tooling.data.SlotTreeKt", true, cl)
        } catch (_: Exception) {
          null
        } catch (_: LinkageError) {
          null
        } ?: return lookup
      val asTreeMethod = slotTreeClass.methods.firstOrNull { it.name == "asTree" && it.parameterCount == 1 } ?: return lookup

      for (compData in store) {
        if (compData == null) continue
        val rootGroup = asTreeMethod.invoke(null, compData)
        walkSlotGroup(rootGroup, cl, lookup)
      }
    } catch (e: Exception) {
      logger.log(Level.FINE, "Failed to extract parameters from Compose slot table", e)
    } catch (e: LinkageError) {
      logger.log(Level.FINE, "Linkage error extracting parameters from Compose slot table", e)
    }
    return lookup
  }

  internal fun walkSlotGroup(
    group: Any?,
    cl: ClassLoader?,
    lookup: ParamLookup,
    enclosingGroupName: String? = null,
  ) {
    if (group == null) return
    val gName = (invokeGetter(group, "name") as? String) ?: ""
    val location = invokeGetter(group, "location")
    val sourceFile = location?.let { invokeGetter(it, "sourceFile") as? String } ?: ""
    val lineNumber = location?.let { (invokeGetter(it, "lineNumber") as? Number)?.toInt() } ?: -1
    val rawBox = invokeGetter(group, "box")
    val left = extractBoundCoord(rawBox, "left")
    val top = extractBoundCoord(rawBox, "top")
    val right = extractBoundCoord(rawBox, "right")
    val bottom = extractBoundCoord(rawBox, "bottom")
    val bounds = ComponentBounds(left, top, right, bottom)

    val isMeaningful = isMeaningfulGroupName(gName)
    if (isMeaningful) {
      lookup.putGroupName(sourceFile, lineNumber, bounds, gName)
    }
    val currentGroupName = if (isMeaningful) gName else enclosingGroupName
    val nodeObj = invokeGetter(group, "node")
    if (nodeObj != null && !currentGroupName.isNullOrBlank()) {
      lookup.putLayoutNodeGroupName(nodeObj, currentGroupName)
    }

    val paramsMap = extractParametersFromGroup(group, cl)
    if (gName.isNotBlank() && paramsMap.isNotEmpty()) {
      lookup.put(sourceFile, lineNumber, gName, bounds, paramsMap)
    }

    val children = (invokeGetter(group, "children") as? Collection<*>) ?: emptyList<Any>()
    for (child in children) {
      walkSlotGroup(child, cl, lookup, currentGroupName)
    }
  }

  internal fun extractParametersFromGroup(group: Any, cl: ClassLoader?): Map<String, String> {
    val rawParams = invokeGetter(group, "parameters") as? List<*> ?: return emptyMap()
    return extractParameterValues(rawParams, cl)
  }

  private fun extractParameterValues(rawParams: List<*>, cl: ClassLoader?): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (p in rawParams.filterNotNull()) {
      try {
        val rawName = (invokeGetter(p, "name") as? String)?.removePrefix("$") ?: continue
        if (
          rawName.isBlank() ||
            rawName == "modifier" ||
            rawName == "content" ||
            rawName.startsWith("$") ||
            rawName.startsWith("arg$") ||
            rawName.startsWith("f$") ||
            syntheticParamRegex.matches(rawName)
        ) {
          continue
        }
        val pValue = invokeGetter(p, "value") ?: continue
        val pInlineClass = invokeGetter(p, "inlineClass") as? String

        val formatted = formatParamValue(rawName, pValue, pInlineClass, cl)
        if (!formatted.isNullOrBlank()) {
          map[rawName] = formatted
        }
      } catch (e: Exception) {
        logger.log(Level.FINE, "Failed to format parameter value", e)
      }
    }
    return map
  }

  internal fun formatParamValue(paramName: String, value: Any, inlineClass: String?, cl: ClassLoader?): String? {
    return try {
      val valClass = value.javaClass.name
      if (
        valClass.contains("Function") ||
          valClass.contains("Lambda") ||
          valClass.contains("_layoutlib_._internal_") ||
          valClass.contains("RecomposeScope")
      ) {
        return null
      }

      // Compose Color
      if (
        inlineClass == "androidx.compose.ui.graphics.Color" ||
          (value is Long && (paramName.contains("color", ignoreCase = true) || paramName.contains("tint", ignoreCase = true)))
      ) {
        return formatColorValue(value, cl)
      }

      // Compose Dp
      if (
        inlineClass == "androidx.compose.ui.unit.Dp" ||
          (value is Float &&
            (paramName.contains("padding", ignoreCase = true) ||
              paramName.contains("size", ignoreCase = true) ||
              paramName.contains("elevation", ignoreCase = true) ||
              paramName.contains("width", ignoreCase = true) ||
              paramName.contains("height", ignoreCase = true)))
      ) {
        val f = (value as? Number)?.toFloat() ?: return null
        if (f.isNaN() || f.isInfinite()) return null
        return "${f}.dp"
      }

      // Compose TextUnit
      if (
        inlineClass == "androidx.compose.ui.unit.TextUnit" ||
          (value is Long &&
            (paramName.contains("fontSize", ignoreCase = true) ||
              paramName.contains("letterSpacing", ignoreCase = true) ||
              paramName.contains("lineHeight", ignoreCase = true)))
      ) {
        val vLong = (value as? Number)?.toLong() ?: return null
        return formatTextUnitValue(vLong, cl)
      }

      // TextAlign
      if (inlineClass == "androidx.compose.ui.text.style.TextAlign" || (value is Int && paramName.equals("textAlign", ignoreCase = true))) {
        return when ((value as? Number)?.toInt()) {
          1 -> "Left"
          2 -> "Right"
          3 -> "Center"
          4 -> "Justify"
          5 -> "Start"
          6 -> "End"
          else -> null
        }
      }

      // TextOverflow
      if (
        inlineClass == "androidx.compose.ui.text.style.TextOverflow" || (value is Int && paramName.equals("overflow", ignoreCase = true))
      ) {
        return when ((value as? Number)?.toInt()) {
          1 -> null // Clip is default
          2 -> "Ellipsis"
          3 -> "Visible"
          else -> null
        }
      }

      // FontWeight
      if (valClass.endsWith("FontWeight")) {
        val weight = invokeGetter(value, "weight") ?: value
        return "FontWeight($weight)"
      }

      // AnnotatedString
      if (valClass.endsWith("AnnotatedString")) {
        return invokeGetter(value, "text")?.toString() ?: value.toString()
      }

      val str = value.toString()
      if (
        str == "kotlin.Unit" ||
          str.contains("Transition animation values") ||
          str.contains("_layoutlib_._internal_") ||
          str.contains("Function") ||
          str.contains("Lambda") ||
          str.contains("RecomposeScope") ||
          (str.startsWith("TextStyle(") && str.contains("Unspecified")) ||
          DEFAULT_OBJECT_TO_STRING_REGEX.matches(str)
      ) {
        return null
      }

      if (paramName == "maxLines" && (value == Int.MAX_VALUE || str == "2147483647")) return null
      if (paramName == "minLines" && (value == 1 || str == "1")) return null
      if (paramName == "softWrap" && (value == true || str == "true")) return null
      if (paramName == "overflow" && (str == "1" || str.endsWith("Clip"))) return null

      str
    } catch (e: Exception) {
      logger.log(Level.FINE, "Failed to format parameter '$paramName'", e)
      null
    }
  }

  internal fun formatTextUnitValue(value: Long, cl: ClassLoader?): String? {
    if (cl != null) {
      try {
        val tuClass = Class.forName("androidx.compose.ui.unit.TextUnit", true, cl)
        val toStringMethod = tuClass.methods.firstOrNull { it.name.startsWith("toString-impl") && it.parameterCount == 1 }
        if (toStringMethod != null) {
          val str = toStringMethod.invoke(null, value).toString()
          if (str.contains("Unspecified") || str.contains("Invalid")) return null
          return str
        }
      } catch (_: Exception) {} catch (_: LinkageError) {}
    }
    val type = (value ushr 32).toInt()
    val rawFloat = Float.fromBits(value.toInt())
    if (type == 0 || rawFloat.isNaN() || value == 0L || value == FLOAT_NAN_BITS) {
      return null
    }
    return when (type) {
      1 -> "${rawFloat}.sp"
      2 -> "${rawFloat}.em"
      else -> "${rawFloat}.sp"
    }
  }
}
