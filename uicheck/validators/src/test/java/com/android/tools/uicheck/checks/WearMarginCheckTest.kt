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
import com.android.tools.visuallint.VisualLintErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearMarginCheckTest {

  /** Wraps [child] inside a 200x200px Wear OS watch root for testing. */
  private fun watch(child: ComponentTreeNode, isRoundDevice: Boolean = true) =
    context(
      node("Box", bounds(0, 0, 200, 200), children = listOf(child)),
      screenWidthPx = 200,
      screenHeightPx = 200,
      densityDpi = 160,
      isRoundDevice = isRoundDevice,
    )

  /** Verifies that content within 5.2% of either round watch edge is reported. */
  @Test
  fun reportsContentTooCloseToEdgeOfRoundDevice() {
    // The minimum margin on a 200px round device is 10.4px (minLeft = 10.4, maxRight = 189.6).
    val leftIssue = WearMarginCheck.check(watch(node("Row", bounds(10, 90, 40, 120), semantics = mapOf("role" to "Button")))).single()
    val rightIssue = WearMarginCheck.check(watch(node("Row", bounds(160, 90, 190, 120), semantics = mapOf("role" to "Button")))).single()
    val insideRoundMargin = node("Row", bounds(11, 90, 189, 120), semantics = mapOf("role" to "Button"))

    assertEquals(VisualLintErrorType.WEAR_MARGIN, leftIssue.errorType)
    assertEquals("The view Row is too close to the side of the device", leftIssue.message)
    assertEquals("The view Row is too close to the side of the device", rightIssue.message)
    assertTrue(WearMarginCheck.check(watch(insideRoundMargin)).isEmpty())
  }

  /** Verifies that content respecting the minimum margin inset is not reported. */
  @Test
  fun ignoresContentInsideMargin() {
    val safeText = node("Text", bounds(30, 40, 170, 70), semantics = mapOf("text" to "Hi"))
    val safeContainerWithOverflowingChild =
      node(
        "Box",
        bounds(20, 0, 180, 200),
        children = listOf(node("Text", bounds(0, 40, 170, 70), semantics = mapOf("text" to "Overflow"))),
      )

    assertTrue(WearMarginCheck.check(watch(safeText)).isEmpty())
    assertTrue(WearMarginCheck.check(watch(safeContainerWithOverflowingChild)).isEmpty())
  }

  /** Verifies that off-screen, scrolled-off, hidden, or zero-dimension viewport items are not reported. */
  @Test
  fun ignoresContentOutsideScreenOrScrollViewport() {
    val aboveScreen = node("Row", bounds(2, -50, 198, -10), semantics = mapOf("role" to "Button"))
    val belowScreen = node("Row", bounds(2, 230, 198, 270), semantics = mapOf("role" to "Button"))
    val leftOfScreen = node("Row", bounds(-120, 90, -10, 120), semantics = mapOf("role" to "Button"))
    val rightOfScreen = node("Row", bounds(210, 90, 320, 120), semantics = mapOf("role" to "Button"))
    val goneButton = node("Row", bounds(2, 90, 198, 120), semantics = mapOf("role" to "Button", "visibility" to "GONE"))
    val scrolledOffItem =
      node(
        "LazyColumn",
        bounds(0, 40, 200, 140),
        semantics = mapOf("actions" to "ScrollBy"),
        children = listOf(node("Text", bounds(2, 150, 198, 180), semantics = mapOf("text" to "Clipped"))),
      )

    assertTrue(WearMarginCheck.check(watch(aboveScreen)).isEmpty())
    assertTrue(WearMarginCheck.check(watch(belowScreen)).isEmpty())
    assertTrue(WearMarginCheck.check(watch(leftOfScreen)).isEmpty())
    assertTrue(WearMarginCheck.check(watch(rightOfScreen)).isEmpty())
    assertTrue(WearMarginCheck.check(watch(goneButton)).isEmpty())
    assertTrue(WearMarginCheck.check(watch(scrolledOffItem)).isEmpty())
    assertTrue(
      WearMarginCheck.check(
          context(node("Text", bounds(2, 10, 50, 40), semantics = mapOf("text" to "Hi")), screenWidthPx = 0, screenHeightPx = 200)
        )
        .isEmpty()
    )
    assertTrue(
      WearMarginCheck.check(
          context(node("Text", bounds(2, 10, 50, 40), semantics = mapOf("text" to "Hi")), screenWidthPx = 200, screenHeightPx = 0)
        )
        .isEmpty()
    )
  }

  /** Verifies that non-round Wear devices enforce the smaller 2.5% margin threshold rather than 5.2%. */
  @Test
  fun usesSmallerMarginOnSquareDevices() {
    // 2.5% of 200px is exactly 5.0px (minLeft = 5.0, maxRight = 195.0), testing exact boundary equality on rectangular screens.
    val text = node("Text", bounds(5, 40, 195, 70), semantics = mapOf("text" to "Hi"))
    val leftViolation = node("Text", bounds(4, 40, 195, 70), semantics = mapOf("text" to "Hi"))
    val rightViolation = node("Text", bounds(5, 40, 196, 70), semantics = mapOf("text" to "Hi"))

    assertEquals(1, WearMarginCheck.check(watch(text, isRoundDevice = true)).size)
    assertTrue(WearMarginCheck.check(watch(text, isRoundDevice = false)).isEmpty())
    assertEquals(1, WearMarginCheck.check(watch(leftViolation, isRoundDevice = false)).size)
    assertEquals(1, WearMarginCheck.check(watch(rightViolation, isRoundDevice = false)).size)
  }

  /**
   * Verifies that Compose nodes with widget semantics (`text`, `editable`, `progress`, and widget roles) are reported without duplicates.
   */
  @Test
  fun reportsComposeNodesWithWidgetSemantics() {
    val screen =
      node(
        "Column",
        bounds(0, 0, 200, 200),
        children =
          listOf(
            node("Text", bounds(2, 0, 198, 20), semantics = mapOf("text" to "Title")),
            node("BasicTextField", bounds(2, 20, 198, 40), semantics = mapOf("editable" to "true")),
            node("Slider", bounds(0, 40, 200, 60), semantics = mapOf("progress" to "current=2.0, range=0.0..5.0, steps=4")),
            node(
              "Row",
              bounds(0, 60, 200, 80),
              semantics = mapOf("role" to "Button", "id" to "start"),
              children = listOf(node("Text", bounds(2, 64, 198, 76), semantics = mapOf("text" to "Start"))),
            ),
            node("Box", bounds(0, 80, 40, 100), semantics = mapOf("role" to "Checkbox")),
            node("Box", bounds(0, 100, 40, 120), semantics = mapOf("role" to "RadioButton")),
            node("Box", bounds(0, 120, 40, 140), semantics = mapOf("role" to "Image", "contentDescription" to "Heart")),
            node("Box", bounds(0, 140, 200, 160), semantics = mapOf("role" to "DropdownList")),
            node("Box", bounds(0, 160, 200, 180), semantics = mapOf("role" to "Picker")),
          ),
      )

    assertEquals(
      listOf(
        "The view Text is too close to the side of the device",
        "The view BasicTextField is too close to the side of the device",
        "The view Slider is too close to the side of the device",
        "The view Row (start) is too close to the side of the device",
        "The view Box is too close to the side of the device",
        "The view Box is too close to the side of the device",
        "The view Box is too close to the side of the device",
        "The view Box is too close to the side of the device",
        "The view Box is too close to the side of the device",
      ),
      WearMarginCheck.check(watch(screen)).map { it.message },
    )
  }

  /**
   * Verifies that Compose nodes without widget semantics (decorative icons, bezel chrome, empty containers, non-widget roles
   * `Switch`/`Tab`, and nodes hidden from accessibility) are not reported, while descending into their children.
   */
  @Test
  fun ignoresComposeNodesWithoutWidgetSemantics() {
    val screen =
      node(
        "ScreenScaffold",
        bounds(0, 0, 200, 200),
        children =
          listOf(
            node("TimeText", bounds(0, 0, 200, 28), semantics = mapOf("contentDescription" to "12:30")),
            node("LevelIndicator", bounds(0, 20, 10, 180)),
            node("ScrollIndicator", bounds(190, 20, 200, 180)),
            node("Canvas", bounds(0, 0, 200, 200)),
            node("Spacer", bounds(0, 0, 200, 8)),
            node("Icon", bounds(0, 30, 24, 54), children = listOf(node("Box", bounds(0, 30, 24, 54)))),
            node("ScalingLazyColumn", bounds(0, 0, 200, 200), semantics = mapOf("actions" to "ScrollBy")),
            node("TitleCard", bounds(0, 60, 200, 90), semantics = mapOf("actions" to "OnClick")),
            node("Text", bounds(0, 90, 200, 105), semantics = mapOf("text" to "")),
            node(
              "Text",
              bounds(0, 105, 200, 120),
              semantics = mapOf("text" to "Hidden", "importantForAccessibility" to "no"),
            ),
            node(
              "Row",
              bounds(0, 120, 200, 150),
              semantics = mapOf("role" to "Switch", "toggleableState" to "On"),
              children = listOf(node("Text", bounds(30, 125, 170, 145), semantics = mapOf("text" to "Safe switch label"))),
            ),
            node(
              "Row",
              bounds(0, 150, 200, 180),
              semantics = mapOf("role" to "Tab"),
              children = listOf(node("Text", bounds(2, 155, 198, 175), semantics = mapOf("text" to "Edge tab label", "id" to "tab_lbl"))),
            ),
          ),
      )

    assertEquals(
      listOf("The view Text (tab_lbl) is too close to the side of the device"),
      WearMarginCheck.check(watch(screen)).map { it.message },
    )
  }

  /**
   * Verifies that Compose and XML containers descend into children, childless `ViewGroup`s are ignored via `isViewGroup`, container `View`s
   * with children are ignored even if `isViewGroup` is omitted, and leaf `View`s are reported.
   */
  @Test
  fun handlesComposeAndXmlContainersVersusLeafViews() {
    val xmlRoot =
      node(
        "android.widget.ScrollView",
        bounds(0, 0, 200, 200),
        semantics = mapOf("isViewGroup" to "true", "accessibilityClassName" to "android.widget.ScrollView"),
        children =
          listOf(
            node(
              "androidx.compose.ui.platform.AndroidComposeView",
              bounds(0, 0, 200, 200),
              semantics = mapOf("isViewGroup" to "true"),
            ),
            node(
              "com.example.PartialContainer",
              bounds(0, 0, 200, 200),
              semantics = mapOf("id" to "wrapper"),
              children =
                listOf(
                  node(
                    "android.widget.TextView",
                    bounds(2, 50, 198, 80),
                    semantics = mapOf("id" to "title", "accessibilityClassName" to "android.widget.TextView"),
                  )
                ),
            ),
            node(
              "android.view.View",
              bounds(2, 90, 40, 110),
              semantics = mapOf("id" to "swatch", "accessibilityClassName" to "android.view.View"),
            ),
            node("com.example.Divider", bounds(2, 120, 198, 122)),
          ),
      )

    assertEquals(
      listOf(
        "The view TextView (title) is too close to the side of the device",
        "The view View (swatch) is too close to the side of the device",
        "The view Divider is too close to the side of the device",
      ),
      WearMarginCheck.check(watch(xmlRoot)).map { it.message },
    )
  }
}
