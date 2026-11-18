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
import com.android.tools.uicheck.snapshot.UiCheckIssue
import com.android.tools.visuallint.VisualLintErrorType

/**
 * Finds Wear OS content closer to the screen edge than the recommended margin (5.2% on round devices, 2.5% on rectangular ones), mirroring
 * Android Studio's `WearMarginAnalyzer`.
 */
object WearMarginCheck : ComponentTreeCheck {
  private const val MIN_ROUND_WEAR_MARGIN_RATIO = 0.052
  private const val MIN_RECT_WEAR_MARGIN_RATIO = 0.025

  /**
   * Compose semantic roles that `AndroidComposeViewAccessibilityDelegateCompat` maps to a non-`View` accessibility class (`Button`,
   * `CheckBox`, `RadioButton`, `ImageView`, `Spinner`, `NumberPicker`). `Role.ValuePicker.toString()` is `"Picker"`.
   */
  private val COMPOSE_WIDGET_ROLES =
    setOf(
      "Button",
      "Checkbox",
      "RadioButton",
      "Image",
      "DropdownList",
      "Picker",
    )

  /** Checks that Wear OS content maintains minimum margin distance from the display edge. */
  override fun check(context: CheckContext): List<UiCheckIssue> {
    val minPercent = if (context.isRoundDevice) MIN_ROUND_WEAR_MARGIN_RATIO else MIN_RECT_WEAR_MARGIN_RATIO
    val minLeft = minPercent * context.screenWidthPx
    val maxRight = context.screenWidthPx - minLeft

    return context
      .visibleNodes(stopAt = { it.isWithinMargin(minLeft, maxRight) || it.isWearMarginRelevant() })
      .filter { !it.isWithinMargin(minLeft, maxRight) && it.isWearMarginRelevant() }
      .map { createIssueData(it, context) }
      .toList()
  }

  /** Returns true if this node's horizontal bounds stay within `[minLeft, maxRight]`. */
  private fun ComponentTreeNode.isWithinMargin(minLeft: Double, maxRight: Double): Boolean =
    bounds.left >= minLeft && bounds.right <= maxRight

  /** Returns true if this node is relevant to Wear OS margin analysis rather than a layout container. */
  private fun ComponentTreeNode.isWearMarginRelevant(): Boolean =
    if (isViewNode()) isViewWearMarginRelevant() else isComposeWearMarginRelevant()

  /**
   * Returns true when this classic Android `View` node is not a `ViewGroup`, matching `WearMarginAnalyzer`'s `viewObject !is ViewGroup`
   * check via the exported `isViewGroup` semantics flag and children presence.
   */
  private fun ComponentTreeNode.isViewWearMarginRelevant(): Boolean = semantics?.get("isViewGroup") != "true" && children.isNullOrEmpty()

  /**
   * Returns true when this Composable node exposes semantics that `AndroidComposeViewAccessibilityDelegateCompat` maps to a non-`View`
   * widget class (`TextView`, `EditText`, `SeekBar`/`ProgressBar`, or a role in [COMPOSE_WIDGET_ROLES]), matching `WearMarginAnalyzer`'s
   * `className != "android.view.View" && className != "androidx.compose.ui.platform.ComposeView"` check. Accessibility-hidden nodes
   * (`importantForAccessibility == "no"`) and Compose scroll containers are intentionally skipped so hidden decorative nodes are ignored
   * and scroll children are inspected.
   */
  private fun ComponentTreeNode.isComposeWearMarginRelevant(): Boolean {
    val sem = semantics ?: return false
    if (sem["importantForAccessibility"] == "no") return false
    return !sem["text"].isNullOrEmpty() || sem["editable"] == "true" || sem["progress"] != null || sem["role"] in COMPOSE_WIDGET_ROLES
  }

  /** Creates a Wear OS margin finding for [node]. */
  private fun createIssueData(
    node: ComponentTreeNode,
    context: CheckContext,
  ): UiCheckIssue {
    val simpleName = node.simpleComponentName()
    return buildIssueData(
      node = node,
      context = context,
      errorType = VisualLintErrorType.WEAR_MARGIN,
      message = "The view ${node.nameWithId()} is too close to the side of the device",
      description =
        "In a preview configuration, the view $simpleName is closer to the side of the device than the recommended amount.<br/>" +
          "It is recommended that, for Wear OS layouts, margins should be at least ${MIN_RECT_WEAR_MARGIN_RATIO * 100}% for square devices," +
          " and ${MIN_ROUND_WEAR_MARGIN_RATIO * 100}% for round devices.",
    )
  }
}
