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

package com.android.build.gradle.internal.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KgpVersionTest {

  @Suppress("LocalVariableName")
  @Test
  fun `test parsing and comparing different KGP versions`() {
    val kgp_2_0_0 = KgpVersion.parse("2.0.0")
    val kgp_2_1_0_beta1 = KgpVersion.parse("2.1.0-Beta1")
    val kgp_2_1_20_rc = KgpVersion.parse("2.1.20-RC")
    val kgp_2_2_20_dev_7502 = KgpVersion.parse("2.2.20-dev-7502")
    val kgp_2_2_20 = KgpVersion.parse("2.2.20")

    assertThat(kgp_2_0_0.toString()).isEqualTo("2.0.0")
    assertThat(kgp_2_1_0_beta1.toString()).isEqualTo("2.1.0-Beta1")
    assertThat(kgp_2_1_20_rc.toString()).isEqualTo("2.1.20-RC")
    assertThat(kgp_2_2_20_dev_7502.toString()).isEqualTo("2.2.20-dev-7502")
    assertThat(kgp_2_2_20.toString()).isEqualTo("2.2.20")

    assertThat(kgp_2_0_0).isLessThan(kgp_2_1_0_beta1)
    assertThat(kgp_2_1_0_beta1).isLessThan(kgp_2_1_20_rc)
    assertThat(kgp_2_1_20_rc).isLessThan(kgp_2_2_20_dev_7502)
    assertThat(kgp_2_2_20_dev_7502).isLessThan(kgp_2_2_20)
  }
}
