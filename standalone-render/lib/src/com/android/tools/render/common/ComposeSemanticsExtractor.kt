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

import com.android.tools.render.common.ComponentTreeExtractor.findZeroArgMethod
import com.android.tools.render.common.ComponentTreeExtractor.invokeGetter
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/** Extracts semantics configurations and modifier metadata from Compose `LayoutNode`s and classic Android `View`s via reflection. */
internal object ComposeSemanticsExtractor {
  private val logger = Logger.getLogger(ComposeSemanticsExtractor::class.java.name)

  /**
   * Matches default JVM `Object.toString()` representations (`fully.qualified.ClassName@1a2b3c`) without matching `@` inside arbitrary user
   * text.
   */
  internal val DEFAULT_OBJECT_TO_STRING_REGEX = Regex("""^[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_$]+)+@[0-9a-fA-F]{6,8}$""")

  /**
   * Compose `Color` packs a 6-bit `ColorSpace` ID in bits 0..5 of its 64-bit `ULong` representation:
   * - `0` (`0x00`) is `ColorSpaces.Srgb`, with the 32-bit ARGB color stored in bits 32..63 (`0L` is `Color.Transparent`).
   * - `16` (`0x10`) is `ColorSpaces.Unspecified` (`Color.Unspecified` is `16UL`, or `0x1000000000L` in older shifted representations).
   */
  private const val COLOR_SPACE_ID_MASK = 0x3fL
  private const val COLOR_SPACE_SRGB_ID = 0L
  private const val COLOR_SPACE_UNSPECIFIED_ID = 16L
  private const val LEGACY_COLOR_UNSPECIFIED = 0x1000000000L

  private val semanticsMethodCache = ConcurrentHashMap<Class<*>, Method>()
  private val noMethodSentinel: Method = Any::class.java.getDeclaredMethod("toString")

  fun extractSemantics(layoutNode: Any?): Map<String, String>? {
    if (layoutNode == null) return null
    return try {
      val getConfig = findSemanticsConfigMethod(layoutNode.javaClass) ?: return null
      val config = getConfig.invoke(layoutNode) ?: return null
      val iterator = (config as? Iterable<*>)?.iterator() ?: return null

      val map = mutableMapOf<String, String>()
      val actions = mutableListOf<String>()

      while (iterator.hasNext()) {
        val entry = iterator.next() as? Map.Entry<*, *> ?: continue
        val key = entry.key ?: continue
        val value = entry.value ?: continue
        val keyName = (invokeGetter(key, "name") as? String) ?: key.toString()

        when (keyName) {
          "Text",
          "EditableText" -> {
            val textList =
              when (value) {
                is List<*> -> value.filterNotNull().map { invokeGetter(it, "text")?.toString() ?: it.toString() }
                else -> listOf(invokeGetter(value, "text")?.toString() ?: value.toString())
              }
            val combined = textList.filter { it.isNotBlank() }.joinToString("\n")
            if (combined.isNotBlank()) map["text"] = combined
          }
          "ContentDescription" -> {
            val cdList =
              when (value) {
                is List<*> -> value.filterNotNull().map { it.toString() }
                else -> listOf(value.toString())
              }
            val combined = cdList.filter { it.isNotBlank() }.joinToString(", ")
            if (combined.isNotBlank()) map["contentDescription"] = combined
          }
          "Role" -> {
            val roleStr = value.toString().removePrefix("Role.")
            if (roleStr.isNotBlank()) map["role"] = roleStr
          }
          "StateDescription" -> map["stateDescription"] = value.toString()
          "TestTag" -> map["testTag"] = value.toString()
          "ProgressBarRangeInfo" -> {
            val current = invokeGetter(value, "current")
            val range = invokeGetter(value, "range")
            val steps = invokeGetter(value, "steps")
            map["progress"] = "current=$current, range=$range, steps=$steps"
          }
          "ToggleableState" -> map["toggleableState"] = value.toString().removePrefix("ToggleableState.")
          "Selected" -> map["selected"] = value.toString()
          "Disabled" -> map["disabled"] = "true"
          "Heading" -> map["heading"] = "true"
          "Error" -> map["error"] = value.toString()
          "OnClick",
          "OnLongClick",
          "SetText",
          "ScrollBy",
          "ScrollToIndex",
          "Expand",
          "Collapse",
          "Dismiss" -> {
            actions.add(keyName)
          }
        }
      }
      if (actions.isNotEmpty()) {
        map["actions"] = actions.distinct().joinToString(", ")
      }
      map.ifEmpty { null }
    } catch (e: Exception) {
      logger.log(Level.FINE, "Failed to extract Compose semantics", e)
      null
    } catch (e: LinkageError) {
      logger.log(Level.FINE, "Linkage error extracting Compose semantics", e)
      null
    }
  }

