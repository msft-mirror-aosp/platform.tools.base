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

import com.android.tools.uicheck.ProjectSourceFile
import com.android.tools.uicheck.snapshot.UiCheckSnapshot.Companion.DEFAULT_DENSITY_DPI
import com.android.tools.uicheck.snapshot.UiCheckSnapshot.Companion.DEFAULT_FONT_SCALE
import com.android.tools.uicheck.snapshot.UiCheckSourceLocation
import com.android.tools.visuallint.VisualLintErrorType
import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCheckAnalyzerTest {

  private val userCode = UserCodeFilter(listOf(ProjectSourceFile(DASHBOARD_FILE, PACKAGE)))

  /** A 1280x800dp tablet (1920x1200px at 240dpi) with one problem for each check. */
  private val dashboard =
    tree(
      methodFqn = DASHBOARD_PREVIEW,
      width = 1920,
      height = 1200,
      densityDpi = 240,
      components =
        listOf(
          node(
            "Column",
            bounds(0, 0, 1920, 1200),
            sourceLoc(DASHBOARD_FILE, 20, PACKAGE),
            children =
              listOf(
                // 400dp wide button (> 320dp).
                node("Button", bounds(0, 4, 600, 80), sourceLoc(DASHBOARD_FILE, 25, PACKAGE), semantics = mapOf("text" to "Continue")),
                // 600dp wide text field (> 488dp).
                node("OutlinedTextField", bounds(20, 100, 920, 184), sourceLoc(DASHBOARD_FILE, 30, PACKAGE)),
                // 145 characters on one line.
                node(
                  "Text",
                  bounds(20, 200, 1800, 230),
                  sourceLoc(DASHBOARD_FILE, 35, PACKAGE),
                  semantics = mapOf("text" to "A".repeat(145)),
                ),
                node("NavigationBar", bounds(0, 1000, 1920, 1120), sourceLoc(DASHBOARD_FILE, 55, PACKAGE)),
                node("BottomAppBar", bounds(0, 1120, 1920, 1200), sourceLoc(DASHBOARD_FILE, 60, PACKAGE)),
              ),
          )
        ),
    )

  /** Verifies that each check reports its problem, pointing at the user's call site. */
  @Test
  fun runsAllChecks() {
    val snapshot =
      UiCheckAnalyzer.analyze(DASHBOARD_PREVIEW, "Medium Tablet (fontScale=1.0)", dashboard, renderedImage = null, userCode = userCode)
    val errorTypes = snapshot.issues.map { it.errorType }

    assertTrue("$errorTypes", VisualLintErrorType.BUTTON_SIZE in errorTypes)
    assertTrue("$errorTypes", VisualLintErrorType.TEXT_FIELD_SIZE in errorTypes)
    assertTrue("$errorTypes", VisualLintErrorType.BOTTOM_NAV in errorTypes)
    assertTrue("$errorTypes", VisualLintErrorType.BOTTOM_APP_BAR in errorTypes)
    assertTrue("$errorTypes", VisualLintErrorType.LONG_TEXT in errorTypes)
    assertEquals(
      UiCheckSourceLocation(DASHBOARD_FILE, 25, "Button"),
      snapshot.issues.first { it.errorType == VisualLintErrorType.BUTTON_SIZE }.sourceLocation,
    )
  }

  /** Verifies that a finding on a library component points at the closest enclosing user call site. */
  @Test
  fun pointsLibraryComponentsAtEnclosingUserCallSite() {
    val libraryButton =
      tree(
        methodFqn = DASHBOARD_PREVIEW,
        width = 1920,
        height = 1200,
        densityDpi = 240,
        components =
          listOf(
            node(
              "Row",
              bounds(0, 0, 1920, 200),
              sourceLoc(DASHBOARD_FILE, 40, PACKAGE),
              children =
                listOf(
                  // A Material 3 button whose own call site is inside the library.
                  node("Button", bounds(0, 4, 600, 80), sourceLoc("Button.kt", 120, MATERIAL3_PACKAGE), semantics = mapOf("text" to "Go"))
                ),
            )
          ),
      )

    val snapshot = UiCheckAnalyzer.analyze(DASHBOARD_PREVIEW, "Medium Tablet", libraryButton, renderedImage = null, userCode = userCode)

    assertEquals(UiCheckSourceLocation(DASHBOARD_FILE, 40, "Row"), snapshot.issues.single().sourceLocation)
  }

  /** Verifies that controls and bars sized for a compact phone report no findings. */
  @Test
  fun reportsNothingOnCompactPhone() {
    val phone =
      tree(
        methodFqn = DASHBOARD_PREVIEW,
        width = 1080,
        height = 2340,
        densityDpi = 420,
        components =
          listOf(
            node(
              "Column",
              bounds(0, 0, 1080, 2340),
              sourceLoc(DASHBOARD_FILE, 20, PACKAGE),
              children =
                listOf(
                  node("Button", bounds(0, 4, 600, 130), sourceLoc(DASHBOARD_FILE, 25, PACKAGE), semantics = mapOf("text" to "Continue")),
                  node("OutlinedTextField", bounds(20, 200, 1060, 350), sourceLoc(DASHBOARD_FILE, 30, PACKAGE)),
                  node("NavigationBar", bounds(0, 2000, 1080, 2200), sourceLoc(DASHBOARD_FILE, 55, PACKAGE)),
                  node("BottomAppBar", bounds(0, 2200, 1080, 2340), sourceLoc(DASHBOARD_FILE, 60, PACKAGE)),
                ),
            )
          ),
      )

    val snapshot =
      UiCheckAnalyzer.analyze(DASHBOARD_PREVIEW, "Medium Phone (fontScale=1.0)", phone, renderedImage = null, userCode = userCode)

    assertTrue("${snapshot.issues}", snapshot.issues.isEmpty())
  }

  /**
   * Verifies that the screen size comes from the rendered image, which includes the system bars that the component tree's own size leaves
   * out, so a tablet with system UI is not mistaken for a short screen.
   */
  @Test
  fun usesRenderedImageSizeForScreen() {
    // The tree reports the 1920x1032 content area of a 1920x1200 tablet with system UI; 1032px at 240dpi is only 688dp tall.
    val withSystemUi = dashboard.copy(height = 1032)
    val image = BufferedImage(1920, 1200, BufferedImage.TYPE_INT_ARGB)

    val snapshot = UiCheckAnalyzer.analyze(DASHBOARD_PREVIEW, "Medium Tablet", withSystemUi, renderedImage = image, userCode = userCode)

    assertEquals(1920, snapshot.screenWidthPx)
    assertEquals(1200, snapshot.screenHeightPx)
    assertEquals(
      1032,
      UiCheckAnalyzer.analyze(DASHBOARD_PREVIEW, "Medium Tablet", withSystemUi, renderedImage = null, userCode = userCode).screenHeightPx,
    )
  }

  /** Verifies that the snapshot carries the preview name, configuration, preview ID, and rendering metadata. */
  @Test
  fun fillsSnapshotFromComponentTree() {
    val snapshot =
      UiCheckAnalyzer.analyze(
        DASHBOARD_PREVIEW,
        "Medium Tablet (fontScale=2.0)",
        dashboard.copy(fontScale = 2.0f),
        renderedImage = null,
        userCode = userCode,
      )

    assertEquals(DASHBOARD_PREVIEW, snapshot.name)
    assertEquals("Medium Tablet (fontScale=2.0)", snapshot.configurationDescription)
    assertEquals("${DASHBOARD_PREVIEW}_0", snapshot.previewId)
    assertEquals(1920, snapshot.screenWidthPx)
    assertEquals(1200, snapshot.screenHeightPx)
    assertEquals(240, snapshot.densityDpi)
    assertEquals(2.0f, snapshot.fontScale)
  }

  /** Verifies that a missing, non-positive, or non-finite density and font scale fall back to the defaults. */
  @Test
  fun fallsBackToDefaultDensityAndFontScale() {
    val trees =
      listOf(
        dashboard.copy(densityDpi = null, fontScale = null),
        dashboard.copy(densityDpi = -1, fontScale = 0f),
        dashboard.copy(densityDpi = 0, fontScale = Float.NaN),
        dashboard.copy(densityDpi = null, fontScale = Float.POSITIVE_INFINITY),
      )

    for (tree in trees) {
      val snapshot = UiCheckAnalyzer.analyze(DASHBOARD_PREVIEW, "Medium Tablet", tree, renderedImage = null, userCode = userCode)

      assertEquals("${tree.densityDpi}", DEFAULT_DENSITY_DPI, snapshot.densityDpi)
      assertEquals("${tree.fontScale}", DEFAULT_FONT_SCALE, snapshot.fontScale)
    }
  }

  private companion object {
    const val PACKAGE = "com.example"
    const val MATERIAL3_PACKAGE = "androidx.compose.material3"
    const val DASHBOARD_FILE = "Dashboard.kt"
    const val DASHBOARD_PREVIEW = "com.example.AppKt.DashboardPreview"
  }
}
