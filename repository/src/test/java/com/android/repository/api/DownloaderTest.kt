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
package com.android.repository.api

import com.android.repository.testframework.FakeProgressIndicator
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

class DownloaderTest {
  @Test
  fun hashProgress() {
    val fractions = mutableListOf<Double>()
    val progress =
      object : FakeProgressIndicator(true) {
        override fun setFraction(v: Double) {
          super.setFraction(v)
          fractions.add(v)
        }
      }
    val input = ByteArrayInputStream(ByteArray(20000))

    val hashResult = Downloader.hash(input, 20000, "sha-256", progress)

    assertThat(fractions).containsNoDuplicates()
    assertThat(fractions.size).isAtLeast(4) // based on buffer size of 5120
    assertThat(fractions.last()).isEqualTo(1.0)
    assertThat(hashResult).isEqualTo("28b4f41a7f3ee6d8cc87272db6e09c6d3566551fd4d18702b041a21658272a85")
  }
}
