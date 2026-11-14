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

package com.android.tools.render

import com.android.tools.render.Renderer.Companion.sanitizeBaseResultId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SanitizeBaseResultIdTest {

  @Test
  fun testClassPrefixIsRemoved() {
    assertEquals(
      "myPreview_Phone_1a2b3c4d",
      sanitizeBaseResultId("pkg.name.MyTestKt.myPreview_Phone_1a2b3c4d", "pkg.name.MyTestKt.myPreview"),
    )
  }

  @Test
  fun testPreviewNameWithDotsKeepsTheMethodName() {
    assertEquals(
      "myPreview_Devices.TV_1080p_68b37a47",
      sanitizeBaseResultId("pkg.name.MyTestKt.myPreview_Devices.TV_1080p_68b37a47", "pkg.name.MyTestKt.myPreview"),
    )
  }

  @Test
  fun testSamePreviewNameWithDotsOnTwoMethodsGivesDifferentIds() {
    val first = sanitizeBaseResultId("pkg.MyTestKt.first_Devices.TV_1080p_68b37a47", "pkg.MyTestKt.first")
    val second = sanitizeBaseResultId("pkg.MyTestKt.second_Devices.TV_1080p_68b37a47", "pkg.MyTestKt.second")

    assertNotEquals(first, second)
  }

  @Test
  fun testPreviewWithoutName() {
    assertEquals("myPreview_1a2b3c4d", sanitizeBaseResultId("pkg.MyTestKt.myPreview_1a2b3c4d", "pkg.MyTestKt.myPreview"))
  }

  @Test
  fun testPreviewIdEqualToMethodFqn() {
    assertEquals("BrokenPreview", sanitizeBaseResultId("com.example.BrokenKt.BrokenPreview", "com.example.BrokenKt.BrokenPreview"))
  }

  @Test
  fun testNestedClass() {
    assertEquals(
      "myPreview_a.b_1a2b3c4d",
      sanitizeBaseResultId("pkg.Outer\$Inner.myPreview_a.b_1a2b3c4d", "pkg.Outer\$Inner.myPreview"),
    )
  }

  @Test
  fun testInvalidPathCharactersAreReplaced() {
    assertEquals(
      "myPreview_simple tile_a_b_7cfb9daa",
      sanitizeBaseResultId("pkg.TileKt.myPreview_simple tile_a/b_7cfb9daa", "pkg.TileKt.myPreview"),
    )
  }

  @Test
  fun testPreviewIdWithoutTheClassPrefixKeepsItsLastSegment() {
    // Resource targets pass the resource name, and other callers may pass any id.
    assertEquals("activity_main", sanitizeBaseResultId("activity_main", "R.layout.activity_main"))
    assertEquals("JumpToBottomPreview", sanitizeBaseResultId("com.example.JumpToBottomPreview", "com.example.AnimKt.JumpToBottomPreview"))
    assertEquals("custom_preview_id", sanitizeBaseResultId("custom_preview_id", "pkg.MyTestKt.myPreview"))
    assertEquals("myPreview", sanitizeBaseResultId("myPreview", "myPreview"))
  }

  @Test
  fun testEmptyIdsFallBackToPreview() {
    assertEquals("preview", sanitizeBaseResultId("", "pkg.MyTestKt.myPreview"))
    assertEquals("preview", sanitizeBaseResultId("pkg.MyTestKt.", "pkg.MyTestKt.myPreview"))
    assertEquals("preview", sanitizeBaseResultId("pkg.MyTestKt...", "pkg.MyTestKt.myPreview"))
  }
}
