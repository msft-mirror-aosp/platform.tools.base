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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleNodesTest {

  /** Verifies visibility filtering across zero-area bounds, visibility flags, and zero alpha modifiers. */
  @Test
  fun filtersInvisibleAndZeroSizedNodes() {
    val visible = node("Box", bounds(0, 0, 100, 100))
    val zeroWidth = node("Box", bounds(10, 10, 10, 100))
    val gone = node("Box", bounds(0, 0, 100, 100), semantics = mapOf("visibility" to "GONE"))
    val zeroAlpha = node("Box", bounds(0, 0, 100, 100), modifiers = mapOf("alpha" to "0.0"))

    assertTrue(visible.isVisible())
    assertFalse(zeroWidth.isVisible())
    assertFalse(gone.isVisible())
    assertFalse(zeroAlpha.isVisible())
  }

  /** Verifies that [visibleNodes] skips items scrolled outside a scrollable container's viewport. */
  @Test
  fun clipsScrollableChildrenAgainstViewport() {
    val visibleItem = node("Text", bounds(0, 100, 400, 200), semantics = mapOf("text" to "Item 1"))
    val scrolledOffItem = node("Text", bounds(0, 1200, 400, 1300), semantics = mapOf("text" to "Offscreen"))
    val lazyColumn =
      node(
        "LazyColumn",
        bounds(0, 0, 400, 1000),
        children = listOf(visibleItem, scrolledOffItem),
      )
    val root = node("Box", bounds(0, 0, 400, 1000), children = listOf(lazyColumn))

    val visited =
      context(root, screenWidthPx = 400, screenHeightPx = 1000).visibleNodes().map { it.semantics?.get("text") ?: it.name }.toList()

    assertEquals(listOf("Box", "LazyColumn", "Item 1"), visited)
  }
}
