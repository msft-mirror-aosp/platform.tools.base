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
import com.google.protobuf.Descriptors
import org.junit.Test

class ConfigurationDiffTest {

  @Test
  fun testDiffConfigurations_noChanges() {
    val config = ViewInspectorProtocol.Configuration.newBuilder().setDensity(160).build()
    val diff = createConfigurationDiff(config, config, emptyMap(), emptyMap())
    assertThat(diff).isNull()
  }

  @Test
  fun testDiffConfigurations_nullInputs() {
    val config = ViewInspectorProtocol.Configuration.newBuilder().setDensity(160).build()
    assertThat(createConfigurationDiff(null, config, emptyMap(), emptyMap())).isNull()
    assertThat(createConfigurationDiff(config, null, emptyMap(), emptyMap())).isNull()
    assertThat(createConfigurationDiff(null, null, emptyMap(), emptyMap())).isNull()
  }

  @Test
  fun testDiffConfigurations_unsetVsDefault() {
    val emptyConfig = ViewInspectorProtocol.Configuration.getDefaultInstance()
    val configWithDefaults =
      ViewInspectorProtocol.Configuration.newBuilder()
        .setDensity(0)
        .setFontScale(0.0f)
        .setOrientation(ViewInspectorProtocol.Orientation.ORIENTATION_UNDEFINED)
        .build()

    val diff = createConfigurationDiff(emptyConfig, configWithDefaults, emptyMap(), emptyMap())
    assertThat(diff).isNull()
  }

  @Test
  fun testDiffConfigurations_withChanges() {
    val stringTable = mapOf(1 to "en", 2 to "US")
    val oldConfig =
      ViewInspectorProtocol.Configuration.newBuilder()
        .setDensity(160)
        .setOrientation(ViewInspectorProtocol.Orientation.ORIENTATION_PORTRAIT)
        .setUiModeNight(ViewInspectorProtocol.UiModeNight.UI_MODE_NIGHT_NO)
        .setLocale(ViewInspectorProtocol.Locale.newBuilder().setLanguage(1))
        .build()

    val newConfig =
      ViewInspectorProtocol.Configuration.newBuilder()
        .setDensity(240)
        .setOrientation(ViewInspectorProtocol.Orientation.ORIENTATION_LANDSCAPE)
        .setUiModeNight(ViewInspectorProtocol.UiModeNight.UI_MODE_NIGHT_YES)
        .setLocale(ViewInspectorProtocol.Locale.newBuilder().setLanguage(1).setCountry(2))
        .build()

    val diff = createConfigurationDiff(oldConfig, newConfig, stringTable, stringTable)
    assertThat(diff).isNotNull()
    val diffs = diff!!.differences
    assertThat(diffs).hasSize(4)

    val orientationField = ViewInspectorProtocol.Configuration.getDescriptor().findFieldByName("orientation")
    val densityField = ViewInspectorProtocol.Configuration.getDescriptor().findFieldByName("density")
    val uiModeNightField = ViewInspectorProtocol.Configuration.getDescriptor().findFieldByName("ui_mode_night")
    val localeField = ViewInspectorProtocol.Configuration.getDescriptor().findFieldByName("locale")

    val orientationDiff = diffs.first { it.field == orientationField }
    assertThat((orientationDiff.oldValue as Descriptors.EnumValueDescriptor).name).isEqualTo("ORIENTATION_PORTRAIT")
    assertThat((orientationDiff.newValue as Descriptors.EnumValueDescriptor).name).isEqualTo("ORIENTATION_LANDSCAPE")

    val densityDiff = diffs.first { it.field == densityField }
    assertThat(densityDiff.oldValue).isEqualTo(160)
    assertThat(densityDiff.newValue).isEqualTo(240)

    val uiModeNightDiff = diffs.first { it.field == uiModeNightField }
    assertThat((uiModeNightDiff.oldValue as Descriptors.EnumValueDescriptor).name).isEqualTo("UI_MODE_NIGHT_NO")
    assertThat((uiModeNightDiff.newValue as Descriptors.EnumValueDescriptor).name).isEqualTo("UI_MODE_NIGHT_YES")

    val localeDiff = diffs.first { it.field == localeField }
    assertThat((localeDiff.oldValue as ViewInspectorProtocol.Locale).language).isEqualTo(1)
    assertThat((localeDiff.newValue as ViewInspectorProtocol.Locale).country).isEqualTo(2)
  }
}
