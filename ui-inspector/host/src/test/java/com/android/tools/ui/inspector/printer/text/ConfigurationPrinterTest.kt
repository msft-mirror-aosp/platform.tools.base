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

package com.android.tools.ui.inspector.printer.text

import com.android.tools.ui.inspector.model.ColorModeHdr
import com.android.tools.ui.inspector.model.ColorModeWideGamut
import com.android.tools.ui.inspector.model.DeviceConfiguration
import com.android.tools.ui.inspector.model.DeviceLocale
import com.android.tools.ui.inspector.model.Dimension
import com.android.tools.ui.inspector.model.DisplayInfo
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
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Test

class ConfigurationPrinterTest {

  @Test
  fun testPrintDeviceConfiguration() {
    val config =
      DeviceConfiguration(
        density = Dimension.Dpi(420),
        screenWidthDp = Dimension.Dp(1080),
        screenHeightDp = Dimension.Dp(1920),
        smallestScreenWidthDp = Dimension.Dp(720),
        fontScale = 1.2f,
        orientation = Orientation.LANDSCAPE,
        screenLayoutSize = ScreenLayoutSize.LARGE,
        screenLayoutLong = ScreenLayoutLong.YES,
        layoutDirection = LayoutDirection.RTL,
        screenLayoutRound = ScreenLayoutRound.YES,
        colorModeWideGamut = ColorModeWideGamut.YES,
        colorModeHdr = ColorModeHdr.YES,
        touchScreen = TouchScreen.FINGER,
        keyboard = Keyboard.QWERTY,
        keyboardHidden = KeyboardHidden.NO,
        hardKeyboardHidden = HardKeyboardHidden.NO,
        navigation = Navigation.NONAV,
        navigationHidden = NavigationHidden.YES,
        uiModeType = UiModeType.NORMAL,
        uiModeNight = UiModeNight.YES,
        locale = DeviceLocale(language = "en", country = "US", variant = "variant", script = "Latn"),
        grammaticalGender = GrammaticalGender.FEMININE,
      )

    val output = captureOutput { printDeviceConfiguration(config, it) }

    val expectedOutput =
      """
Device Configuration:
 Color Mode Hdr: yes
 Color Mode Wide Gamut: yes
 Density: 420 dpi
 Font Scale: 1.2
 Grammatical Gender: feminine
 Hard Keyboard Hidden: no
 Keyboard: qwerty
 Keyboard Hidden: no
 Layout Direction: rtl
 Locale: en-US-variant-Latn
 Navigation: nonav
 Navigation Hidden: yes
 Orientation: landscape
 Screen Height Dp: 1920 dp
 Screen Layout Long: yes
 Screen Layout Round: yes
 Screen Layout Size: large
 Screen Width Dp: 1080 dp
 Smallest Screen Width Dp: 720 dp
 Touch Screen: finger
 Ui Mode Night: yes
 Ui Mode Type: normal
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expectedOutput.normalizeLineEndings())
  }

  @Test
  fun testPrintDeviceConfiguration_minimal() {
    val config = DeviceConfiguration(density = Dimension.Dpi(160), grammaticalGender = null)

    val output = captureOutput { printDeviceConfiguration(config, it) }

    val expectedOutput =
      """
Device Configuration:
 Density: 160 dpi
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expectedOutput.normalizeLineEndings())
  }

  @Test
  fun testPrintDisplays() {
    val output = captureOutput {
      printDisplays(
        listOf(
          DisplayInfo(id = 0, widthPx = 1080, heightPx = 1920, orientation = 90),
          DisplayInfo(id = 1, widthPx = 800, heightPx = 600, orientation = null),
        ),
        it,
      )
    }

    val expectedOutput =
      """
Displays:
 - Display 0: 1080x1920 px, rotation 90°
 - Display 1: 800x600 px
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expectedOutput.normalizeLineEndings())
  }

  @Test
  fun testPrintDisplays_empty() {
    assertThat(captureOutput { printDisplays(emptyList(), it) }).isEmpty()
  }

  @Test
  fun testPrintTheme() {
    assertThat(captureOutput { printTheme("@style/Theme.AppCompat", it) }).isEqualTo("Theme: @style/Theme.AppCompat")
  }

  @Test
  fun testFormatPropertyName() {
    assertThat(formatPropertyName("fontScale")).isEqualTo("Font Scale")
    assertThat(formatPropertyName("screenLayoutSize")).isEqualTo("Screen Layout Size")
    assertThat(formatPropertyName("smallestScreenWidthDp")).isEqualTo("Smallest Screen Width Dp")
    assertThat(formatPropertyName("uiModeNight")).isEqualTo("Ui Mode Night")
    assertThat(formatPropertyName("grammaticalGender")).isEqualTo("Grammatical Gender")
  }

  private fun captureOutput(action: (PrintStream) -> Unit): String {
    val outContent = ByteArrayOutputStream()
    action(PrintStream(outContent))
    return outContent.toString().trim()
  }

  private fun String.normalizeLineEndings(): String = this.replace("\r\n", "\n").replace('\r', '\n')
}
