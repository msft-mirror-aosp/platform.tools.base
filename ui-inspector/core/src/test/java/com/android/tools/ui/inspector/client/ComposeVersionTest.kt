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

package com.android.tools.ui.inspector.client

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ComposeVersionTest {

  @Test
  fun parse_composeReleases() {
    assertThat(ComposeVersion.parse("1.10.6")).isEqualTo(ComposeVersion(1, 10, 6, ComposeVersion.Stage.STABLE, 0))
    assertThat(ComposeVersion.parse("1.5.0-alpha04")).isEqualTo(ComposeVersion(1, 5, 0, ComposeVersion.Stage.ALPHA, 4))
    assertThat(ComposeVersion.parse("1.11.0-beta01")).isEqualTo(ComposeVersion(1, 11, 0, ComposeVersion.Stage.BETA, 1))
    assertThat(ComposeVersion.parse("1.2.0-rc03")).isEqualTo(ComposeVersion(1, 2, 0, ComposeVersion.Stage.RC, 3))
  }

  @Test
  fun parse_otherVersions_returnsNull() {
    for (version in
      listOf("", "1", "1.10", "1.10.0-ALPHA01", "1.10.0-alpha", "0.1.0-dev17", "1.10.0-SNAPSHOT", "1.7.2+build_4", "99999999999.0.0")) {
      assertThat(ComposeVersion.parse(version)).isNull()
    }
  }

  @Test
  fun order_isTheReleaseOrder() {
    val versions =
      listOf(
        "1.4.3",
        "1.5.0-alpha01",
        "1.5.0-alpha04",
        "1.5.0-beta01",
        "1.5.0-beta03",
        "1.5.0-rc01",
        "1.5.0",
        "1.5.1",
        "1.9.0",
        "1.10.0",
        "2.0.0",
      )
    for ((earlier, later) in versions.map { ComposeVersion.of(it) }.zipWithNext()) {
      assertThat(earlier).isLessThan(later)
    }
  }

  @Test
  fun toString_isThePublishedVersion() {
    for (version in listOf("1.10.6", "1.5.0-alpha04", "1.11.0-beta01", "1.2.0-rc03")) {
      assertThat(ComposeVersion.of(version).toString()).isEqualTo(version)
    }
  }

  @Test
  fun of_otherVersion_fails() {
    assertThrows(IllegalStateException::class.java) { ComposeVersion.of("1.10.0-SNAPSHOT") }
  }
}
