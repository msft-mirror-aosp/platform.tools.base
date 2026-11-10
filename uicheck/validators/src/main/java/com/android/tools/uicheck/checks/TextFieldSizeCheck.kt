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

/** Finds text fields (Compose text fields and View `EditText` widgets) wider than the recommended 488dp. */
object TextFieldSizeCheck : ComponentTreeCheck {
  private const val MAX_TEXT_FIELD_WIDTH_DP = 488

  /** Checks for text fields exceeding the maximum recommended width of 488dp, evaluating only the outermost text field node. */
  override fun check(context: CheckContext): List<UiCheckIssue> =
    context
      .visibleNodes(stopAt = { it.isTextFieldComponent() })
      .filter { it.isTextFieldComponent() && it.bounds.width.pxToDp(context.densityDpi) > MAX_TEXT_FIELD_WIDTH_DP }
      .distinctBy { it.bounds }
      .map { createIssueData(it, context) }
      .toList()

  /** Creates a text field width finding for [node]. */
  private fun createIssueData(
    node: ComponentTreeNode,
    context: CheckContext,
  ): UiCheckIssue =
    buildIssueData(
      node = node,
      context = context,
      errorType = VisualLintErrorType.TEXT_FIELD_SIZE,
      message = "The text field ${node.nameWithId()} is too wide",
      description =
        "The text field ${node.simpleComponentName()} is wider than ${MAX_TEXT_FIELD_WIDTH_DP}dp in a preview configuration.<br/>" +
          "Material Design recommends text fields to be no wider than ${MAX_TEXT_FIELD_WIDTH_DP}dp",
    )
}
