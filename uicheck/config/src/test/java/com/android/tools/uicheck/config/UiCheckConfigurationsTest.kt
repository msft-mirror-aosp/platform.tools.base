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

import com.android.tools.render.common.InvalidResourceScreenshot
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.uicheck.config.UiCheckPreset.DEFAULT
import com.android.tools.uicheck.config.UiCheckPreset.FULL
import com.android.tools.uicheck.config.UiCheckPreset.NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TABLET_SPEC = "spec:width=1280dp,height=800dp,dpi=240"

class UiCheckConfigurationsTest {

  /** Creates a base [ComposeScreenshot] for a Compose preview method named [name] with [previewParams]. */
  private fun preview(name: String, previewParams: Map<String, String> = emptyMap()) =
    ComposeScreenshot(
      methodFQN = "com.example.AppKt.$name",
      methodParams = emptyList(),
      previewParams = previewParams,
      previewId = name,
    )

  /** Returns [base] expanded for [preset]. */
  private fun plan(base: PreviewScreenshot, preset: UiCheckPreset): List<PlannedPreview> = UiCheckConfigurations.plan(listOf(base), preset)

  /** Returns the preview IDs of [base] expanded for [preset]. */
  private fun planIds(base: PreviewScreenshot, preset: UiCheckPreset): List<String> = plan(base, preset).map { it.screenshot.previewId }

  /** Verifies that [DEFAULT] renders tablet with system UI and 200% font scale. */
  @Test
  fun defaultRendersTabletAndLargestFontScale() {
    val planned = UiCheckConfigurations.plan(listOf(preview("Greeting")))

    assertEquals(listOf("Greeting", "Greeting_tablet", "Greeting_font200"), planned.map { it.screenshot.previewId })
    assertEquals(listOf("Base", "Medium Tablet", "Font scale 200%"), planned.map { it.label })
    assertEquals(mapOf("device" to TABLET_SPEC, "showSystemUi" to "true"), planned[1].screenshot.previewParams)
    assertEquals(mapOf("fontScale" to "2.0"), planned[2].screenshot.previewParams)
    assertEquals(planned, plan(preview("Greeting"), DEFAULT))

    val blank = preview("Blank", mapOf("device" to "", "fontScale" to " "))
    assertEquals(listOf("Blank", "Blank_tablet", "Blank_font200"), planIds(blank, DEFAULT))
  }

  /** Verifies that [DEFAULT] yields to the device and font scale of the base preview, while [FULL] still expands them. */
  @Test
  fun defaultYieldsToBaseDeviceAndFontScale() {
    val custom = preview("Custom", mapOf("device" to "id:small_phone", "fontScale" to "1.15"))

    assertEquals(listOf("Custom"), planIds(custom, DEFAULT))
    assertEquals("id:small_phone", plan(custom, DEFAULT).single().label)
    assertEquals(13, plan(custom, FULL).size)
  }

  /** Verifies that [NONE] plans only the base preview and [FULL] expands all variation groups. */
  @Test
  fun plansNoneAndFullPresets() {
    val greeting = preview("Greeting")
    assertEquals(listOf("Greeting"), planIds(greeting, NONE))

    val planned = plan(greeting, FULL)
    assertEquals(
      listOf(
        "Base",
        "Medium Phone",
        "Unfolded Foldable",
        "Medium Tablet",
        "Desktop",
        "Medium Phone-Landscape",
        "Font scale 85%",
        "Font scale 115%",
        "Font scale 130%",
        "Font scale 180%",
        "Font scale 200%",
        "Dark",
        "System UI",
      ),
      planned.map { it.label },
    )
    assertEquals("32", planned[11].screenshot.previewParams["uiMode"])
    assertEquals(mapOf("showSystemUi" to "true"), planned[12].screenshot.previewParams)
  }

  /** Returns the `fontScale` parameter of each font-scale variation of [base] for [preset]. */
  private fun fontScaleVariationParams(base: PreviewScreenshot, preset: UiCheckPreset): List<String?> =
    plan(base, preset).filter { it.label.startsWith("Font scale") }.map { it.screenshot.previewParams["fontScale"] }

