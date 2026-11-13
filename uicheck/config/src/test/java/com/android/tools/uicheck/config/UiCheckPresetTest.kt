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

package com.android.tools.uicheck.config

import com.android.tools.uicheck.config.UiCheckPreset.DEFAULT
import com.android.tools.uicheck.config.UiCheckPreset.FULL
import com.android.tools.uicheck.config.UiCheckPreset.NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UiCheckPresetTest {

  /** Verifies that blank values fall back to [DEFAULT] and known preset tokens parse in any case with surrounding whitespace. */
  @Test
  fun parsesKnownAndBlankPresets() {
    assertEquals(DEFAULT, UiCheckPreset.parse(""))
    assertEquals(DEFAULT, UiCheckPreset.parse("   "))
    assertEquals(DEFAULT, UiCheckPreset.parse("default"))
    assertEquals(FULL, UiCheckPreset.parse(" FULL "))
    assertEquals(NONE, UiCheckPreset.parse("None"))
  }

  /** Verifies that unknown tokens, including removed group-level tokens, are rejected. */
  @Test
  fun rejectsUnknownPresets() {
    for (token in listOf("window_sizes", "font_scales", "light_dark", "system_ui", "default,full", "typo")) {
      assertThrows(token, IllegalArgumentException::class.java) { UiCheckPreset.parse(token) }
    }
  }
}
