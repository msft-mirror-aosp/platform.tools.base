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

internal val COMPOSE_BUTTON_NAMES =
  setOf(
    "Button",
    "ElevatedButton",
    "FilledTonalButton",
    "OutlinedButton",
    "TextButton",
    "ExtendedFloatingActionButton",
  )

internal val VIEW_BUTTON_NAMES =
  setOf(
    "Button",
    "AppCompatButton",
    "MaterialButton",
    "ExtendedFloatingActionButton",
  )

internal val COMPOSE_CONTAINER_NAMES =
  setOf(
    "Box",
    "Row",
    "Column",
    "Surface",
    "Card",
    "ElevatedCard",
    "OutlinedCard",
    "ListItem",
    "FlowRow",
    "FlowColumn",
    "ConstraintLayout",
  )

internal val VIEW_COMPOUND_BUTTON_NAMES =
  setOf(
    "CheckBox",
    "AppCompatCheckBox",
    "MaterialCheckBox",
    "RadioButton",
    "AppCompatRadioButton",
    "MaterialRadioButton",
    "Switch",
    "SwitchCompat",
    "SwitchMaterial",
    "MaterialSwitch",
    "ToggleButton",
    "AppCompatToggleButton",
    "Chip",
  )

internal val ICON_BUTTON_COMPONENT_NAMES =
  setOf(
    "IconButton",
    "FilledIconButton",
    "FilledTonalIconButton",
    "OutlinedIconButton",
    "IconToggleButton",
  )

/** Classic `View` button types backed by `ImageView` rather than `TextView` (`android.widget.Button`). */
internal val IMAGE_VIEW_BUTTON_NAMES =
  setOf(
    "ImageButton",
    "AppCompatImageButton",
    "FloatingActionButton",
  )

/** Button-like components that are expected to be compact in Compose, so they are excluded from Compose maximum button width checks. */
internal val COMPOSE_NON_WIDE_BUTTON_NAMES =
  ICON_BUTTON_COMPONENT_NAMES +
    setOf(
      "FloatingActionButton",
      "SmallFloatingActionButton",
      "LargeFloatingActionButton",
      "Checkbox",
      "RadioButton",
      "Switch",
      "FilterChip",
      "InputChip",
      "AssistChip",
      "SuggestionChip",
      "NavigationBarItem",
      "NavigationRailItem",
      "Tab",
      "LeadingIconTab",
    )

internal val COMPOSE_TEXT_FIELD_NAMES =
  setOf(
    "TextField",
    "OutlinedTextField",
    "BasicTextField",
    "CoreTextField",
    "BasicSecureTextField",
    "SecureTextField",
  )

internal val VIEW_TEXT_FIELD_NAMES =
  setOf(
    "EditText",
    "AppCompatEditText",
    "TextInputEditText",
    "AutoCompleteTextView",
    "AppCompatAutoCompleteTextView",
    "MaterialAutoCompleteTextView",
    "MultiAutoCompleteTextView",
    "AppCompatMultiAutoCompleteTextView",
    "ExtractEditText",
  )
