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
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.ContextThemeWrapper
import android.view.View
import com.android.tools.ui.inspector.inspectors.view.property.PropertyCache
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
  fun testToWindowInfo() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    activity.setTheme(android.R.style.Theme_Material)
    val view = View(activity)
    val stringTable = StringTable()
    val window =
      ViewNodes.toWindowInfo(
        view,
        stringTable,
        false,
        false,
        PropertyCache.createViewPropertyCache(),
        PropertyCache.createLayoutParamsPropertyCache(),
      )

    val stringMap = stringTable.toStringEntries().associate { it.id to it.value }
    assertThat(window.hasRoot()).isTrue()
    assertThat(window.root.id).isEqualTo(view.uniqueDrawingId)
    assertThat(window.hasConfiguration()).isTrue()
    assertThat(window.configuration.density).isEqualTo(activity.resources.configuration.densityDpi)
    assertThat(stringMap[window.theme]).isEqualTo("@android:style/Theme.Material")
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun testBuildDisplayInfo() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val displays = ViewNodes.buildDisplayInfo(activity)

    assertThat(displays).isNotEmpty()
    val display = displays.first()
    val displayManager = activity.getSystemService(DisplayManager::class.java)
    val expectedDisplayId = displayManager?.displays?.firstOrNull()?.displayId
    assertThat(display.id).isEqualTo(expectedDisplayId)
    assertThat(display.widthPx).isGreaterThan(0)
    assertThat(display.heightPx).isGreaterThan(0)
    assertThat(display.hasOrientation()).isTrue()
    assertThat(display.orientation).isEqualTo(0)
  }

  @Test
  fun testToWindowInfo_usesEachRootContext() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val density160Context =
      ContextThemeWrapper(
        activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { densityDpi = 160 }),
        android.R.style.Theme_Material,
      )
    val density420Context =
      ContextThemeWrapper(
        activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { densityDpi = 420 }),
        android.R.style.Theme_Holo,
      )
    val stringTable = StringTable()

    val density160Window =
      ViewNodes.toWindowInfo(
        View(density160Context),
        stringTable,
        false,
        false,
        PropertyCache.createViewPropertyCache(),
        PropertyCache.createLayoutParamsPropertyCache(),
      )
    val density420Window =
      ViewNodes.toWindowInfo(
        View(density420Context),
        stringTable,
        false,
        false,
        PropertyCache.createViewPropertyCache(),
        PropertyCache.createLayoutParamsPropertyCache(),
      )

    val strings = stringTable.toStringEntries().associate { it.id to it.value }
    assertThat(density160Window.configuration.density).isEqualTo(160)
    assertThat(density420Window.configuration.density).isEqualTo(420)
    assertThat(strings[density160Window.theme]).isEqualTo("@android:style/Theme.Material")
    assertThat(strings[density420Window.theme]).isEqualTo("@android:style/Theme.Holo")
  }
}
