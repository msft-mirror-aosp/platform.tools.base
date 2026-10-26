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
import com.android.ide.common.repository.AgpVersion
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
    // A newer release of the running plugin's major.minor series, the only agent versions it supports.
    val runningAgpVersion = AgpVersion.parse(Version.ANDROID_GRADLE_PLUGIN_VERSION)
    val override = "${runningAgpVersion.major}.${runningAgpVersion.minor}.99"

    val coordinate = CoverageAgentConfigurations.getAgentRuntimeDependency(versionOverride = override, issueReporter = issueReporter)

    assertThat(coordinate).isEqualTo("com.android.tools.test:coverage-agent:$override")
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
  fun newerOverrideOfTheSameSeriesIsUsed() {
    assertThat(CoverageAgentConfigurations.getAgentVersion("9.5.0-alpha12", issueReporter, agpVersion)).isEqualTo("9.5.0-alpha12")
    assertThat(CoverageAgentConfigurations.getAgentVersion("9.5.3", issueReporter, agpVersion)).isEqualTo("9.5.3")
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
  fun overrideOfAnotherSeriesIsReportedAndFallsBackToTheAgpVersion() {
    // AGP 9.5.x only supports 9.5.y agents: releases of older and newer series are rejected.
    for (override in listOf("9.4.2", "9.4.99", "8.5.0", "9.6.0-alpha01", "9.6.0", "10.5.0", "99.0.0")) {
      val fakeIssueReporter = FakeSyncIssueReporter(throwOnError = false)

      assertThat(CoverageAgentConfigurations.getAgentVersion(override, fakeIssueReporter, agpVersion)).isEqualTo(agpVersion)

      assertThat(fakeIssueReporter.warnings).isEmpty()
      val error = fakeIssueReporter.errors.single()
      assertThat(error)
        .contains("override '$override' is not compatible with the Android Gradle plugin version ($agpVersion), which only supports 9.5.x")
      assertThat(error).contains("Remove the gradle property $propertyName or set it to a 9.5.x version")
    }
  }

  @Test
  fun agpSupportsTheAgentVersionsOfItsOwnMajorAndMinorVersionOnly() {
    val agp = AgpVersion.parse("9.6.0-alpha05")
    for (agent in listOf("9.6.0-alpha01", "9.6.0-alpha05", "9.6.0-dev", "9.6.0", "9.6.3", "9.6.10-rc02")) {
      assertWithMessage(agent).that(CoverageAgentConfigurations.isAgentVersionSupported(AgpVersion.parse(agent), agp)).isTrue()
    }
    for (agent in listOf("9.5.0-alpha01", "9.5.9", "9.7.0-alpha01", "9.7.0", "8.6.0", "10.6.0", "9.0.6")) {
      assertWithMessage(agent).that(CoverageAgentConfigurations.isAgentVersionSupported(AgpVersion.parse(agent), agp)).isFalse()
    }
  }

  @Test
  fun olderPreviewOfTheSameSeriesIsUsedWithAWarning() {
    assertThat(CoverageAgentConfigurations.getAgentVersion("9.5.0-alpha08", issueReporter, agpVersion)).isEqualTo("9.5.0-alpha08")

    assertThat(issueReporter.errors).isEmpty()
    val warning = issueReporter.warnings.single()
    assertThat(warning).contains("'9.5.0-alpha08' is older than the Android Gradle plugin version (9.5.0-alpha09)")
    assertThat(warning).contains(propertyName)
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
