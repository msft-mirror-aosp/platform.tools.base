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

import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.After
import org.junit.Before
import org.junit.Test

class ConfigurationTest {
  private val outContent = ByteArrayOutputStream()
  private val originalOut = System.out

  @Before
  fun setUpStreams() {
    System.setOut(PrintStream(outContent))
  }

  @After
  fun restoreStreams() {
    System.setOut(originalOut)
  }

  @Test
  fun testPrintDeviceConfiguration() {
    val stringTable = mapOf(1 to "en", 2 to "US", 3 to "variant", 4 to "Latn")

    val config =
      ViewInspectorProtocol.Configuration.newBuilder()
        .setDensity(420)
        .setScreenWidthDp(1080)
        .setScreenHeightDp(1920)
        .setSmallestScreenWidthDp(720)
        .setFontScale(1.2f)
        .setOrientation(ViewInspectorProtocol.Orientation.ORIENTATION_LANDSCAPE)
        .setScreenLayoutSize(ViewInspectorProtocol.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_LARGE)
        .setScreenLayoutLong(ViewInspectorProtocol.ScreenLayoutLong.SCREEN_LAYOUT_LONG_YES)
        .setLayoutDirection(ViewInspectorProtocol.LayoutDirection.LAYOUT_DIRECTION_RTL)
        .setScreenLayoutRound(ViewInspectorProtocol.ScreenLayoutRound.SCREEN_LAYOUT_ROUND_YES)
        .setColorModeWideGamut(ViewInspectorProtocol.ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_YES)
        .setColorModeHdr(ViewInspectorProtocol.ColorModeHdr.COLOR_MODE_HDR_YES)
        .setTouchScreen(ViewInspectorProtocol.TouchScreen.TOUCH_SCREEN_FINGER)
        .setKeyboard(ViewInspectorProtocol.Keyboard.KEYBOARD_QWERTY)
        .setKeyboardHidden(ViewInspectorProtocol.KeyboardHidden.KEYBOARD_HIDDEN_NO)
        .setHardKeyboardHidden(ViewInspectorProtocol.HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_NO)
        .setNavigation(ViewInspectorProtocol.Navigation.NAVIGATION_NONAV)
        .setNavigationHidden(ViewInspectorProtocol.NavigationHidden.NAVIGATION_HIDDEN_YES)
        .setUiModeType(ViewInspectorProtocol.UiModeType.UI_MODE_TYPE_NORMAL)
        .setUiModeNight(ViewInspectorProtocol.UiModeNight.UI_MODE_NIGHT_YES)
        .setLocale(ViewInspectorProtocol.Locale.newBuilder().setLanguage(1).setCountry(2).setVariant(3).setScript(4))
        .setGrammaticalGender(ViewInspectorProtocol.GrammaticalGender.GRAMMATICAL_GENDER_FEMININE)
        .build()

    printDeviceConfiguration(config, stringTable)

    val output = outContent.toString().trim()

    val expectedOutput =
      """
Device Configuration:
 Orientation: landscape
 Density: 420 dpi
 Screen Width: 1080 dp
 Screen Height: 1920 dp
 Smallest Screen Width: 720 dp
 Screen Size: large
 Screen Aspect: yes
 Layout Direction: rtl
 Screen Shape: yes
 Color Wide Gamut: yes
 Color HDR: yes
 Touchscreen: finger
 Keyboard: qwerty
 Keyboard Hidden: no
 Hard Keyboard Hidden: no
 Navigation: nonav
 Navigation Hidden: yes
 UI Mode Type: normal
 UI Mode Night: yes
 Locale: en-US-variant-Latn
 Font Scale: 1.2
 Grammatical Gender: feminine
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expectedOutput.normalizeLineEndings())
  }

  @Test
  fun testPrintDeviceConfiguration_minimal() {
    val config =
      ViewInspectorProtocol.Configuration.newBuilder()
        .setDensity(160)
        .setGrammaticalGender(ViewInspectorProtocol.GrammaticalGender.GRAMMATICAL_GENDER_UNDEFINED)
        .build()

    printDeviceConfiguration(config, emptyMap())

    val output = outContent.toString().trim()

    // Enums are set to default (0), which is typically undefined/unknown.
    // Locale is empty, so it's not printed.
    // Grammatical gender is undefined, so it's not printed.
    val expectedOutput =
      """
Device Configuration:
 Orientation: undefined
 Density: 160 dpi
 Screen Width: 0 dp
 Screen Height: 0 dp
 Smallest Screen Width: 0 dp
 Screen Size: undefined
 Screen Aspect: undefined
 Layout Direction: undefined
 Screen Shape: undefined
 Color Wide Gamut: undefined
 Color HDR: undefined
 Touchscreen: undefined
 Keyboard: undefined
 Keyboard Hidden: undefined
 Hard Keyboard Hidden: undefined
 Navigation: undefined
 Navigation Hidden: undefined
 UI Mode Type: undefined
 UI Mode Night: undefined
 Font Scale: 0.0
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expectedOutput.normalizeLineEndings())
  }

  private fun String.normalizeLineEndings(): String = this.replace("\r\n", "\n").replace('\r', '\n')
}
