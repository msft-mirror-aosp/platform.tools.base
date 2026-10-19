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

import com.android.ide.common.rendering.api.ViewInfo
import com.android.tools.configurations.Configuration
import com.android.tools.render.common.ComposeParameterExtractor.ParamLookup
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.compose.ComposeViewInfo
import com.android.tools.rendering.compose.PxBounds
import com.android.tools.rendering.compose.SourceLocation
import com.android.tools.rendering.compose.findComposeViewAdapter as findComposeAdapterInViewObject
import com.android.tools.rendering.compose.parseViewInfo
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Extracts preview component hierarchies from rendered Layoutlib [RenderResult]s.
 *
 * For Compose previews, it discovers `ComposeViewAdapter` via reflection, extracts `androidx.compose.ui.tooling.ViewInfo` nodes with their
 * bounds, source locations, semantics, parameters, and modifiers, and prunes non-visual and internal implementation-detail wrapper nodes
 * while preserving user composables and layout containers. For classic Android views or when Compose metadata is absent, it falls back to
 * converting Layoutlib [ViewInfo] hierarchies.
 */
object ComponentTreeExtractor {
  private val logger = Logger.getLogger(ComponentTreeExtractor::class.java.name)

  fun extract(
    previewId: String,
    methodFQN: String,
    displayName: String?,
    config: Configuration,
    renderResult: RenderResult? = null,
    viewInfos: List<ComposeViewInfo> = emptyList(),
  ): PreviewComponentTree {
    val rootViews = renderResult?.rootViews.orEmpty()
    val composeAdapter = findComposeViewAdapter(rootViews)
    val parsedComposeViewInfos = viewInfos.ifEmpty { rootViews.flatMap { parseViewInfo(it) } }
    val composeNodes =
      when {
        composeAdapter != null -> extractFromComposeAdapter(composeAdapter, parsedComposeViewInfos)
        parsedComposeViewInfos.isNotEmpty() -> extractFromComposeViewInfos(parsedComposeViewInfos)
        else -> emptyList()
      }

    val components =
      if (composeNodes.isNotEmpty()) {
        composeNodes
      } else {
        if (composeAdapter != null || parsedComposeViewInfos.isNotEmpty()) {
          logger.log(
            Level.FINE,
            "Compose adapter/viewInfos present for $methodFQN ($previewId) but no meaningful Compose nodes survived pruning; " +
              "falling back to Layoutlib ViewInfo hierarchy.",
          )
        }
        fallbackToLayoutlibViewInfos(rootViews)
      }

    val rootWidth = rootViews.firstOrNull()?.let { it.right - it.left } ?: 0
    val rootHeight = rootViews.firstOrNull()?.let { it.bottom - it.top } ?: 0
    val effectiveWidth = if (rootWidth > 0) rootWidth else components.maxOfOrNull { it.bounds.right } ?: 0
    val effectiveHeight = if (rootHeight > 0) rootHeight else components.maxOfOrNull { it.bounds.bottom } ?: 0

    return PreviewComponentTree(
      previewId = previewId,
      methodFQN = methodFQN,
      displayName = displayName,
      width = effectiveWidth,
      height = effectiveHeight,
      densityDpi = config.density?.dpiValue,
      fontScale = config.fontScale,
      uiMode = config.uiModeFlagValue,
      deviceId = config.device?.id,
      components = components,
    )
  }

  private fun findComposeViewAdapter(rootViews: List<ViewInfo>): Any? {
    val queue = ArrayDeque<ViewInfo>(rootViews)
    while (queue.isNotEmpty()) {
      val curr = queue.removeFirst()
      val found =
        curr.viewObject?.let {
          try {
            findComposeAdapterInViewObject(it)
          } catch (e: Exception) {
            logger.log(Level.FINE, "Failed to inspect viewObject for ComposeViewAdapter", e)
            null
          }
        }
      if (found != null) return found
      queue.addAll(curr.children)
    }
    return null
  }

  /**
   * Internal Compose runtime / tooling wrapper groups that do not represent user-facing UI components and should be unwrapped so their
   * children are promoted directly.
   */
  private val INTERNAL_COMPOSE_NODE_NAMES =
    setOf(
      "ReusableComposeNode",
      "Layout",
      "SubcomposeLayout",
      "CompositionLocalProvider",
      "LookaheadScope",
      "Inspectable",
      "WrapPreview",
      "ProvideAndroidCompositionLocals",
      "ProvideCommonCompositionLocals",
    )