  /**
   * Verifies font scales compared as numbers, so the scale of the base preview is not rendered twice, and invalid scales fall back to 1.0.
   */
  @Test
  fun skipsFontScaleOfBasePreview() {
    assertEquals(
      listOf("0.85", "1.15", "1.3", "1.8", "2.0"),
      fontScaleVariationParams(preview("Greeting"), FULL),
    )
    assertEquals(
      listOf("0.85", "1.0", "1.15", "1.3", "1.8"),
      fontScaleVariationParams(preview("Large", mapOf("fontScale" to "2")), FULL),
    )
    assertEquals(
      listOf("Font scale 85%", "Font scale 100%", "Font scale 115%", "Font scale 130%", "Font scale 180%", "Font scale 200%"),
      plan(preview("Custom", mapOf("fontScale" to "1.5")), FULL).map { it.label }.filter { it.startsWith("Font scale") },
    )
    for (invalid in listOf("abc", "0", "-1", "NaN", "Infinity")) {
      assertEquals(
        invalid,
        listOf("0.85", "1.15", "1.3", "1.8", "2.0"),
        fontScaleVariationParams(preview("Invalid", mapOf("fontScale" to invalid)), FULL),
      )
    }
  }

  /** Verifies that [FULL] renders the opposite theme of the base preview and keeps the device-type bits of its `uiMode`. */
  @Test
  fun rendersOppositeThemeKeepingUiModeTypeBits() {
    // UI_MODE_TYPE_TELEVISION (0x04) | UI_MODE_NIGHT_NO (0x10) = 20, and | UI_MODE_NIGHT_YES (0x20) = 36.
    val lightTv = plan(preview("Tv", mapOf("uiMode" to "20")), FULL).single { it.label == "Dark" }
    assertEquals("Tv_dark", lightTv.screenshot.previewId)
    assertEquals("36", lightTv.screenshot.previewParams["uiMode"])

    val darkTv = plan(preview("Tv", mapOf("uiMode" to "36")), FULL).single { it.label == "Light" }
    assertEquals("Tv_light", darkTv.screenshot.previewId)
    assertEquals("20", darkTv.screenshot.previewParams["uiMode"])

    val invalidUiMode = plan(preview("Invalid", mapOf("uiMode" to "night")), FULL).single { it.label == "Dark" }
    assertEquals("32", invalidUiMode.screenshot.previewParams["uiMode"])
  }

  /** Verifies that a variation is skipped only when the base preview already renders that device with system UI. */
  @Test
  fun skipsVariationsThatChangeNothing() {
    val tablet = preview("Tablet", mapOf("device" to TABLET_SPEC))
    val tabletVariation = plan(tablet, FULL).single { it.label == "Medium Tablet" }
    assertEquals("true", tabletVariation.screenshot.previewParams["showSystemUi"])

    val tabletWithSystemUi = preview("Tablet", mapOf("device" to TABLET_SPEC, "showSystemUi" to "TRUE"))
    val tabletWithSystemUiIds = planIds(tabletWithSystemUi, FULL)
    assertTrue("Tablet_tablet" !in tabletWithSystemUiIds)
    assertTrue("Tablet_system_ui" !in tabletWithSystemUiIds)

    val tabletWithDecoration = preview("Tablet", mapOf("device" to TABLET_SPEC, "showDecoration" to "true"))
    val tabletWithDecorationIds = planIds(tabletWithDecoration, FULL)
    assertTrue("Tablet_tablet" !in tabletWithDecorationIds)
    assertTrue("Tablet_system_ui" !in tabletWithDecorationIds)

    // A device alias renders at its own size, so the reference device is still rendered.
    val mediumPhone = preview("Phone", mapOf("device" to "id:medium_phone", "showSystemUi" to "true"))
    assertTrue("Phone_phone" in planIds(mediumPhone, FULL))
  }

  /** Verifies that screenshot types UI Check cannot copy are planned alone. */
  @Test
  fun plansUnsupportedScreenshotTypesAlone() {
    val invalid = InvalidResourceScreenshot(methodFQN = "R.layot.missing", previewId = "missing", reason = "Not found")
    assertEquals(listOf(PlannedPreview(invalid, "Base")), plan(invalid, FULL))
  }

  /** Verifies descriptions with the `@PreviewParameter` display name of a render. */
  @Test
  fun describesRenders() {
    val planned = PlannedPreview(preview("Greeting"), "Medium Tablet")
    assertEquals("Medium Tablet", planned.describe(displayName = null))
    assertEquals("Medium Tablet", planned.describe(displayName = " "))
    assertEquals("Medium Tablet [param 0]", planned.describe("param 0"))
  }
}
