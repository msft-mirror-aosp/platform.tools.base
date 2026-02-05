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
package com.android.sdklib.internal.avd

import com.android.sdklib.AndroidVersion
import com.android.sdklib.SystemImageTags
import com.google.common.truth.Truth.assertThat
import java.nio.file.Paths
import org.junit.Test

class AvdInfoTest {
  @Test
  fun testEmptyPropertiesBehavior() {
    val iniFile = Paths.get("my_device.ini")
    val folder = Paths.get("my_device.avd")
    val avd = AvdInfo(iniFile, folder, null, emptyMap())

    assertThat(avd.abiType).isNull()
    assertThat(avd.displayName).isEqualTo("my device")
    assertThat(avd.tag).isEqualTo(SystemImageTags.DEFAULT_TAG)
    assertThat(avd.hasPlayStore()).isFalse()
    assertThat(avd.androidVersion).isEqualTo(AndroidVersion.DEFAULT)
    assertThat(avd.deviceManufacturer).isEmpty()
    assertThat(avd.deviceName).isEmpty()
  }
}