  private class RawComposeNode(
    val name: String,
    val bounds: ComponentBounds,
    val sourceLocation: ComponentSourceLocation?,
    var layoutNode: Any?,
    val rawChildren: List<RawComposeNode>,
    var semantics: Map<String, String>? = null,
    var parameters: Map<String, String>? = null,
    var modifiers: Map<String, String>? = null,
    val isInferredFromLayoutNode: Boolean = false,
  )

  internal fun extractFromComposeAdapter(
    adapter: Any,
    parsedViewInfos: List<ComposeViewInfo> = emptyList(),
  ): List<ComponentTreeNode> {
    val cl = adapter.javaClass.classLoader
    val paramLookup = ComposeParameterExtractor.extractParametersFromSlotTable(adapter, cl)

    val getViewInfos =
      adapter.javaClass.declaredMethods.firstOrNull { it.name.contains("getViewInfos") && it.parameterCount == 0 }
        ?: adapter.javaClass.methods.firstOrNull { it.name.contains("getViewInfos") && it.parameterCount == 0 }
    val rawList =
      if (getViewInfos != null) {
        try {
          getViewInfos.isAccessible = true
          (getViewInfos.invoke(adapter) as? List<*>)?.filterNotNull().orEmpty()
        } catch (e: Exception) {
          logger.log(Level.FINE, "Failed to invoke getViewInfos on ComposeViewAdapter", e)
          emptyList()
        }
      } else {
        emptyList()
      }

    val rawNodes =
      if (rawList.isNotEmpty()) {
        rawList.mapIndexed { index, rawNode ->
          val parsedInfo = parsedViewInfos.getOrNull(index).takeIf { rawList.size == parsedViewInfos.size }
          parseRawComposeViewInfo(rawNode, paramLookup, cl, parsedInfo)
        }
      } else {
        parsedViewInfos.map { parseRawComposeViewInfo(it, paramLookup, cl, it) }
      }
    return rawNodes.flatMap { pruneRawComposeNode(it, parent = null) }
  }

  internal fun extractFromComposeViewInfos(
    viewInfos: List<ComposeViewInfo>,
    paramLookup: ParamLookup = ParamLookup(),
  ): List<ComponentTreeNode> {
    val rawNodes = viewInfos.map { parseRawComposeViewInfo(it, paramLookup, null, it) }
    return rawNodes.flatMap { pruneRawComposeNode(it, parent = null) }
  }

