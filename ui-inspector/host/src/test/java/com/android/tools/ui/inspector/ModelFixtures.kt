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

package com.android.tools.ui.inspector

import com.android.tools.ui.inspector.model.ColorModeHdr
import com.android.tools.ui.inspector.model.ColorModeWideGamut
import com.android.tools.ui.inspector.model.DeviceConfiguration
import com.android.tools.ui.inspector.model.DeviceLocale
import com.android.tools.ui.inspector.model.Dimension
import com.android.tools.ui.inspector.model.GrammaticalGender
import com.android.tools.ui.inspector.model.HardKeyboardHidden
import com.android.tools.ui.inspector.model.Keyboard
import com.android.tools.ui.inspector.model.KeyboardHidden
import com.android.tools.ui.inspector.model.LayoutDirection
import com.android.tools.ui.inspector.model.Navigation
import com.android.tools.ui.inspector.model.NavigationHidden
import com.android.tools.ui.inspector.model.Orientation
import com.android.tools.ui.inspector.model.ScreenLayoutLong
import com.android.tools.ui.inspector.model.ScreenLayoutRound
import com.android.tools.ui.inspector.model.ScreenLayoutSize
import com.android.tools.ui.inspector.model.TouchScreen
import com.android.tools.ui.inspector.model.UiModeNight
import com.android.tools.ui.inspector.model.UiModeType
import com.android.tools.ui.inspector.model.UiNode

/*
 * Test fixtures for the model. The model's constructors take every field, so that each producer states what it knows. A test states only
 * the fields it is about; these fixtures fill in every other field as absent.
 */

/** A [DeviceConfiguration] with the given facts and every other one absent. */
fun configuration(
  fontScale: Float? = null,
  countryCode: Int? = null,
  networkCode: Int? = null,
  locale: DeviceLocale? = null,
  screenLayoutSize: ScreenLayoutSize? = null,
  screenLayoutLong: ScreenLayoutLong? = null,
  layoutDirection: LayoutDirection? = null,
  screenLayoutRound: ScreenLayoutRound? = null,
  colorModeWideGamut: ColorModeWideGamut? = null,
  colorModeHdr: ColorModeHdr? = null,
  touchScreen: TouchScreen? = null,
  keyboard: Keyboard? = null,
  keyboardHidden: KeyboardHidden? = null,
  hardKeyboardHidden: HardKeyboardHidden? = null,
  navigation: Navigation? = null,
  navigationHidden: NavigationHidden? = null,
  uiModeType: UiModeType? = null,
  uiModeNight: UiModeNight? = null,
  smallestScreenWidthDp: Dimension.Dp? = null,
  density: Dimension.Dpi? = null,
  orientation: Orientation? = null,
  screenWidthDp: Dimension.Dp? = null,
  screenHeightDp: Dimension.Dp? = null,
  grammaticalGender: GrammaticalGender? = null,
): DeviceConfiguration =
  DeviceConfiguration(
    fontScale = fontScale,
    countryCode = countryCode,
    networkCode = networkCode,
    locale = locale,
    screenLayoutSize = screenLayoutSize,
    screenLayoutLong = screenLayoutLong,
    layoutDirection = layoutDirection,
    screenLayoutRound = screenLayoutRound,
    colorModeWideGamut = colorModeWideGamut,
    colorModeHdr = colorModeHdr,
    touchScreen = touchScreen,
    keyboard = keyboard,
    keyboardHidden = keyboardHidden,
    hardKeyboardHidden = hardKeyboardHidden,
    navigation = navigation,
    navigationHidden = navigationHidden,
    uiModeType = uiModeType,
    uiModeNight = uiModeNight,
    smallestScreenWidthDp = smallestScreenWidthDp,
    density = density,
    orientation = orientation,
    screenWidthDp = screenWidthDp,
    screenHeightDp = screenHeightDp,
    grammaticalGender = grammaticalGender,
  )

/** A View attribute without resolution provenance unless given. */
fun attribute(
  name: String,
  value: UiNode.AttributeValue,
  directSource: String? = null,
  styleChain: List<String> = emptyList(),
): UiNode.Attribute = UiNode.Attribute(name = name, value = value, directSource = directSource, styleChain = styleChain)

/** A dimension attribute value in pixels, unresolved to dp/sp unless given. */
fun dimension(value: Float, dp: Float? = null, sp: Float? = null): UiNode.AttributeValue.DimensionVal =
  UiNode.AttributeValue.DimensionVal(value = value, dp = dp, sp = sp)
