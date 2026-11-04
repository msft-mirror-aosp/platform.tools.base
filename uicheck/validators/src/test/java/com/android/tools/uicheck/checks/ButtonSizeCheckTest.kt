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

class ButtonSizeCheckTest {

  /** Verifies that Compose buttons wider than 320dp are reported. */
  @Test
  fun reportsButtonWiderThan320dp() {
    // 600px at 240dpi is 400dp.
    val issue = ButtonSizeCheck.check(context(node("Button", bounds(20, 40, 620, 120)), densityDpi = 240)).single()

    assertEquals(VisualLintErrorType.BUTTON_SIZE, issue.errorType)
    assertEquals("The button Button is too wide", issue.message)
  }

  /** Verifies that XML `ExtendedFloatingActionButton`s wider than 320dp are reported. */
  @Test
  fun reportsWideXmlExtendedFloatingActionButton() {
    val fab = node("com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton", bounds(0, 0, 600, 80))

    assertEquals(1, ButtonSizeCheck.check(context(fab, densityDpi = 240)).size)
  }

  /** Verifies that buttons at or below the 320dp limit are not reported. */
  @Test
  fun ignoresButtonsUpTo320dp() {
    assertTrue(ButtonSizeCheck.check(context(node("Button", bounds(0, 0, 480, 80)), densityDpi = 240)).isEmpty())
  }

  /** Verifies that invisible or gone buttons wider than 320dp are not reported. */
  @Test
  fun ignoresInvisibleAndGoneButtons() {
    val invisibleButton = node("Button", bounds(0, 0, 600, 80), semantics = mapOf("visibility" to "INVISIBLE"))
    val goneButton = node("android.widget.Button", bounds(0, 0, 600, 80), parameters = mapOf("visibility" to "GONE"))

    assertTrue(ButtonSizeCheck.check(context(invisibleButton, densityDpi = 240)).isEmpty())
    assertTrue(ButtonSizeCheck.check(context(goneButton, densityDpi = 240)).isEmpty())
  }

  /** Verifies that compact Compose controls and their internal Surface children with Button role are skipped. */
  @Test
  fun ignoresIconButtonsAndInternalRoleDescendants() {
    val iconButtonWithInnerSurface =
      node(
        "IconButton",
        bounds(0, 0, 600, 80),
        semantics = mapOf("role" to "Button"),
        children = listOf(node("Surface", bounds(4, 4, 596, 76), semantics = mapOf("role" to "Button"))),
      )

    assertTrue(ButtonSizeCheck.check(context(iconButtonWithInnerSurface, densityDpi = 240)).isEmpty())
    assertTrue(ButtonSizeCheck.check(context(node("Checkbox", bounds(0, 0, 600, 80)), densityDpi = 240)).isEmpty())
  }

  /** Verifies that custom non-container composables with Role.Button are checked while clickable layout containers are ignored. */
  @Test
  fun checksCustomButtonRoleNodesWhileSkippingClickableContainers() {
    val customButton = node("PrimaryActionCta", bounds(0, 0, 600, 80), semantics = mapOf("role" to "Button", "id" to "submit"))
    val clickableRow = node("Row", bounds(0, 0, 600, 80), semantics = mapOf("role" to "Button"))
    val clickableCard = node("Card", bounds(0, 0, 600, 200), semantics = mapOf("role" to "Button"))

    assertEquals(
      listOf("The button PrimaryActionCta (submit) is too wide"),
      ButtonSizeCheck.check(context(customButton, densityDpi = 240)).map { it.message },
    )
    assertTrue(ButtonSizeCheck.check(context(clickableRow, densityDpi = 240)).isEmpty())
    assertTrue(ButtonSizeCheck.check(context(clickableCard, densityDpi = 240)).isEmpty())
  }

  /** Verifies XML View Button subclasses (including CheckBox and MaterialButton) are flagged while ImageButton is ignored. */
  @Test
  fun checksXmlViewButtonSubclassesIncludingCompoundButtons() {
    val root =
      node(
        "android.widget.LinearLayout",
        bounds(0, 0, 800, 600),
        children =
          listOf(
            node("android.widget.Button", bounds(0, 0, 600, 80), parameters = mapOf("id" to "cta")),
            node("com.google.android.material.button.MaterialButton", bounds(0, 100, 600, 180)),
            node("android.widget.CheckBox", bounds(0, 200, 600, 280), parameters = mapOf("id" to "terms")),
            node("android.widget.ImageButton", bounds(0, 300, 600, 380)),
          ),
      )

    assertEquals(
      listOf(
        "The button Button (cta) is too wide",
        "The button MaterialButton is too wide",
        "The button CheckBox (terms) is too wide",
      ),
      ButtonSizeCheck.check(context(root, densityDpi = 240)).map { it.message },
    )
  }
}
