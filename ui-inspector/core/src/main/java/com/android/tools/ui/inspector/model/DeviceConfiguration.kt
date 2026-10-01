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

package com.android.tools.ui.inspector.model

/** Represents device locale with resolved language, country, variant, and script strings. */
data class DeviceLocale(val language: String?, val country: String?, val variant: String?, val script: String?)

/** Screen orientation qualifier (e.g. portrait, landscape, square). */
enum class Orientation {
  PORTRAIT,
  LANDSCAPE,
  SQUARE,
}

/** Screen size layout qualifier (e.g. small, normal, large, xlarge). */
enum class ScreenLayoutSize {
  SMALL,
  NORMAL,
  LARGE,
  XLARGE,
}

/** Aspect ratio / screen longness layout qualifier (e.g. long for widescreen/tall displays vs not long). */
enum class ScreenLayoutLong {
  NO,
  YES,
}

/** Layout direction qualifier (e.g. LTR or RTL). */
enum class LayoutDirection {
  LTR,
  RTL,
}

/** Screen shape qualifier (e.g. round vs not round). */
enum class ScreenLayoutRound {
  NO,
  YES,
}

/** Wide color gamut capability qualifier. */
enum class ColorModeWideGamut {
  NO,
  YES,
}

/** High dynamic range (HDR) capability qualifier. */
enum class ColorModeHdr {
  NO,
  YES,
}

/** Primary touchscreen input type qualifier. */
enum class TouchScreen {
  NOTOUCH,
  STYLUS,
  FINGER,
}

/** Primary text input device / keyboard type qualifier. */
enum class Keyboard {
  NOKEYS,
  QWERTY,
  KEY_12,
}

/** State of the primary text keyboard (hidden vs visible). */
enum class KeyboardHidden {
  NO,
  YES,
}

/** State of the hardware keyboard (hidden vs visible). */
enum class HardKeyboardHidden {
  NO,
  YES,
}

/** Primary navigation method qualifier (e.g. dpad, trackball, wheel, nonav). */
enum class Navigation {
  NONAV,
  DPAD,
  TRACKBALL,
  WHEEL,
}

/** State of the navigation controller (hidden vs visible). */
enum class NavigationHidden {
  NO,
  YES,
}

/** UI mode type qualifier (e.g. normal, desk, car, television, watch, vr). */
enum class UiModeType {
  NORMAL,
  DESK,
  CAR,
  TELEVISION,
  APPLIANCE,
  WATCH,
  VR_HEADSET,
}

/** Night mode qualifier (yes vs no). */
enum class UiModeNight {
  NO,
  YES,
}

/** Grammatical gender system preference (neutral, feminine, masculine). */
enum class GrammaticalGender {
  NEUTRAL,
  FEMININE,
  MASCULINE,
}

/** Dimension qualifiers (e.g. Dp, Dpi). */
sealed class Dimension(open val value: Int) {
  data class Dp(override val value: Int) : Dimension(value)

  data class Dpi(override val value: Int) : Dimension(value)
}

/** Represents host-side device configuration. */
data class DeviceConfiguration(
  val fontScale: Float?,
  val countryCode: Int?,
  val networkCode: Int?,
  val locale: DeviceLocale?,
  val screenLayoutSize: ScreenLayoutSize?,
  val screenLayoutLong: ScreenLayoutLong?,
  val layoutDirection: LayoutDirection?,
  val screenLayoutRound: ScreenLayoutRound?,
  val colorModeWideGamut: ColorModeWideGamut?,
  val colorModeHdr: ColorModeHdr?,
  val touchScreen: TouchScreen?,
  val keyboard: Keyboard?,
  val keyboardHidden: KeyboardHidden?,
  val hardKeyboardHidden: HardKeyboardHidden?,
  val navigation: Navigation?,
  val navigationHidden: NavigationHidden?,
  val uiModeType: UiModeType?,
  val uiModeNight: UiModeNight?,
  val smallestScreenWidthDp: Dimension.Dp?,
  val density: Dimension.Dpi?,
  val orientation: Orientation?,
  val screenWidthDp: Dimension.Dp?,
  val screenHeightDp: Dimension.Dp?,
  val grammaticalGender: GrammaticalGender?,
)
