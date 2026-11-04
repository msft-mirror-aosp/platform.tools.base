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

package com.android.tools.uicheck.checks

import com.android.tools.uicheck.ComponentTreeNode

/**
 * Returns true if this node originates from a classic Android `View` (which uses a fully qualified class name) rather than a Composable.
 */
internal fun ComponentTreeNode.isViewNode(): Boolean = name.contains('.')

/** Returns the simple component name optionally suffixed with its semantic or resource ID. */
internal fun ComponentTreeNode.nameWithId(): String {
  val simple = simpleComponentName()
  val id = semantics?.get("id") ?: parameters?.get("id")
  return if (!id.isNullOrBlank()) "$simple ($id)" else simple
}

/** Returns true if this node is a button component in either a classic Android `View` (XML) or Compose hierarchy. */
internal fun ComponentTreeNode.isButtonComponent(): Boolean = if (isViewNode()) isViewButton() else isComposeButton()

/**
 * Returns true if this classic Android `View` node is a button or compound button.
 *
 * Note: Matching uses known framework and Material simple class names (custom XML `Button` subclasses with arbitrary class names are not
 * matched unless `PreviewComponentTree` records the `View` superclass hierarchy or `Button` role).
 */
private fun ComponentTreeNode.isViewButton(): Boolean {
  val simple = simpleComponentName()
  return simple in VIEW_BUTTON_NAMES ||
    simple in VIEW_COMPOUND_BUTTON_NAMES ||
    simple in IMAGE_VIEW_BUTTON_NAMES ||
    semantics?.get("role") == "Button"
}

/** Returns true if this Composable node is a button component or a non-container node with a `Button` semantic role. */
private fun ComponentTreeNode.isComposeButton(): Boolean {
  val simple = simpleComponentName()
  return simple in COMPOSE_BUTTON_NAMES ||
    simple in COMPOSE_NON_WIDE_BUTTON_NAMES ||
    (semantics?.get("role") == "Button" && simple !in COMPOSE_CONTAINER_NAMES)
}

/** Returns true if this node represents an editable text field in either a classic Android `View` (XML) or Compose hierarchy. */
internal fun ComponentTreeNode.isTextFieldComponent(): Boolean = if (isViewNode()) isViewTextField() else isComposeTextField()

/**
 * Returns true if this classic Android `View` node represents an `EditText` or editable text field.
 *
 * Note: Matching uses known framework and Material simple class names (custom XML `EditText` subclasses with arbitrary class names are not
 * matched unless `PreviewComponentTree` records the `View` superclass hierarchy or `SetText` action).
 */
private fun ComponentTreeNode.isViewTextField(): Boolean = simpleComponentName() in VIEW_TEXT_FIELD_NAMES || hasAction("SetText")

/** Returns true if this Composable node represents a text field or exposes a `SetText` semantic action. */
private fun ComponentTreeNode.isComposeTextField(): Boolean = simpleComponentName() in COMPOSE_TEXT_FIELD_NAMES || hasAction("SetText")
