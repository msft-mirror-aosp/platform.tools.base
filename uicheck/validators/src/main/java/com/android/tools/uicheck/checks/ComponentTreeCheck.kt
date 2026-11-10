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

import com.android.tools.uicheck.ComponentBounds
import com.android.tools.uicheck.ComponentTreeNode
import com.android.tools.uicheck.snapshot.UiCheckBounds
import com.android.tools.uicheck.snapshot.UiCheckIssue
import com.android.tools.visuallint.VisualLintErrorType

/**
 * A Visual Lint check that runs on the component tree of a rendered preview (`standalone-render`'s `includeComponentTree = true` output),
 * without reading the live `android.view.View` or `AccessibilityNodeInfo` hierarchy.
 */
interface ComponentTreeCheck {
  /** Evaluates this check against the rendered UI hierarchy in [context] and returns any findings. */
  fun check(context: CheckContext): List<UiCheckIssue>

  /** Builds a [UiCheckIssue] populated with [node]'s resolved source location, pixel [bounds], and component class name. */
  fun buildIssueData(
    node: ComponentTreeNode,
    context: CheckContext,
    errorType: VisualLintErrorType,
    message: String,
    description: String? = null,
    bounds: UiCheckBounds = node.bounds.toRectData(),
  ): UiCheckIssue =
    UiCheckIssue(
      errorType = errorType,
      message = message,
      description = description,
      sourceLocation = context.sourceLocationOf(node),
      viewBounds = bounds,
      viewClassName = node.simpleComponentName(),
    )
}

/** Converts this [ComponentBounds] into a serializable [UiCheckBounds]. */
internal fun ComponentBounds.toRectData(): UiCheckBounds = UiCheckBounds(left = left, top = top, right = right, bottom = bottom)

/** Extracts the simple unqualified name for [ComponentTreeNode.name], mapping generic View to Composable when semantics are present. */
internal fun ComponentTreeNode.simpleComponentName(): String {
  val raw = name.substringAfterLast('.')
  return if (raw == "View" && semantics != null) "Composable" else raw.ifBlank { "View" }
}
