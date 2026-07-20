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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConfigurationDiffTest {

  @Test
  fun testDiffConfigurations_noChanges() {
    val config = DeviceConfiguration(density = 160)
    val diff = createConfigurationDiff(config, config)
    assertThat(diff).isNull()
  }

  @Test
  fun testDiffConfigurations_nullInputs() {
    val config = DeviceConfiguration(density = 160)
    assertThat(createConfigurationDiff(null, config)).isNull()
    assertThat(createConfigurationDiff(config, null)).isNull()
    assertThat(createConfigurationDiff(null, null)).isNull()
  }

  @Test
  fun testDiffConfigurations_unsetVsDefault() {
    val emptyConfig = DeviceConfiguration()
    val configWithDefaults = DeviceConfiguration(density = null, fontScale = null, orientation = null)

    val diff = createConfigurationDiff(emptyConfig, configWithDefaults)
    assertThat(diff).isNull()
  }

  @Test
  fun testDiffConfigurations_withChanges() {
    val oldConfig =
      DeviceConfiguration(
        density = 160,
        orientation = Orientation.PORTRAIT,
        uiModeNight = UiModeNight.NO,
        locale = DeviceLocale(language = "en", country = null, variant = null, script = null),
      )

    val newConfig =
      DeviceConfiguration(
        density = 240,
        orientation = Orientation.LANDSCAPE,
        uiModeNight = UiModeNight.YES,
        locale = DeviceLocale(language = "en", country = "US", variant = null, script = null),
      )

    val diff = createConfigurationDiff(oldConfig, newConfig)
    assertThat(diff).isNotNull()
    val diffs = diff!!.differences
    assertThat(diffs).hasSize(4)

    val orientationDiff = diffs.first { it.name == "orientation" }
    assertThat(orientationDiff.oldValue).isEqualTo(Orientation.PORTRAIT)
    assertThat(orientationDiff.newValue).isEqualTo(Orientation.LANDSCAPE)

    val densityDiff = diffs.first { it.name == "density" }
    assertThat(densityDiff.oldValue).isEqualTo(160)
    assertThat(densityDiff.newValue).isEqualTo(240)

    val uiModeNightDiff = diffs.first { it.name == "uiModeNight" }
    assertThat(uiModeNightDiff.oldValue).isEqualTo(UiModeNight.NO)
    assertThat(uiModeNightDiff.newValue).isEqualTo(UiModeNight.YES)

    val localeDiff = diffs.first { it.name == "locale" }
    assertThat((localeDiff.oldValue as DeviceLocale).language).isEqualTo("en")
    assertThat((localeDiff.newValue as DeviceLocale).country).isEqualTo("US")
  }

  @Test
  fun testRawPropertyNamesInDifferences() {
    val oldConfig =
      DeviceConfiguration(
        fontScale = 1.0f,
        screenLayoutSize = ScreenLayoutSize.NORMAL,
        smallestScreenWidthDp = 320,
        uiModeNight = UiModeNight.NO,
        grammaticalGender = GrammaticalGender.NEUTRAL,
      )
    val newConfig =
      DeviceConfiguration(
        fontScale = 1.2f,
        screenLayoutSize = ScreenLayoutSize.LARGE,
        smallestScreenWidthDp = 600,
        uiModeNight = UiModeNight.YES,
        grammaticalGender = GrammaticalGender.FEMININE,
      )

    val diff = createConfigurationDiff(oldConfig, newConfig)!!
    val fieldNames = diff.differences.map { it.name }

    assertThat(fieldNames).containsExactly("fontScale", "screenLayoutSize", "smallestScreenWidthDp", "uiModeNight", "grammaticalGender")
  }
}
