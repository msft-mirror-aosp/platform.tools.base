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

package com.android.build.gradle.internal.tasks

import com.android.build.gradle.internal.fixtures.FakeSyncIssueReporter
import com.android.builder.dexing.R8Version
import com.android.builder.errors.IssueReporter
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/** Unit tests for [checkIfR8VersionMatches]. */
class R8VersionCheckTest {

  private lateinit var issueReporter: FakeSyncIssueReporter

  @Before
  fun setUp() {
    issueReporter = FakeSyncIssueReporter()
  }

  @Test
  fun testMatchingVersionDoesNotReportWarning() {
    checkIfR8VersionMatches(issueReporter, R8Version.VERSION_AGP_WAS_SHIPPED_WITH)
    assertThat(issueReporter.warnings).isEmpty()
    assertThat(issueReporter.syncIssues).isEmpty()
  }

  @Test
  fun testDefaultVersionFromClasspathDoesNotReportWarning() {
    checkIfR8VersionMatches(issueReporter)
    assertThat(issueReporter.warnings).isEmpty()
    assertThat(issueReporter.syncIssues).isEmpty()
  }

  @Test
  fun testNewerVersionDoesNotReportWarning() {
    checkIfR8VersionMatches(issueReporter, "99.0.0-newer")
    assertThat(issueReporter.warnings).isEmpty()
    assertThat(issueReporter.syncIssues).isEmpty()
  }

  @Test
  fun testOlderVersionReportsWarning() {
    val olderVersion = "3.0.0"
    checkIfR8VersionMatches(issueReporter, olderVersion)
    assertThat(issueReporter.warnings).hasSize(1)
    assertThat(issueReporter.syncIssues).hasSize(1)
    val syncIssue = issueReporter.syncIssues.first()
    assertThat(syncIssue.type).isEqualTo(IssueReporter.Type.R8_VERSION_MISMATCH.type)
    assertThat(syncIssue.message).contains("Your project includes version $olderVersion of R8")
    assertThat(syncIssue.message).contains("while Android Gradle Plugin was shipped with")
  }

  @Test
  fun testInvalidVersionStringIgnored() {
    checkIfR8VersionMatches(issueReporter, "invalid-version-string")
    assertThat(issueReporter.warnings).isEmpty()
  }
}