  private fun parseRawComposeViewInfo(
    node: Any,
    paramLookup: ParamLookup,
    cl: ClassLoader?,
    parsedInfo: ComposeViewInfo? = node as? ComposeViewInfo,
  ): RawComposeNode {
    val bounds =
      if (parsedInfo != null) {
        ComponentBounds(parsedInfo.bounds.left, parsedInfo.bounds.top, parsedInfo.bounds.right, parsedInfo.bounds.bottom)
      } else {
        val rawBounds = invokeGetter(node, "bounds")
        val left = extractBoundCoord(rawBounds, "left")
        val top = extractBoundCoord(rawBounds, "top")
        val right = extractBoundCoord(rawBounds, "right")
        val bottom = extractBoundCoord(rawBounds, "bottom")
        ComponentBounds(left, top, right, bottom)
      }
    val sourceLocation = extractSourceLocation(node, parsedInfo?.sourceLocation)
    val directLayoutNode = invokeGetter(node, "layoutInfo")
    val parsedName = parsedInfo?.name?.takeIf { it.isNotEmpty() }
    val explicitName =
      parsedName
        ?: (invokeGetter(node, "name") as? String).orEmpty().ifEmpty {
          paramLookup.findGroupName(sourceLocation?.fileName, sourceLocation?.lineNumber, bounds, directLayoutNode).orEmpty()
        }

    val parsedChildren =
      if (node is ComposeViewInfo) {
        node.children.map { parseRawComposeViewInfo(it, paramLookup, cl, it) }
      } else {
        val rawChildrenList = (invokeGetter(node, "children") as? List<*>)?.filterNotNull().orEmpty()
        val parsedChildrenInfos = parsedInfo?.children.orEmpty()
        rawChildrenList.mapIndexed { index, child ->
          val childParsedInfo = parsedChildrenInfos.getOrNull(index).takeIf { rawChildrenList.size == parsedChildrenInfos.size }
          parseRawComposeViewInfo(child, paramLookup, cl, childParsedInfo)
        }
      }

    var layoutNode = directLayoutNode ?: if (!explicitName.endsWith("Theme")) findInternalLayoutNode(node) else null
    if (layoutNode == null && !explicitName.endsWith("Theme")) {
      for (c in parsedChildren) {
        if ((c.name.isBlank() || c.name in INTERNAL_COMPOSE_NODE_NAMES) && c.bounds == bounds && c.layoutNode != null) {
          layoutNode = c.layoutNode
          break
        }
      }
    }

    var semantics = ComposeSemanticsExtractor.extractSemantics(layoutNode)
    var modifiers = ComposeSemanticsExtractor.extractModifiers(layoutNode, cl)
    val name =
      if (explicitName.isNotBlank()) {
        explicitName
      } else if (directLayoutNode != null) {
        inferNameFromLayoutNode(directLayoutNode, semantics, modifiers)
      } else {
        ""
      }
    val isInferredFromLayoutNode = explicitName.isBlank() && name.isNotBlank()
    var parameters = paramLookup.findParameters(sourceLocation?.fileName, sourceLocation?.lineNumber, name, bounds)

    // Hoist from direct internal primitive children (e.g. ReusableComposeNode / Layout) only when they share this node's bounds
    // and this node is not a Theme wrapper.
    if (!name.endsWith("Theme")) {
      for (c in parsedChildren) {
        if ((c.name.isBlank() || c.name in INTERNAL_COMPOSE_NODE_NAMES) && c.bounds == bounds) {
          if (semantics == null && c.semantics != null) semantics = c.semantics
          if (modifiers == null && c.modifiers != null) modifiers = c.modifiers
          if (parameters == null && c.parameters != null && areCompatibleParamOwners(name, c.name)) {
            parameters = c.parameters
          }
        }
      }
    }

    return RawComposeNode(
      name = name,
      bounds = bounds,
      sourceLocation = sourceLocation,
      layoutNode = layoutNode,
      rawChildren = parsedChildren,
      semantics = semantics,
      parameters = parameters,
      modifiers = modifiers,
      isInferredFromLayoutNode = isInferredFromLayoutNode,
    )
  }

  /**
   * Infers a component name for a `NodeGroup`-backed `ViewInfo` when Compose 1.5–1.7 `ViewInfo` or `sourceInformation=false` omits the
   * call-group name, using the `LayoutNode`'s semantics role/properties and `MeasurePolicy`.
   */
  private fun inferNameFromLayoutNode(
    layoutNode: Any,
    semantics: Map<String, String>?,
    modifiers: Map<String, String>?,
  ): String {
    when (semantics?.get("role")) {
      "Button" -> return "Button"
      "Checkbox" -> return "Checkbox"
      "Switch" -> return "Switch"
      "RadioButton" -> return "RadioButton"
      "Tab" -> return "Tab"
      "Image" -> return "Image"
    }
    if (semantics?.get("text") != null) return "Text"
    if (semantics?.get("progress") != null) return "ProgressIndicator"
    if (semantics?.get("contentDescription") != null) return "Image"

    val measurePolicy = invokeGetter(layoutNode, "measurePolicy")
    val policyDescriptor = "${measurePolicy?.javaClass?.name.orEmpty()} ${measurePolicy?.toString().orEmpty()}"
    return when {
      policyDescriptor.contains("Row", ignoreCase = false) -> "Row"
      policyDescriptor.contains("Column", ignoreCase = false) -> "Column"
      policyDescriptor.contains("Spacer", ignoreCase = false) -> "Spacer"
      policyDescriptor.contains("Box", ignoreCase = false) -> "Box"
      !semantics.isNullOrEmpty() || !modifiers.isNullOrEmpty() -> "Box"
      else -> ""
    }
  }

  /**
   * Returns true only when [parentName] and [childName] represent the same logical composable (such as overloaded functions delegating to
   * one another or `Text` delegating to `BasicText`), preventing unrelated composables like `IconButton` and `Button` from merging
   * parameters.
   */
  private fun areCompatibleParamOwners(parentName: String, childName: String): Boolean {
    if (parentName.isBlank() || childName.isBlank()) return false
    if (parentName == childName) return true
    if (parentName == "Text" && childName == "BasicText") return true
    return false
  }

