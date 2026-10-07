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

package com.android.build.gradle.internal.coverage

import com.android.Version
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoverageAgentConfigurationsTest {

  @Test
  fun agentCoordinateIsPinnedToTheAgpVersion() {
    val coordinate = CoverageAgentConfigurations.getAgentRuntimeDependency()

    assertThat(coordinate).isEqualTo("com.android.tools.test:coverage-agent:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
    // The agent is released in lockstep with AGP: it must never carry an independent version
    // (it used to be 0.1.0-alpha01 before it was coupled to the plugin).
    assertThat(coordinate.substringAfterLast(':')).isEqualTo(Version.ANDROID_GRADLE_PLUGIN_VERSION)
    assertThat(coordinate).doesNotContain("0.1.0")
  }
}
