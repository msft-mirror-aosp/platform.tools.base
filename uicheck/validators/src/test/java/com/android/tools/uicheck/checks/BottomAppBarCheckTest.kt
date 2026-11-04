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

import com.android.tools.visuallint.VisualLintErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomAppBarCheckTest {

  private val bottomAppBar = node("BottomAppBar", bounds(0, 1120, 1920, 1200))

  /** Verifies reporting of Compose BottomAppBar on non-compact screens (> 600dp wide and > 360dp tall). */
  @Test
  fun reportsBottomAppBarOnLargeScreens() {
    // 1920x1200px at 240dpi is 1280x800dp.
    val issue = BottomAppBarCheck.check(context(bottomAppBar, screenWidthPx = 1920, screenHeightPx = 1200, densityDpi = 240)).single()

    assertEquals(VisualLintErrorType.BOTTOM_APP_BAR, issue.errorType)
    assertEquals("Bottom app bars are only recommended for compact screens", issue.message)
  }

  /** Verifies reporting of Material XML BottomAppBar by FQN while ignoring unrelated custom views with the same simple name. */
  @Test
  fun reportsMaterialXmlBottomAppBarAndIgnoresUnrelatedCustomView() {
    val xmlBar = node("com.google.android.material.bottomappbar.BottomAppBar", bounds(0, 1120, 1920, 1200))
    val customView = node("com.example.widget.BottomAppBar", bounds(0, 1000, 1920, 1080))
    val root = node("FrameLayout", bounds(0, 0, 1920, 1200), children = listOf(xmlBar, customView))

    val issue = BottomAppBarCheck.check(context(root, screenWidthPx = 1920, screenHeightPx = 1200, densityDpi = 240)).single()
    assertEquals(VisualLintErrorType.BOTTOM_APP_BAR, issue.errorType)
  }

  /** Verifies that compact-width phones and wide-but-short landscape phones (<= 360dp tall) are ignored. */
  @Test
  fun ignoresBottomAppBarOnCompactWidthOrLandscapePhone() {
    // Compact width phone (411x891dp at 420dpi).
    assertTrue(BottomAppBarCheck.check(context(bottomAppBar)).isEmpty())

    // Landscape phone: 800dp wide (> 600dp) x 360dp tall (<= 360dp) at 160dpi.
    val landscapeBar = node("BottomAppBar", bounds(0, 300, 800, 360))
    assertTrue(BottomAppBarCheck.check(context(landscapeBar, screenWidthPx = 800, screenHeightPx = 360, densityDpi = 160)).isEmpty())
  }

  /** Verifies that a BottomAppBar nested inside a HorizontalPager is still detected. */
  @Test
  fun reportsBottomAppBarInsideHorizontalPager() {
    val pager = node("HorizontalPager", bounds(0, 0, 1920, 1200), children = listOf(bottomAppBar))
    val issue = BottomAppBarCheck.check(context(pager, screenWidthPx = 1920, screenHeightPx = 1200, densityDpi = 240)).single()

    assertEquals(VisualLintErrorType.BOTTOM_APP_BAR, issue.errorType)
  }
}
