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

package com.android.tools.ui.inspector.model

/** Base class representing any node in the unified layout tree. */
sealed class UiNode {
  abstract val id: Long
  abstract val className: String
  abstract val bounds: Bounds
  abstract val children: List<UiNode>

  /**
   * Where the node appears on screen: the straight (non-tilted) rectangle enclosing the node as rendered, in screen coordinates. For a node
   * without a visual transform this is simply its position and size.
   */
  data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

  /** Represents a strongly-typed value for a [ViewNode] attribute. */
  sealed class AttributeValue {
    data class StringVal(val value: String) : AttributeValue()

    data class BooleanVal(val value: Boolean) : AttributeValue()

    data class NumberVal(val value: Number) : AttributeValue()

    data class ColorVal(val colorInt: Int) : AttributeValue()

    data class DimensionVal(val value: Float, val dp: Float?, val sp: Float?) : AttributeValue()

    object NullVal : AttributeValue()
  }

  data class Attribute(val name: String, val value: AttributeValue, val directSource: String?, val styleChain: List<String>)

  /** Represents the location in the source code where a layout node is defined. */
  data class SourceLocation(val filename: String, val lineNumber: Int)

  /** Represents a standard Android View node. */
  data class ViewNode(
    override val id: Long,
    override val className: String,
    override val bounds: Bounds,
    override val children: List<UiNode>,
    val idResource: String?,
    val layoutResource: String?,
    val attributes: List<Attribute>,
  ) : UiNode()

  /** Represents a Jetpack Compose Composable parameter. */
  sealed class ComposeParameter {
    abstract val name: String

    /**
     * The value of the parameter itself, or null when it has none, which only happens for a [Group]. A parameter whose value is null has
     * [Value.NullVal] instead.
     */
    abstract val value: Value?

    /**
     * True when the inspector holds data for this parameter that it did not send: it only sends the first few elements of each collection,
     * and none of the elements of collections and objects nested too deeply. Omitted null elements alone don't make a parameter truncated.
     */
    abstract val isTruncated: Boolean

    /** A leaf parameter representing a strongly-typed value. */
    data class Single(override val name: String, override val value: Value, override val isTruncated: Boolean = false) : ComposeParameter()

    /**
     * A nested group of parameters representing collections or objects.
     *
     * @param elements The parameters the group holds, as sent by the inspector: the elements of a collection, or the properties of an
     *   object, e.g. "x" and "y" for an Offset. A truncated group holds only some of them, or none.
     * @param isCollection True for lists, sets, maps, arrays and other iterables, even when none of their elements was sent. Their elements
     *   are named by index ("[0]") or, for map entries, by key ("[key]"). Null elements, and map entries with a null value, are omitted.
     * @param value The value of the group as a whole, as reported by the inspector, or null when it has none. For an object it is usually
     *   its class name (e.g. "Offset"). For a collection it is a summary of its kind and, when known, its total number of elements
     *   including omitted ones, e.g. "List[7]", "IntArray[8]" or "Map[2]", but just "Iterable" or "Sequence".
     */
    data class Group(
      override val name: String,
      val elements: List<ComposeParameter>,
      val isCollection: Boolean,
      override val value: Value? = null,
      override val isTruncated: Boolean = false,
    ) : ComposeParameter()

    /** Domain values that preserve original data and type information. */
    sealed class Value {
      data class StringVal(val value: String) : Value()

      data class BooleanVal(val value: Boolean) : Value()

      data class NumberVal(val value: Number) : Value()

      data class DimensionVal(val value: Float, val unit: DimensionUnit) : Value()

      data class ColorVal(val colorInt: Int) : Value()

      data class ResourceVal(val namespace: String?, val type: String?, val name: String) : Value()

      data class LambdaVal(val fileName: String?, val startLineNumber: Int?) : Value()

      object NullVal : Value()
    }

    enum class DimensionUnit {
      DP,
      SP,
      EM,
    }
  }

  /**
   * Represents a Jetpack Compose Composable node.
   *
   * @param parameters Standard Composable properties and function parameters.
   * @param mergedSemantics Accessibility properties that aggregate all readable texts and actions from this node's entire subtree into one
   *   focusable block (which is what a screen reader like TalkBack speaks).
   * @param unmergedSemantics Accessibility properties declared directly on this Composable node.
   * @param isSystemCreated True when this composable originates from framework code rather than the inspected app's own source, as
   *   classified by the inspector.
   */
  data class ComposeNode(
    override val id: Long,
    override val className: String,
    override val bounds: Bounds,
    override val children: List<UiNode>,
    val sourceLocation: SourceLocation?,
    val parameters: List<ComposeParameter>,
    val mergedSemantics: List<ComposeParameter>,
    val unmergedSemantics: List<ComposeParameter>,
    val isSystemCreated: Boolean,
  ) : UiNode()
}
