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
import org.junit.Test

class DimensionsTest {

  /** Verifies nearest-integer rounding in [pxToDp], fractional conversion in [dpToPx], and non-positive density guards. */
  @Test
  fun convertsBetweenPxAndDpWithSafeDensity() {
    // 125px at 420dpi is 47.619dp and rounds to 48dp rather than truncating to 47dp.
    assertEquals(48, 125.pxToDp(420))
    assertEquals(48, 126.pxToDp(420))
    assertEquals(320, 480.pxToDp(240))
    assertEquals(321, 481.pxToDp(240))
    assertEquals(601, 1201.pxToDp(320))
    assertEquals(160, 1.pxToDp(0))
    assertEquals(30.0f, 20.0f.dpToPx(240), 0.001f)
    assertEquals(0.125f, 20.0f.dpToPx(0), 0.001f)
  }
}