  /**
   * Resolves the semantics configuration getter on `LayoutNode`.
   *
   * Compose 1.8+ exposes `getSemanticsConfiguration()` via `SemanticsInfo`, whereas Compose 1.5–1.7 exposes the internal mangled method
   * `getCollapsedSemantics$ui_release()`.
   */
  private fun findSemanticsConfigMethod(clazz: Class<*>): Method? {
    val cached =
      semanticsMethodCache.computeIfAbsent(clazz) { cls ->
        val found =
          (cls.methods.asSequence() + cls.declaredMethods.asSequence()).firstOrNull { method ->
            method.parameterCount == 0 &&
              (method.name == "getSemanticsConfiguration" ||
                method.name.startsWith("getSemanticsConfiguration$") ||
                method.name.startsWith("getCollapsedSemantics"))
          }
        found?.apply { isAccessible = true } ?: noMethodSentinel
      }
    return cached.takeIf { it !== noMethodSentinel }
  }

  fun extractModifiers(layoutNode: Any?, cl: ClassLoader?): Map<String, String>? {
    if (layoutNode == null) return null
    return try {
      val getModifierInfo = findZeroArgMethod(layoutNode.javaClass, "getModifierInfo") ?: return null
      val modifierInfos = (getModifierInfo.invoke(layoutNode) as? List<*>) ?: return null

      val map = mutableMapOf<String, String>()

      for (info in modifierInfos.filterNotNull()) {
        val mod = invokeGetter(info, "modifier") ?: continue
        val simpleName = mod.javaClass.simpleName

        if (simpleName.contains("Background")) {
          val color = invokeGetter(mod, "color")
          val brush = invokeGetter(mod, "brush")
          val shape = formatShape(invokeGetter(mod, "shape"))
          if (brush != null) {
            map["background"] = "brush=$brush${if (shape.isNotEmpty()) ", shape=$shape" else ""}"
          } else if (color != null) {
            val colorStr = formatColorValue(color, cl)
            if (colorStr != null) {
              map["background"] = "$colorStr${if (shape.isNotEmpty()) ", shape=$shape" else ""}"
            }
          }
        } else if (simpleName.contains("Padding")) {
          val start = formatDpValue(invokeGetter(mod, "start"))
          val top = formatDpValue(invokeGetter(mod, "top"))
          val end = formatDpValue(invokeGetter(mod, "end"))
          val bottom = formatDpValue(invokeGetter(mod, "bottom"))
          if (start != null && start == top && top == end && end == bottom) {
            map["padding"] = start
          } else {
            val parts =
              listOfNotNull(
                start?.let { "start=$it" },
                top?.let { "top=$it" },
                end?.let { "end=$it" },
                bottom?.let { "bottom=$it" },
              )
            if (parts.isNotEmpty()) map["padding"] = parts.joinToString(", ")
          }
        } else if (simpleName.contains("SizeElement")) {
          val minW = formatDpValue(invokeGetter(mod, "minWidth"))
          val minH = formatDpValue(invokeGetter(mod, "minHeight"))
          val maxW = formatDpValue(invokeGetter(mod, "maxWidth"))
          val maxH = formatDpValue(invokeGetter(mod, "maxHeight"))
          if (minW != null && minW == maxW && minH != null && minH == maxH && minW == minH) {
            map["size"] = minW
          } else {
            if (minW != null && minW == maxW) map["width"] = minW else if (minW != null && maxW != null) map["width"] = "$minW..$maxW"
            if (minH != null && minH == maxH) map["height"] = minH else if (minH != null && maxH != null) map["height"] = "$minH..$maxH"
          }
        } else if (simpleName.contains("FillElement")) {
          val dir = invokeGetter(mod, "direction")?.toString() ?: "Both"
          val frac = (invokeGetter(mod, "fraction") as? Number)?.toFloat() ?: 1.0f
          map["fill"] = "$dir ($frac)"
        } else if (simpleName.contains("WrapContent")) {
          val dir = invokeGetter(mod, "direction")?.toString() ?: "Both"
          map["wrapContent"] = dir
        } else if (simpleName.contains("Shadow")) {
          val elev = formatDpValue(invokeGetter(mod, "elevation") ?: invokeGetter(mod, "shadowElevation"))
          val shape = formatShape(invokeGetter(mod, "shape"))
          val clip = invokeGetter(mod, "clip")
          map["shadow"] =
            "elevation=${elev ?: "0.dp"}${if (shape.isNotEmpty()) ", shape=$shape" else ""}${if (clip != null) ", clip=$clip" else ""}"
        } else if (simpleName.contains("GraphicsLayer") && !simpleName.contains("Shadow")) {
          val alpha = (invokeGetter(mod, "alpha") as? Number)?.toFloat()
          val clip = invokeGetter(mod, "clip") as? Boolean
          if (alpha != null && alpha != 1.0f) map["alpha"] = alpha.toString()
          if (clip == true) map["clip"] = "true"
        } else if (simpleName.contains("Clickable")) {
          val enabled = invokeGetter(mod, "enabled") as? Boolean ?: true
          val role = invokeGetter(mod, "role")
          map["clickable"] = "enabled=$enabled${if (role != null) ", role=$role" else ""}"
        } else if (simpleName.contains("Scroll")) {
          map["scroll"] = "true"
        }

        // Also inspect LayoutWeightElement directly on the modifier if present.
        if (simpleName.contains("LayoutWeight")) {
          val weight = (invokeGetter(mod, "weight") as? Number)?.toFloat()
          if (weight != null && weight > 0f) {
            map["weight"] = weight.toString()
          }
        }

        // Inspect ModifierInfo.extra (parentData such as RowColumnParentData or BoxChildDataNode).
        // Match on property presence rather than class name substrings so RowColumnParentData.weight is captured.
        val extra = invokeGetter(info, "extra")
        if (extra != null) {
          val weight = (invokeGetter(extra, "weight") as? Number)?.toFloat()
          if (weight != null && weight > 0f) {
            map["weight"] = weight.toString()
          }
          val alignment = invokeGetter(extra, "alignment")
          if (alignment != null) {
            map["align"] = alignment.toString().removePrefix("Alignment.")
          }
        }
      }
      map.ifEmpty { null }
    } catch (e: Exception) {
      logger.log(Level.FINE, "Failed to extract Compose modifiers", e)
      null
    } catch (e: LinkageError) {
      logger.log(Level.FINE, "Linkage error extracting Compose modifiers", e)
      null
    }
  }

