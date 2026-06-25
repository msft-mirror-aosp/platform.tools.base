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
package com.android.template.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SchemaVersionTest {

  @Test
  fun testFromString() {
    val v1 = SchemaVersion.fromString("1.2")
    assertThat(v1.major).isEqualTo(1)
    assertThat(v1.minor).isEqualTo(2)
    assertThat(v1.micro).isNull()

    val v2 = SchemaVersion.fromString("2.3.4")
    assertThat(v2.major).isEqualTo(2)
    assertThat(v2.minor).isEqualTo(3)
    assertThat(v2.micro).isEqualTo(4)

    val v3 = SchemaVersion.fromString("invalid")
    assertThat(v3.major).isEqualTo(0)
    assertThat(v3.minor).isEqualTo(0)
    assertThat(v3.micro).isNull()

    val v4 = SchemaVersion.fromString("1")
    assertThat(v4.major).isEqualTo(1)
    assertThat(v4.minor).isEqualTo(0)
    assertThat(v4.micro).isNull()
  }

  @Test
  fun testToString() {
    assertThat(SchemaVersion(1, 2).toString()).isEqualTo("1.2")
    assertThat(SchemaVersion(2, 3, 4).toString()).isEqualTo("2.3.4")
  }

  @Test
  fun testComparison() {
    val v1_2 = SchemaVersion(1, 2)
    val v1_2_0 = SchemaVersion(1, 2, 0)
    val v1_2_1 = SchemaVersion(1, 2, 1)
    val v1_3 = SchemaVersion(1, 3)
    val v2_1 = SchemaVersion(2, 1)

    // Major comparisons
    assertThat(v1_2).isLessThan(v2_1)
    assertThat(v2_1).isGreaterThan(v1_2)

    // Minor comparisons
    assertThat(v1_2).isLessThan(v1_3)
    assertThat(v1_3).isGreaterThan(v1_2)

    // Micro comparisons
    assertThat(v1_2).isLessThan(v1_2_0) // micro = null is less than micro = 0
    assertThat(v1_2_0).isLessThan(v1_2_1)
    assertThat(v1_2_0).isEqualTo(SchemaVersion(1, 2, 0))
    assertThat(v1_2).isEqualTo(SchemaVersion(1, 2))
  }
}