  private fun findInternalLayoutNode(node: Any): Any? {
    val direct = invokeGetter(node, "layoutInfo")
    if (direct != null) return direct
    val rawChildren = (invokeGetter(node, "children") as? List<*>) ?: return null
    for (child in rawChildren.filterNotNull()) {
      val childName = (invokeGetter(child, "name") as? String).orEmpty()
      if (childName.isBlank() || childName in INTERNAL_COMPOSE_NODE_NAMES) {
        val found = findInternalLayoutNode(child)
        if (found != null) return found
      }
    }
    return null
  }

  internal fun isMeaningfulGroupName(name: String): Boolean {
    if (name.isBlank() || name in INTERNAL_COMPOSE_NODE_NAMES) return false
    if (name.startsWith("<get-") || name.startsWith("remember") || name.startsWith("<anonymous")) return false
    return true
  }

  /**
   * Returns true if a Compose node represents a meaningful UI component with positive dimensions.
   *
   * Note that a missing [ComponentSourceLocation] (for example, when compiled with `sourceInformation=false`) does not disqualify a named
   * node with positive bounds.
   */
  private fun isMeaningfulComposeNode(
    name: String,
    bounds: ComponentBounds,
  ): Boolean {
    if (bounds.width <= 0 || bounds.height <= 0) return false
    return isMeaningfulGroupName(name)
  }

  /**
   * Bottom-up pruning of [RawComposeNode] hierarchies:
   * 1. Non-meaningful nodes (zero-bounds, nameless, `remember`, `<get-...>`, and [INTERNAL_COMPOSE_NODE_NAMES]) are unwrapped. Their direct
   *    `layoutNode`/`semantics`/`modifiers` are only hoisted into `parent` when `parent` has identical bounds and is not a `*Theme`
   *    composable.
   * 2. Duplicate-bounds nodes (`node.bounds == parent.bounds`) with at most one child are collapsed only when they are:
   *     - Same-name overloads (`node.name == parent.name`),
   *     - `BasicText` inside `Text`,
   *     - Childless, metadata-free internal leaf placeholders (such as an empty `Box` inside `Icon` or an internal 3P image wrapper with no
   *       children or metadata), or
   *     - Intermediate `*Theme` / `*Theme.kt` implementation wrappers directly beneath an outer `*Theme` node (merging any metadata
   *       downward into the surviving child rather than upward into the theme). User composables and layout containers across different
   *       `.kt` files are always preserved.
   */
  private fun pruneRawComposeNode(
    node: RawComposeNode,
    parent: RawComposeNode?,
  ): List<ComponentTreeNode> {
    if (!isMeaningfulComposeNode(node.name, node.bounds)) {
      if (parent != null && parent.bounds == node.bounds && !parent.name.endsWith("Theme")) {
        parent.layoutNode = parent.layoutNode ?: node.layoutNode
        parent.semantics = mergeMaps(parent.semantics, node.semantics)
        parent.modifiers = mergeMaps(parent.modifiers, node.modifiers)
        if (areCompatibleParamOwners(parent.name, node.name)) {
          parent.parameters = mergeMaps(parent.parameters, node.parameters)
        }
      }
      return node.rawChildren.flatMap { pruneRawComposeNode(it, parent) }
    }

    val prunedChildren = node.rawChildren.flatMap { pruneRawComposeNode(it, node) }

    if (parent != null && node.bounds == parent.bounds && prunedChildren.size <= 1) {
      val isSameNameOverload = node.name == parent.name
      val isBasicTextUnderText = parent.name == "Text" && node.name == "BasicText"
      val isBarrenInferredBoxWrapper =
        node.isInferredFromLayoutNode &&
          node.name == "Box" &&
          node.semantics.isNullOrEmpty() &&
          node.parameters.isNullOrEmpty() &&
          node.modifiers.isNullOrEmpty()
      val isBarrenLeafPlaceholder =
        prunedChildren.isEmpty() &&
          node.semantics.isNullOrEmpty() &&
          node.parameters.isNullOrEmpty() &&
          node.modifiers.isNullOrEmpty() &&
          (node.name == "Box" || (node.sourceLocation?.fileName != null && node.sourceLocation.fileName != parent.sourceLocation?.fileName))
      val isIntermediateThemeWrapper =
        parent.name.endsWith("Theme") &&
          prunedChildren.isNotEmpty() &&
          (node.name.endsWith("Theme") ||
            (node.sourceLocation?.fileName?.endsWith("Theme.kt") == true &&
              node.modifiers.isNullOrEmpty() &&
              node.semantics.isNullOrEmpty()))

      if (isSameNameOverload || isBasicTextUnderText || isBarrenInferredBoxWrapper || isBarrenLeafPlaceholder) {
        if (!parent.name.endsWith("Theme")) {
          parent.layoutNode = parent.layoutNode ?: node.layoutNode
          parent.semantics = mergeMaps(parent.semantics, node.semantics)
          parent.modifiers = mergeMaps(parent.modifiers, node.modifiers)
          if (areCompatibleParamOwners(parent.name, node.name)) {
            parent.parameters = mergeMaps(parent.parameters, node.parameters)
          }
        }
        return prunedChildren
      }

      if (isIntermediateThemeWrapper) {
        // Merge any metadata from the collapsed theme wrapper downward into the surviving child rather than upward into the parent theme.
        return prunedChildren.map { child ->
          child.copy(
            semantics = mergeMaps(child.semantics, node.semantics),
            modifiers = mergeMaps(child.modifiers, node.modifiers),
          )
        }
      }
    }

    val componentNode =
      ComponentTreeNode(
        name = node.name,
        bounds = node.bounds,
        sourceLocation = node.sourceLocation,
        children = prunedChildren.ifEmpty { null },
        semantics = node.semantics?.ifEmpty { null },
        parameters = node.parameters?.ifEmpty { null },
        modifiers = node.modifiers?.ifEmpty { null },
      )
    return listOf(componentNode)
  }