  fun extractViewSemantics(view: Any?): Map<String, String>? {
    if (view == null) return null
    return try {
      val map = mutableMapOf<String, String>()

      val text = invokeGetter(view, "text")?.toString()
      if (!text.isNullOrBlank()) map["text"] = text

      val cd = invokeGetter(view, "contentDescription")?.toString()
      if (!cd.isNullOrBlank()) map["contentDescription"] = cd

      val id = invokeGetter(view, "id") as? Int
      if (id != null && id != -1) {
        val res = invokeGetter(view, "resources")
        val resName =
          try {
            res?.javaClass?.methods?.firstOrNull { it.name == "getResourceEntryName" && it.parameterCount == 1 }?.invoke(res, id) as? String
          } catch (_: Exception) {
            null
          }
        if (!resName.isNullOrBlank()) map["id"] = resName
      }

      val vis = invokeGetter(view, "visibility") as? Int
      if (vis != null && vis != 0) {
        map["visibility"] =
          when (vis) {
            4 -> "INVISIBLE"
            8 -> "GONE"
            else -> vis.toString()
          }
      }

      val clickable = invokeGetter(view, "clickable") as? Boolean
      if (clickable == true) map["clickable"] = "true"

      val enabled = invokeGetter(view, "enabled") as? Boolean
      if (enabled == false) map["enabled"] = "false"

      val alpha = (invokeGetter(view, "alpha") as? Number)?.toFloat()
      if (alpha != null && alpha != 1.0f) map["alpha"] = alpha.toString()

      map.ifEmpty { null }
    } catch (e: Exception) {
      logger.log(Level.FINE, "Failed to extract View semantics", e)
      null
    }
  }

