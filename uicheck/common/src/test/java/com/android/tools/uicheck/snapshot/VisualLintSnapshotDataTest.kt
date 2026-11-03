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

package com.android.tools.uicheck.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualLintSnapshotDataTest {

  /** Verifies rectangle dimensions, area, and clamping on inverted coordinates. */
  @Test
  fun computesDimensionsAndClampsInvertedBounds() {
    val rect = VisualLintRectData(left = 10, top = 20, right = 110, bottom = 70)
    assertEquals(100, rect.width)
    assertEquals(50, rect.height)
    assertEquals(5000, rect.area)

    val inverted = VisualLintRectData(left = 50, top = 50, right = 20, bottom = 30)
    assertEquals(0, inverted.width)
    assertEquals(0, inverted.height)
    assertEquals(0, inverted.area)
  }

  /** Verifies non-zero overlap detection, touching-edge exclusion, and inverted-rectangle rejection. */
  @Test
  fun computesIntersectionAndOverlapArea() {
    val first = VisualLintRectData(left = 0, top = 0, right = 100, bottom = 100)
    val overlapping = VisualLintRectData(left = 60, top = 40, right = 140, bottom = 90)
    val touchingEdge = VisualLintRectData(left = 100, top = 0, right = 200, bottom = 100)
    val inverted = VisualLintRectData(left = 50, top = 50, right = 20, bottom = 30)

    assertTrue(first.intersects(overlapping))
    assertEquals(2000, first.intersectionArea(overlapping))
    assertFalse(first.intersects(touchingEdge))
    assertEquals(0, first.intersectionArea(touchingEdge))
    assertFalse(first.intersects(inverted))
    assertFalse(inverted.intersects(first))
    assertEquals(0, first.intersectionArea(inverted))
  }

  /** Verifies default density, font-scale, and unknown 1-based line sentinel values. */
  @Test
  fun usesDefaultPhoneDensityAndFontScale() {
    val snapshot =
      VisualLintSnapshotData(
        name = "MainActivityKt.PreviewCard",
        configurationDescription = "Phone",
        screenWidthPx = 1080,
        screenHeightPx = 2340,
      )
    val unknownLineLocation = VisualLintSourceLocationData(filePath = "MainActivity.kt")

    assertEquals(VisualLintSnapshotData.DEFAULT_DENSITY_DPI, snapshot.densityDpi)
    assertEquals(1.0f, snapshot.fontScale)
    assertTrue(snapshot.analyzerIssues.isEmpty())
    assertEquals(-1, unknownLineLocation.lineNumber)
  }
}
