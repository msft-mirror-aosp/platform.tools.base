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
package com.android.sdklib.deviceprovisioner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceIdTest {
  @Test
  fun validNonTemplate() {
    val id = checkNotNull(DeviceId.fromString("a::c:d"))
    assertThat(id.pluginId).isEqualTo("a")
    assertThat(id.isTemplate).isFalse()
    assertThat(id.identifier).isEqualTo("c:d")
  }

  @Test
  fun validTemplate() {
    val id = checkNotNull(DeviceId.fromString("a:template::c d"))
    assertThat(id.pluginId).isEqualTo("a")
    assertThat(id.isTemplate).isTrue()
    assertThat(id.identifier).isEqualTo(":c d")
  }

  @Test
  fun invalid() {
    assertThat(DeviceId.fromString("a:b")).isNull()
  }
}
