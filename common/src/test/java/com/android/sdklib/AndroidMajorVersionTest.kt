/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.sdklib

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AndroidMajorVersionTest {
  @Test
  fun apiString() {
    assertThat(AndroidMajorVersion(34).apiString).isEqualTo("34")
    assertThat(AndroidMajorVersion(34, "VanillaIceCream").apiString).isEqualTo("VanillaIceCream")
  }

  @Test
  fun order() {
    assertThat(AndroidMajorVersion(34)).isLessThan(AndroidMajorVersion(35))
    assertThat(AndroidMajorVersion(34)).isLessThan(AndroidMajorVersion(34, "VanillaIceCream"))
    assertThat(AndroidMajorVersion(34, "Codename"))
      .isLessThan(AndroidMajorVersion(34, "VanillaIceCream"))
  }

  @Test
  fun secondaryConstructor() {
    assertThat(AndroidMajorVersion(AndroidApiLevel(34)).apiString).isEqualTo("34")
    assertThat(AndroidMajorVersion(AndroidApiLevel(36, 1)).apiString).isEqualTo("36")
  }
}
