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

class BottomNavCheckTest {

  /** Verifies reporting of Compose NavigationBar wider than 600dp. */
  @Test
  fun reportsBottomNavigationWiderThan600dp() {
    // 1920px at 240dpi is 1280dp.
    val nav = node("NavigationBar", bounds(0, 1000, 1920, 1120))
    val issue = BottomNavCheck.check(context(nav, screenWidthPx = 1920, screenHeightPx = 1200, densityDpi = 240)).single()

    assertEquals(VisualLintErrorType.BOTTOM_NAV, issue.errorType)
    assertEquals("Bottom navigation bar is not recommended for breakpoints over 600dp", issue.message)
  }

  /** Verifies Material XML BottomNavigationView is reported while LayoutLib's system NavigationBar is ignored. */
  @Test
  fun reportsXmlBottomNavigationViewAndIgnoresLayoutlibSystemBar() {
    val materialNav = node("com.google.android.material.bottomnavigation.BottomNavigationView", bounds(0, 1000, 1920, 1120))
    val layoutlibSystemNav = node("com.android.layoutlib.bridge.bars.NavigationBar", bounds(0, 1120, 1920, 1200))
    val root = node("FrameLayout", bounds(0, 0, 1920, 1200), children = listOf(materialNav, layoutlibSystemNav))

    val issue = BottomNavCheck.check(context(root, screenWidthPx = 1920, screenHeightPx = 1200, densityDpi = 240)).single()
    assertEquals("BottomNavigationView", issue.viewClassName)
  }

  /** Verifies the exact 600dp boundary: 600dp is allowed and 601dp is flagged. */
  @Test
  fun respectsExact600dpWidthBoundary() {
    val exact600dp = node("NavigationBar", bounds(0, 700, 600, 800))
    assertTrue(BottomNavCheck.check(context(exact600dp, screenWidthPx = 800, screenHeightPx = 800, densityDpi = 160)).isEmpty())

    val width601dp = node("NavigationBar", bounds(0, 700, 601, 800))
    assertEquals(1, BottomNavCheck.check(context(width601dp, screenWidthPx = 800, screenHeightPx = 800, densityDpi = 160)).size)
  }

  /** Verifies that nested NavigationBar nodes sharing identical bounds are reported only once. */
  @Test
  fun reportsDuplicateBoundsOnce() {
    val inner = node("NavigationBar", bounds(0, 700, 800, 800))
    val outer = node("NavigationBar", bounds(0, 700, 800, 800), children = listOf(inner))

    assertEquals(1, BottomNavCheck.check(context(outer, screenWidthPx = 800, screenHeightPx = 800, densityDpi = 160)).size)
  }

  /** Verifies that a NavigationBar placed inside a LazyColumn or HorizontalPager is still detected. */
  @Test
  fun reportsBottomNavigationInsideScrollableContainer() {
    val nav = node("NavigationBar", bounds(0, 700, 800, 800))
    val pager = node("HorizontalPager", bounds(0, 0, 800, 800), children = listOf(nav))

    assertEquals(1, BottomNavCheck.check(context(pager, screenWidthPx = 800, screenHeightPx = 800, densityDpi = 160)).size)
  }

  /** Verifies that phone-width bottom navigation bars (<= 600dp) are ignored. */
  @Test
  fun ignoresBottomNavigationOnPhones() {
    // 1080px at 420dpi is 411dp.
    assertTrue(BottomNavCheck.check(context(node("NavigationBar", bounds(0, 2100, 1080, 2340)))).isEmpty())
  }
}
