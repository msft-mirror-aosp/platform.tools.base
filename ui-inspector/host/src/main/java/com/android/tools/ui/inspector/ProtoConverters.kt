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

package com.android.tools.ui.inspector

import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol

/** Holds pre-indexed parameters mapping and string table for efficient Compose node parsing. */
private class ComposeParameters(response: LayoutInspectorComposeProtocol.GetAllParametersResponse) {
  /** Maps Composable ID to its list of parameters. */
  val parametersMap: Map<Long, List<LayoutInspectorComposeProtocol.Parameter>> =
    response.parameterGroupsList.associate { group -> group.composableId to group.parameterList }

  /** String table for resolving parameter names and string values. */
  val parameterStringTable: Map<Int, String> = response.stringsList.associate { it.id to it.str }
}

/** Converts a protobuf [ViewInspectorProtocol.ViewNode] into a domain [UiNode.ViewNode]. */
internal fun convertViewNode(node: ViewInspectorProtocol.ViewNode, stringTable: Map<Int, String>): UiNode.ViewNode {
  val className = stringTable[node.className] ?: "Unknown"
  val bounds = UiNode.Bounds(x = node.bounds.x, y = node.bounds.y, width = node.bounds.width, height = node.bounds.height)
  val idResource = stringTable[node.idResource]
  val layoutResource = stringTable[node.layoutResource]
  val attributes =
    node.attributesList.map { attr ->
      val directSource = stringTable[attr.directSource]
      val styleChain = attr.styleChainList.map { stringTable[it] ?: "unknown" }
      UiNode.Attribute(
        name = stringTable[attr.name] ?: "unknown",
        value = if (attr.value == 0) "" else stringTable[attr.value] ?: "unknown",
        directSource = directSource,
        styleChain = styleChain,
      )
    }
  val children = node.childrenList.map { convertViewNode(it, stringTable) }.toMutableList<UiNode>()
  return UiNode.ViewNode(
    id = node.id,
    className = className,
    bounds = bounds,
    idResource = idResource,
    layoutResource = layoutResource,
    attributes = attributes,
    children = children,
  )
}

/**
 * Converts a protobuf [LayoutInspectorComposeProtocol.ComposableNode] into a domain [UiNode.ComposeNode].
 *
 * @param node the protobuf Composable node to convert.
 * @param stringTable string table containing all the text resources indexed by ID.
 * @param hostedViews a map of View ID to [UiNode.ViewNode] representing all Android views hosted within the entire Compose tree.
 * @param parameters optional Composable parameters.
 */
internal fun convertComposeNode(
  node: LayoutInspectorComposeProtocol.ComposableNode,
  stringTable: Map<Int, String>,
  hostedViews: Map<Long, UiNode.ViewNode>,
  parameters: LayoutInspectorComposeProtocol.GetAllParametersResponse? = null,
): UiNode.ComposeNode {
  val composeParameters = parameters?.let { ComposeParameters(it) }
  return doConvertComposeNode(node, stringTable, hostedViews, composeParameters)
}

/** Private recursive helper that maps the Composable nodes and propagates pre-indexed parameters. */
private fun doConvertComposeNode(
  node: LayoutInspectorComposeProtocol.ComposableNode,
  stringTable: Map<Int, String>,
  hostedViews: Map<Long, UiNode.ViewNode>,
  parameters: ComposeParameters? = null,
): UiNode.ComposeNode {
  val name = stringTable[node.name] ?: "Composable"
  val bounds =
    if (node.hasBounds()) {
      val layout = node.bounds.layout
      UiNode.Bounds(x = layout.x, y = layout.y, width = layout.w, height = layout.h)
    } else {
      UiNode.Bounds(x = 0, y = 0, width = 0, height = 0)
    }

  val nodeParams = parameters?.parametersMap?.get(node.id) ?: emptyList()
  val paramStringTable = parameters?.parameterStringTable ?: emptyMap()
  val mappedParameters = nodeParams.map { convertParameterToComposeParameter(it, paramStringTable) }

  val children = node.childrenList.map { doConvertComposeNode(it, stringTable, hostedViews, parameters) }.toMutableList<UiNode>()
  if (node.viewId != 0L) {
    hostedViews[node.viewId]?.let { hostedView -> children.add(hostedView) }
  }
  val sourceLocation =
    if (node.filename != 0) {
      val filename = stringTable[node.filename] ?: "Missing"
      UiNode.SourceLocation(filename, node.lineNumber)
    } else {
      null
    }
  return UiNode.ComposeNode(
    id = node.id,
    className = name,
    bounds = bounds,
    children = children,
    sourceLocation = sourceLocation,
    parameters = mappedParameters,
  )
}

