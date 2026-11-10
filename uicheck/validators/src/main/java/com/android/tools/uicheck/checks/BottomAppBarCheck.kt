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

/** Finds bottom app bars on screens that aren't compact, where Material Design recommends other navigation. */
object BottomAppBarCheck : ComponentTreeCheck {
  private const val COMPACT_HEIGHT_BREAKPOINT_DP = 360
  private const val NAVIGATION_RAIL_URL = "https://d.android.com/r/studio-ui/designer/material/navigation-rail"
  private const val NAVIGATION_DRAWER_URL = "https://d.android.com/r/studio-ui/designer/material/navigation-drawer"
  private const val TOP_APP_BAR_URL = "https://d.android.com/r/studio-ui/designer/material/top-app-bar"
  private val BOTTOM_APP_BAR_NAMES =
    setOf(
      "BottomAppBar",
      "com.google.android.material.bottomappbar.BottomAppBar",
    )

  /** Checks for bottom app bars placed on screens both wider than 600dp and taller than 360dp. */
  override fun check(context: CheckContext): List<UiCheckIssue> {
    val isCompact =
      context.screenWidthPx.pxToDp(context.densityDpi) <= COMPACT_WIDTH_BREAKPOINT_DP ||
        context.screenHeightPx.pxToDp(context.densityDpi) <= COMPACT_HEIGHT_BREAKPOINT_DP
    if (isCompact) return emptyList()
    return context.visibleNodes().filter { it.isBottomAppBar() }.distinctBy { it.bounds }.map { createIssueData(it, context) }.toList()
  }

  /** Creates a bottom app bar finding for [node]. */
  private fun createIssueData(
    node: ComponentTreeNode,
    context: CheckContext,
  ): UiCheckIssue =
    buildIssueData(
      node = node,
      context = context,
      errorType = VisualLintErrorType.BOTTOM_APP_BAR,
      message = "Bottom app bars are only recommended for compact screens",
      description =
        "Bottom app bars are only recommended for compact screens, which affects a preview configuration.<br/>" +
          "Material Design recommends replacing bottom app bar with " +
          "<a href=\"$NAVIGATION_RAIL_URL\">navigation rail</a>, " +
          "<a href=\"$NAVIGATION_DRAWER_URL\">navigation drawer</a> or " +
          "<a href=\"$TOP_APP_BAR_URL\">top app bar</a> for breakpoints over 600dp.",
    )

  /** Returns true if this component represents a Compose or Material View BottomAppBar. */
  private fun ComponentTreeNode.isBottomAppBar(): Boolean = name in BOTTOM_APP_BAR_NAMES
}
