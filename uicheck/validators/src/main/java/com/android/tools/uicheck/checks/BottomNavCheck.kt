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

/** Finds bottom navigation bars wider than 600dp, where Material Design recommends a navigation rail or drawer instead. */
object BottomNavCheck : ComponentTreeCheck {
  private const val NAVIGATION_RAIL_URL = "https://d.android.com/r/studio-ui/designer/material/navigation-rail"
  private const val NAVIGATION_DRAWER_URL = "https://d.android.com/r/studio-ui/designer/material/navigation-drawer"
  private val BOTTOM_NAV_NAMES =
    setOf(
      "NavigationBar",
      "BottomNavigation",
      "com.google.android.material.bottomnavigation.BottomNavigationView",
    )

  /** Checks for bottom navigation bars wider than 600dp. */
  override fun check(context: CheckContext): List<UiCheckIssue> =
    context
      .visibleNodes()
      .filter { it.isBottomNav() && it.bounds.width.pxToDp(context.densityDpi) > COMPACT_WIDTH_BREAKPOINT_DP }
      .distinctBy { it.bounds }
      .map { createIssueData(it, context) }
      .toList()

  /** Creates a bottom navigation bar finding for [node]. */
  private fun createIssueData(
    node: ComponentTreeNode,
    context: CheckContext,
  ): UiCheckIssue =
    buildIssueData(
      node = node,
      context = context,
      errorType = VisualLintErrorType.BOTTOM_NAV,
      message = "Bottom navigation bar is not recommended for breakpoints over 600dp",
      description =
        "Bottom navigation bar is not recommended for breakpoints >= 600dp, which affects a preview configuration.<br/>" +
          "Material Design recommends replacing bottom navigation bar with " +
          "<a href=\"$NAVIGATION_RAIL_URL\">navigation rail</a> or " +
          "<a href=\"$NAVIGATION_DRAWER_URL\">navigation drawer</a> for breakpoints >= 600dp.",
    )

  /** Returns true if this component represents a Compose or Material View bottom navigation bar. */
  private fun ComponentTreeNode.isBottomNav(): Boolean = name in BOTTOM_NAV_NAMES
}