  internal fun parseComposeViewInfo(node: Any): ComponentTreeNode = parseRawComposeViewInfo(node, ParamLookup(), null).toComponentTreeNode()

  private fun RawComposeNode.toComponentTreeNode(): ComponentTreeNode =
    ComponentTreeNode(
      name = name,
      bounds = bounds,
      sourceLocation = sourceLocation,
      children = rawChildren.map { it.toComponentTreeNode() }.ifEmpty { null },
      semantics = semantics,
      parameters = parameters,
      modifiers = modifiers,
    )

  internal fun mergeMaps(first: Map<String, String>?, second: Map<String, String>?): Map<String, String>? {
    if (first == null) return second
    if (second == null) return first
    val merged = first.toMutableMap()
    for ((k, v) in second) {
      if (!merged.containsKey(k)) {
        merged[k] = v
      }
    }
    return merged.ifEmpty { null }
  }

  internal fun extractBoundCoord(boundsObj: Any?, coordName: String): Int {
    if (boundsObj == null) return 0
    if (boundsObj is PxBounds) {
      return when (coordName) {
        "left" -> boundsObj.left
        "top" -> boundsObj.top
        "right" -> boundsObj.right
        "bottom" -> boundsObj.bottom
        else -> 0
      }
    }
    val value = invokeGetter(boundsObj, coordName) ?: return 0
    return when (value) {
      is Number -> value.toInt()
      else -> {
        val innerVal = invokeGetter(value, "value")
        (innerVal as? Number)?.toInt() ?: 0
      }
    }
  }

