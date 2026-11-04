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
import com.android.tools.uicheck.snapshot.VisualLintAnalyzerIssueData
import com.android.tools.visuallint.VisualLintErrorType

/** Finds buttons (Compose `Role.Button` nodes and View button widgets) wider than Material Design's recommended 320dp. */
object ButtonSizeCheck : ComponentTreeCheck {
  private const val MAX_BUTTON_WIDTH_DP = 320

  /** Checks for buttons exceeding the maximum recommended width of 320dp, skipping internal descendants of button components. */
  override fun check(context: CheckContext): List<VisualLintAnalyzerIssueData> =
    context
      .visibleNodes(stopAt = { it.isButtonComponent() })
      .filter { it.isWideableButton() && it.bounds.width.pxToDp(context.densityDpi) > MAX_BUTTON_WIDTH_DP }
      .distinctBy { it.bounds }
      .map { createIssueData(it, context) }
      .toList()

  /** Creates a button width finding for [node]. */
  private fun createIssueData(
    node: ComponentTreeNode,
    context: CheckContext,
  ): VisualLintAnalyzerIssueData =
    buildIssueData(
      node = node,
      context = context,
      errorType = VisualLintErrorType.BUTTON_SIZE,
      message = "The button ${node.nameWithId()} is too wide",
      description =
        "The button ${node.simpleComponentName()} is wider than ${MAX_BUTTON_WIDTH_DP}dp in a preview configuration.<br/>" +
          "Material Design recommends buttons to be no wider than ${MAX_BUTTON_WIDTH_DP}dp",
    )

  /** Returns true if this button node is subject to the 320dp maximum width recommendation in either XML (`View`) or Compose. */
  private fun ComponentTreeNode.isWideableButton(): Boolean = if (isViewNode()) isWideableViewButton() else isWideableComposeButton()

  /**
   * Returns true if this classic Android `View` node is a wideable `android.widget.Button` subclass (including `CheckBox`, `RadioButton`,
   * `Switch`, `ToggleButton`, and Material `Chip` to match `ButtonSizeAnalyzer`, while excluding `ImageView`-backed buttons).
   */
  private fun ComponentTreeNode.isWideableViewButton(): Boolean = isButtonComponent() && simpleComponentName() !in IMAGE_VIEW_BUTTON_NAMES

  /** Returns true if this Composable button node is subject to the 320dp maximum width recommendation, excluding compact controls. */
  private fun ComponentTreeNode.isWideableComposeButton(): Boolean =
    isButtonComponent() && simpleComponentName() !in COMPOSE_NON_WIDE_BUTTON_NAMES
}