/** Helper to map Composable proto parameters to framework-agnostic domain ComposeParameters. */
private fun convertParameterToComposeParameter(
  param: LayoutInspectorComposeProtocol.Parameter,
  stringTable: Map<Int, String>,
): UiNode.ComposeParameter {
  val name = stringTable[param.name] ?: "unknown"
  return if (param.elementsCount > 0) {
    val allAnonymous = param.elementsList.all { it.name == 0 }
    val elements = param.elementsList.map { convertParameterToComposeParameter(it, stringTable) }
    UiNode.ComposeParameter.Group(name, elements, allAnonymous)
  } else {
    UiNode.ComposeParameter.Single(name, getParameterValue(param, stringTable))
  }
}

private fun getParameterValue(
  param: LayoutInspectorComposeProtocol.Parameter,
  stringTable: Map<Int, String>,
): UiNode.ComposeParameter.Value {
  return when (param.type) {
    LayoutInspectorComposeProtocol.Parameter.Type.STRING,
    LayoutInspectorComposeProtocol.Parameter.Type.ITERABLE -> {
      val stringValue = if (param.int32Value != 0) stringTable[param.int32Value] ?: "" else ""
      UiNode.ComposeParameter.Value.StringVal(stringValue)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.BOOLEAN -> {
      UiNode.ComposeParameter.Value.BooleanVal(param.int32Value == 1)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.DOUBLE -> {
      UiNode.ComposeParameter.Value.NumberVal(param.doubleValue)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.FLOAT -> {
      UiNode.ComposeParameter.Value.NumberVal(param.floatValue)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_DP -> {
      UiNode.ComposeParameter.Value.DimensionVal(param.floatValue, UiNode.ComposeParameter.DimensionUnit.DP)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_SP -> {
      UiNode.ComposeParameter.Value.DimensionVal(param.floatValue, UiNode.ComposeParameter.DimensionUnit.SP)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_EM -> {
      UiNode.ComposeParameter.Value.DimensionVal(param.floatValue, UiNode.ComposeParameter.DimensionUnit.EM)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.INT32 -> {
      UiNode.ComposeParameter.Value.NumberVal(param.int32Value)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.INT64 -> {
      UiNode.ComposeParameter.Value.NumberVal(param.int64Value)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.COLOR -> {
      UiNode.ComposeParameter.Value.ColorVal(param.int32Value)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.RESOURCE -> {
      if (param.hasResourceValue()) {
        val res = param.resourceValue
        val namespace = stringTable[res.namespace]
        val type = stringTable[res.type]
        val resName = stringTable[res.name] ?: "unknown"
        UiNode.ComposeParameter.Value.ResourceVal(namespace, type, resName)
      } else {
        UiNode.ComposeParameter.Value.NullVal
      }
    }
    LayoutInspectorComposeProtocol.Parameter.Type.LAMBDA,
    LayoutInspectorComposeProtocol.Parameter.Type.FUNCTION_REFERENCE -> {
      if (param.hasLambdaValue()) {
        val lambda = param.lambdaValue
        val fileName = stringTable[lambda.fileName]
        UiNode.ComposeParameter.Value.LambdaVal(fileName = fileName, startLineNumber = lambda.startLineNumber)
      } else {
        UiNode.ComposeParameter.Value.LambdaVal(fileName = null, startLineNumber = null)
      }
    }
    else -> UiNode.ComposeParameter.Value.NullVal
  }
}