  private fun extractSourceLocation(node: Any, parsedLocation: SourceLocation? = null): ComponentSourceLocation? {
    if (node is ComposeViewInfo) {
      val loc = node.sourceLocation
      return if (loc.fileName.isNotBlank() && loc.lineNumber >= 0) {
        ComponentSourceLocation(loc.fileName, loc.lineNumber, loc.packageHash.takeIf { it != -1 })
      } else {
        null
      }
    }
    val baseFileName = parsedLocation?.fileName?.ifEmpty { null } ?: (invokeGetter(node, "fileName") as? String).orEmpty()
    val baseLineNumber = parsedLocation?.lineNumber?.takeIf { it != -1 } ?: (invokeGetter(node, "lineNumber") as? Number)?.toInt() ?: -1
    val basePackageHash = parsedLocation?.packageHash?.takeIf { it != -1 }
    val locationObj = invokeGetter(node, "location") ?: invokeGetter(node, "sourceLocation")
    val (effFile, effLine, effPkgHash) =
      when (locationObj) {
        is SourceLocation -> {
          val f = locationObj.fileName.ifEmpty { baseFileName }
          val l = if (locationObj.lineNumber != -1) locationObj.lineNumber else baseLineNumber
          val p = locationObj.packageHash.takeIf { it != -1 } ?: basePackageHash
          Triple(f, l, p)
        }
        null -> Triple(baseFileName, baseLineNumber, basePackageHash)
        else -> {
          val locFile = (invokeGetter(locationObj, "sourceFile") as? String) ?: (invokeGetter(locationObj, "fileName") as? String).orEmpty()
          val locLine = (invokeGetter(locationObj, "lineNumber") as? Number)?.toInt() ?: -1
          val locPkgHash = (invokeGetter(locationObj, "packageHash") as? Number)?.toInt()?.takeIf { it != -1 }
          val f = locFile.ifEmpty { baseFileName }
          val l = if (locLine != -1) locLine else baseLineNumber
          val p = locPkgHash ?: basePackageHash
          Triple(f, l, p)
        }
      }
    return if (effFile.isNotBlank() && effLine >= 0) {
      ComponentSourceLocation(effFile, effLine, effPkgHash)
    } else {
      null
    }
  }

  internal fun fallbackToLayoutlibViewInfos(rootViews: List<ViewInfo>): List<ComponentTreeNode> {
    return rootViews.map { parseLayoutlibViewInfo(it, parentX = 0, parentY = 0) }
  }

  private fun parseLayoutlibViewInfo(info: ViewInfo, parentX: Int, parentY: Int): ComponentTreeNode {
    val absLeft = parentX + info.left
    val absTop = parentY + info.top
    val absRight = parentX + info.right
    val absBottom = parentY + info.bottom
    val bounds = ComponentBounds(absLeft, absTop, absRight, absBottom)
    val children = info.children.map { parseLayoutlibViewInfo(it, absLeft, absTop) }
    val name = info.className ?: "android.view.View"

    val semantics = ComposeSemanticsExtractor.extractViewSemantics(info.viewObject)

    return ComponentTreeNode(
      name = name,
      bounds = bounds,
      sourceLocation = null,
      children = children.ifEmpty { null },
      semantics = semantics,
    )
  }

  // Cached reflection lookup tables to avoid repeated Class.getMethods() array allocations per node property.
  private val getterMethodCache = ConcurrentHashMap<Pair<Class<*>, String>, Method>()
  private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Field>()
  private val noMethodSentinel: Method = Any::class.java.getDeclaredMethod("toString")
  private val noFieldSentinel: Field = ComponentBounds::class.java.getDeclaredField("left")

  internal fun findZeroArgMethod(clazz: Class<*>, methodName: String): Method? {
    val cached =
      getterMethodCache.computeIfAbsent(clazz to methodName) { (cls, name) ->
        val found =
          cls.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: cls.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
        found?.apply { isAccessible = true } ?: noMethodSentinel
      }
    return cached.takeIf { it !== noMethodSentinel }
  }

  internal fun findField(clazz: Class<*>, fieldName: String): Field? {
    val cached =
      fieldCache.computeIfAbsent(clazz to fieldName) { (cls, name) ->
        val found = cls.declaredFields.firstOrNull { it.name == name } ?: cls.fields.firstOrNull { it.name == name }
        found?.apply { isAccessible = true } ?: noFieldSentinel
      }
    return cached.takeIf { it !== noFieldSentinel }
  }

  internal fun invokeGetter(obj: Any, propertyName: String): Any? {
    val clazz = obj.javaClass
    val method =
      getterMethodCache.computeIfAbsent(clazz to "prop:$propertyName") { (cls, _) ->
        val capitalized = propertyName.replaceFirstChar { it.uppercaseChar() }
        val targetNames = setOf("get$capitalized", propertyName, "is$capitalized")
        val found =
          cls.methods.firstOrNull { it.name in targetNames && it.parameterCount == 0 }
            ?: cls.declaredMethods.firstOrNull { it.name in targetNames && it.parameterCount == 0 }
        found?.apply { isAccessible = true } ?: noMethodSentinel
      }
    if (method !== noMethodSentinel) {
      try {
        val result = method.invoke(obj)
        if (result != null) return result
      } catch (_: Exception) {}
    }
    val field = findField(clazz, propertyName) ?: return null
    return try {
      field.get(obj)
    } catch (_: Exception) {
      null
    }
  }
}
