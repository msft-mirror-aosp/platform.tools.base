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

package com.android.tools.ui.inspector.inspectors.view

import android.app.Activity
import android.hardware.display.DisplayManager
import android.view.View
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ViewNodesTest {

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun testCreateAppContext() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    activity.setTheme(android.R.style.Theme_Material)
    val view = View(activity)
    val stringTable = StringTable()
    val appContext = ViewNodes.createAppContext(view, stringTable)

    // Verify theme resolved
    val stringMap = stringTable.toStringEntries().associate { it.id to it.value }
    val themeStr = stringMap[appContext.theme]
    assertThat(themeStr).isEqualTo("@android:style/Theme.Material")

    // Verify display info
    assertThat(appContext.displayInfoCount).isAtLeast(1)
    val display = appContext.getDisplayInfo(0)
    val displayManager = activity.getSystemService(DisplayManager::class.java)
    val expectedDisplayId = displayManager?.displays?.firstOrNull()?.displayId
    assertThat(display.id).isEqualTo(expectedDisplayId)
    assertThat(display.widthPx).isGreaterThan(0)
    assertThat(display.heightPx).isGreaterThan(0)
    assertThat(display.orientation).isEqualTo(0) // ROTATION_0
  }
}
