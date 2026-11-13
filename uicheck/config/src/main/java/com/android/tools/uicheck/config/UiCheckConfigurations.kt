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

import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.uicheck.snapshot.UiCheckSnapshot.Companion.DEFAULT_FONT_SCALE
import kotlin.math.roundToInt

/**
 * Expands `@Preview`s across UI Check variations, like Android Studio's `UiCheckModeFilter`, so validators can check each preview on other
 * screen sizes, font scales, and themes.
 *
 * Unlike Android Studio, the variations are selected by [UiCheckPreset], a variation that would render the same as the base preview is
 * skipped, and system UI adds a single `showSystemUi` render instead of two navigation and cutout variants.
 */
object UiCheckConfigurations {

  /**
   * Returns each of [baseScreenshots] followed by its variations for [preset]. Screenshots that are not Compose previews are returned
   * without variations.
   */
  fun plan(
    baseScreenshots: List<PreviewScreenshot>,
    preset: UiCheckPreset = UiCheckPreset.DEFAULT,
  ): List<PlannedPreview> = baseScreenshots.flatMap { expandPreviews(it, preset) }
}

/** Returns [base] followed by its variations for [preset]. Non-Compose previews return [base] alone. */
private fun expandPreviews(base: PreviewScreenshot, preset: UiCheckPreset): List<PlannedPreview> {
  val basePreview = PlannedPreview(base, base.previewParams[PARAM_DEVICE]?.takeIf { it.isNotBlank() } ?: "Base")
  if (base !is ComposeScreenshot) return listOf(basePreview)

  val variations =
    when (preset) {
      UiCheckPreset.NONE -> emptyList()
      UiCheckPreset.DEFAULT -> expandDefaultPreviews(base)
      UiCheckPreset.FULL -> expandFullPreviews(base)
    }
  return listOf(basePreview) + variations
}

/**
 * Expands [base] into all [UiCheckPreset.FULL] variations (window sizes, font scales, opposite theme, and system UI), skipping any
 * variation that matches [base]'s existing configuration.
 */
private fun expandFullPreviews(base: ComposeScreenshot): List<PlannedPreview> {
  val params = base.previewParams
  val showsSystemUi =
    params[PARAM_SHOW_SYSTEM_UI].equals("true", ignoreCase = true) || params[PARAM_SHOW_DECORATION].equals("true", ignoreCase = true)
  val currentFontScale = params[PARAM_FONT_SCALE]?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_FONT_SCALE
  val uiMode = params[PARAM_UI_MODE]?.toIntOrNull() ?: 0
  val isNight = (uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES
  val flipped = (uiMode and UI_MODE_NIGHT_MASK.inv()) or (if (isNight) UI_MODE_NIGHT_NO else UI_MODE_NIGHT_YES)
  val (themeKey, themeLabel) = if (isNight) "light" to "Light" else "dark" to "Dark"

  return buildList {
    WINDOW_SIZE_DEVICES.filterNot { params[PARAM_DEVICE] == it.spec && showsSystemUi }.forEach { add(base.withDevice(it)) }
    FONT_SCALES.filter { it != currentFontScale }.forEach { add(base.withFontScale(it)) }
    add(base.withVariation(themeKey, themeLabel, mapOf(PARAM_UI_MODE to flipped.toString())))
    if (!showsSystemUi) add(base.withVariation("system_ui", "System UI", mapOf(PARAM_SHOW_SYSTEM_UI to "true")))
  }
}

/**
 * Expands [base] into [UiCheckPreset.DEFAULT] variations (Medium Tablet and 200% font scale), skipping each when [base] already sets its
 * own `device` or `fontScale`.
 */
private fun expandDefaultPreviews(base: ComposeScreenshot): List<PlannedPreview> = buildList {
  if (base.previewParams[PARAM_DEVICE].isNullOrBlank()) add(base.withDevice(TABLET))
  if (base.previewParams[PARAM_FONT_SCALE].isNullOrBlank()) add(base.withFontScale(FONT_SCALES.last()))
}

/** Returns a [PlannedPreview] rendering this screenshot on [device] with system UI enabled. */
private fun ComposeScreenshot.withDevice(device: DevicePreset): PlannedPreview =
  withVariation(device.idSuffix, device.label, mapOf(PARAM_DEVICE to device.spec, PARAM_SHOW_SYSTEM_UI to "true"))

/** Returns a [PlannedPreview] rendering this screenshot at font [scale] (e.g., `"font130"`, `"Font scale 130%"`). */
private fun ComposeScreenshot.withFontScale(scale: Float): PlannedPreview {
  val percent = (scale * 100).roundToInt()
  return withVariation("font$percent", "Font scale $percent%", mapOf(PARAM_FONT_SCALE to scale.toString()))
}

/** Returns a [PlannedPreview] copying this screenshot with [overrides] applied and [idSuffix] appended to its preview ID. */
private fun ComposeScreenshot.withVariation(
  idSuffix: String,
  label: String,
  overrides: Map<String, String>,
): PlannedPreview = PlannedPreview(copy(previewParams = previewParams + overrides, previewId = "${previewId}_$idSuffix"), label)

// `@Preview` parameter keys.
private const val PARAM_DEVICE = "device"
private const val PARAM_FONT_SCALE = "fontScale"
private const val PARAM_SHOW_SYSTEM_UI = "showSystemUi"
private const val PARAM_SHOW_DECORATION = "showDecoration"
private const val PARAM_UI_MODE = "uiMode"

// `android.content.res.Configuration.uiMode` night-mode bitmask and values.
private const val UI_MODE_NIGHT_MASK = 0x30
private const val UI_MODE_NIGHT_NO = 0x10
private const val UI_MODE_NIGHT_YES = 0x20

// UI Check font-scale and reference-device presets (in Android Studio UI Check order).
private val FONT_SCALES = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.8f, 2.0f)
private val TABLET = DevicePreset("tablet", "Medium Tablet", "spec:width=1280dp,height=800dp,dpi=240")
private val WINDOW_SIZE_DEVICES =
  listOf(
    DevicePreset("phone", "Medium Phone", "spec:width=411dp,height=891dp"),
    DevicePreset("foldable", "Unfolded Foldable", "spec:width=673dp,height=841dp"),
    TABLET,
    DevicePreset("desktop", "Desktop", "spec:width=1920dp,height=1080dp,dpi=160"),
    DevicePreset("landscape", "Medium Phone-Landscape", "spec:width=891dp,height=411dp"),
  )

/** Reference device definition for UI Check window-size variations. */
private data class DevicePreset(val idSuffix: String, val label: String, val spec: String)
