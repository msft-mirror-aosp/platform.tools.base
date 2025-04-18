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

class AndroidApiLevelTest {
  @Test
  fun fromString() {
    assertThat(AndroidApiLevel.fromString("30")).isEqualTo(AndroidApiLevel(30))
    assertThat(AndroidApiLevel.fromString("30.1")).isEqualTo(AndroidApiLevel(30, 1))
    assertThat(AndroidApiLevel.fromString("30.100")).isEqualTo(AndroidApiLevel(30, 100))
  }

  @Test
  fun fromString_invalid() {
    assertThat(AndroidApiLevel.fromString("30a")).isNull()
    assertThat(AndroidApiLevel.fromString("30-ext5")).isNull()
    assertThat(AndroidApiLevel.fromString("30.100.1")).isNull()
    assertThat(AndroidApiLevel.fromString(" 30")).isNull()
    assertThat(AndroidApiLevel.fromString("30.1 ")).isNull()
  }
}
