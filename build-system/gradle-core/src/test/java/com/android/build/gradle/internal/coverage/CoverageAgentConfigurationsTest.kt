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
import com.android.build.gradle.internal.fixtures.FakeSyncIssueReporter
import com.android.build.gradle.options.StringOption
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoverageAgentConfigurationsTest {

  private val agpVersion = "9.5.0-alpha09"
  private val propertyName = StringOption.COVERAGE_AGENT_VERSION_OVERRIDE.propertyName
  private val issueReporter = FakeSyncIssueReporter(throwOnError = false)

  @Test
  fun agentCoordinateIsPinnedToTheAgpVersionByDefault() {
    val coordinate = CoverageAgentConfigurations.getAgentRuntimeDependency(versionOverride = null, issueReporter = issueReporter)

    assertThat(coordinate).isEqualTo("com.android.tools.test:coverage-agent:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
    // The agent is released in lockstep with AGP: it must never carry an independent version
    // (it used to be 0.1.0-alpha01 before it was coupled to the plugin).
    assertThat(coordinate.substringAfterLast(':')).isEqualTo(Version.ANDROID_GRADLE_PLUGIN_VERSION)
    assertThat(coordinate).doesNotContain("0.1.0")
    assertThat(issueReporter.messages).isEmpty()
  }

  @Test
  fun agentCoordinateUsesTheOverriddenVersion() {
    val coordinate = CoverageAgentConfigurations.getAgentRuntimeDependency(versionOverride = "99.0.0", issueReporter = issueReporter)

    assertThat(coordinate).isEqualTo("com.android.tools.test:coverage-agent:99.0.0")
    assertThat(issueReporter.messages).isEmpty()
  }

  @Test
  fun blankOverrideUsesTheAgpVersion() {
    for (override in listOf(null, "", "   ")) {
      assertThat(CoverageAgentConfigurations.getAgentVersion(override, issueReporter, agpVersion)).isEqualTo(agpVersion)
    }
    assertThat(issueReporter.messages).isEmpty()
  }

  @Test
  fun newerOverrideIsUsed() {
    assertThat(CoverageAgentConfigurations.getAgentVersion("9.5.0-alpha12", issueReporter, agpVersion)).isEqualTo("9.5.0-alpha12")
    assertThat(CoverageAgentConfigurations.getAgentVersion("9.6.0", issueReporter, agpVersion)).isEqualTo("9.6.0")
    assertThat(issueReporter.messages).isEmpty()
  }

  @Test
  fun sameVersionOverrideIsUsedSilently() {
    assertThat(CoverageAgentConfigurations.getAgentVersion(agpVersion, issueReporter, agpVersion)).isEqualTo(agpVersion)
    assertThat(issueReporter.messages).isEmpty()
  }

  @Test
  fun overrideIsTrimmed() {
    assertThat(CoverageAgentConfigurations.getAgentVersion("  9.5.0-alpha12 ", issueReporter, agpVersion)).isEqualTo("9.5.0-alpha12")
    assertThat(issueReporter.messages).isEmpty()
  }

  @Test
  fun olderOverrideIsUsedWithAWarning() {
    assertThat(CoverageAgentConfigurations.getAgentVersion("9.4.2", issueReporter, agpVersion)).isEqualTo("9.4.2")

    assertThat(issueReporter.errors).isEmpty()
    val warning = issueReporter.warnings.single()
    assertThat(warning).contains("'9.4.2' is older than the Android Gradle plugin version (9.5.0-alpha09)")
    assertThat(warning).contains(propertyName)
  }

  @Test
  fun olderPreviewOfTheSameSeriesIsUsedWithAWarning() {
    assertThat(CoverageAgentConfigurations.getAgentVersion("9.5.0-alpha08", issueReporter, agpVersion)).isEqualTo("9.5.0-alpha08")

    assertThat(issueReporter.errors).isEmpty()
    assertThat(issueReporter.warnings).hasSize(1)
  }

  @Test
  fun unparsableOverrideIsReportedAndFallsBackToTheAgpVersion() {
    for (override in listOf("9.5", "latest", "+", "9.5.0-SNAPSHOT")) {
      val fakeIssueReporter = FakeSyncIssueReporter(throwOnError = false)

      assertThat(CoverageAgentConfigurations.getAgentVersion(override, fakeIssueReporter, agpVersion)).isEqualTo(agpVersion)

      assertThat(fakeIssueReporter.warnings).isEmpty()
      val error = fakeIssueReporter.errors.single()
      assertThat(error).contains("Could not parse on-device coverage agent version override '$override'")
      assertThat(error).contains("Remove or fix the gradle property $propertyName")
      assertThat(error).contains(agpVersion)
    }
  }
}