  internal fun formatColorValue(colorObj: Any, cl: ClassLoader?): String? {
    val value = (colorObj as? Number)?.toLong() ?: (invokeGetter(colorObj, "value") as? Number)?.toLong() ?: return colorObj.toString()

    // Check for Color.Unspecified (color space ID 16 in bits 0..5, or legacy 0x1000000000L sentinel).
    // Note that 0L has color space ID 0 (sRGB) and ARGB 0x00000000, which is Color.Transparent (#00000000), not Unspecified.
    if (value == LEGACY_COLOR_UNSPECIFIED || (value and COLOR_SPACE_ID_MASK) == COLOR_SPACE_UNSPECIFIED_ID) {
      return null
    }

    if (cl != null) {
      try {
        val colorKtClass = Class.forName("androidx.compose.ui.graphics.ColorKt", true, cl)
        val isSpecifiedMethod = colorKtClass.methods.firstOrNull { it.name.startsWith("isSpecified") && it.parameterCount == 1 }
        if (isSpecifiedMethod != null) {
          val isSpecified = isSpecifiedMethod.invoke(null, value) as? Boolean
          if (isSpecified == false) return null
        }
        val toArgbMethod = colorKtClass.methods.firstOrNull { it.name.startsWith("toArgb") && it.parameterCount == 1 }
        if (toArgbMethod != null) {
          val argb = (toArgbMethod.invoke(null, value) as? Number)?.toInt()
          if (argb != null) return "#%08X".format(argb)
        }
      } catch (e: Exception) {
        val msg = e.cause?.message ?: e.message ?: ""
        if (msg.contains("unspecified", ignoreCase = true)) {
          return null
        }
      } catch (_: LinkageError) {}
    }

    // Plain 32-bit ARGB integer stored in the low 32 bits (used in lightweight test fixtures or legacy Color representations).
    if ((value ushr 32) == 0L) {
      return "#%08X".format((value and 0xFFFFFFFFL).toInt())
    }

    // Standard Compose sRGB Color: 32-bit ARGB is stored in the upper 32 bits and bits 0..5 are 0 (sRGB color space ID).
    if ((value and COLOR_SPACE_ID_MASK) == COLOR_SPACE_SRGB_ID) {
      val argb = ((value ushr 32) and 0xFFFFFFFFL).toInt()
      return "#%08X".format(argb)
    }
    return "#%016X".format(value)
  }

  internal fun formatShape(shape: Any?): String {
    if (shape == null) return ""
    val name = shape.javaClass.simpleName
    val str = shape.toString()
    if (name.contains("RectangleShape") || str.contains("RectangleShape")) return ""
    if (!DEFAULT_OBJECT_TO_STRING_REGEX.matches(str) && str.isNotBlank()) return str
    return name
  }

  internal fun formatDpValue(value: Any?): String? {
    if (value == null) return null
    if (value is Number) {
      val f = value.toFloat()
      if (f.isNaN() || f.isInfinite()) return null
      return "${f}.dp"
    }
    val str = value.toString()
    if (str.contains("NaN") || str.contains("Unspecified")) return null
    return if (str.endsWith(".dp")) str else "${str}.dp"
  }
}
