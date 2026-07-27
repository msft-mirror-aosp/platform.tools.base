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

import android.content.res.Configuration as AndroidResConfiguration
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ConfigurationProtoConverterTest {

  @Test
  @Config(sdk = [23])
  fun testConvert_nullLocale_belowApi24_doesNotCrash() {
    val config = AndroidResConfiguration()
    @Suppress("deprecation")
    config.locale = null
    val stringTable = StringTable()

    val proto = ConfigurationProtoConverter.convert(config, stringTable)

    assertThat(proto.hasLocale()).isFalse()
  }

  @Test
  @Config(sdk = [23])
  fun testConvert_withLocale_belowApi24() {
    val config = AndroidResConfiguration()
    @Suppress("deprecation")
    config.locale = Locale.US
    val stringTable = StringTable()

    val proto = ConfigurationProtoConverter.convert(config, stringTable)

    assertThat(proto.hasLocale()).isTrue()
    assertThat(stringTable.getString(proto.locale.language)).isEqualTo("en")
    assertThat(stringTable.getString(proto.locale.country)).isEqualTo("US")
  }

  @Test
  @Config(sdk = [29])
  fun testConvert_nullOrEmptyLocale_api24AndAbove_doesNotCrash() {
    val config = AndroidResConfiguration()
    val stringTable = StringTable()

    val proto = ConfigurationProtoConverter.convert(config, stringTable)

    assertThat(proto.hasLocale()).isFalse()
  }
}
