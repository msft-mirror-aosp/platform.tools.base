/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.build.gradle.internal.errors

import com.android.build.gradle.internal.fixtures.FakeAndroidProblemsReporter
import com.android.build.gradle.internal.fixtures.FakeAndroidProblemsReporter.ReportedIssue
import com.android.build.gradle.internal.fixtures.FakeGradleProperty
import com.android.build.gradle.internal.fixtures.FakeObjectFactory
import com.android.build.gradle.options.SyncOptions
import com.android.builder.errors.EvalIssueException
import com.android.builder.errors.IssueReporter
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncIssueReporterImplTest {

  val androidProblemsReporterProviderService: AndroidProblemReporterProvider = mock()

  val androidProblemsReporter = FakeAndroidProblemsReporter()

  val issueReporter: SyncIssueReporterImpl.GlobalSyncIssueService by lazy {
    object : SyncIssueReporterImpl.GlobalSyncIssueService() {
      override fun getParameters(): Parameters {
        return object : Parameters {
          override val mode: Property<SyncOptions.EvaluationMode>
            get() = FakeGradleProperty(SyncOptions.EvaluationMode.IDE)

          override val errorFormatMode: Property<SyncOptions.ErrorFormatMode>
            get() = FakeGradleProperty(SyncOptions.ErrorFormatMode.HUMAN_READABLE)

          override val androidProblemReporterProviderService: Property<AndroidProblemReporterProvider>
            get() = FakeGradleProperty(androidProblemsReporterProviderService)

          override val suppressedSyncIssues: SetProperty<String>
            get() = FakeObjectFactory.factory.setProperty(String::class.java).value(setOf(Type.GRADLE_TOO_OLD.name))
        }
      }
    }
  }

  @Before
  fun setup() {
    whenever(androidProblemsReporterProviderService.reporter()).thenReturn(androidProblemsReporter)
  }

  @Test
  fun testCollectingIssueTwice() {
    issueReporter.reportError(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, RuntimeException(""))
    assertThat(issueReporter.getAllIssuesAndClear().map { it.type }).containsExactly(IssueReporter.Type.BUILD_TOOLS_TOO_LOW.type)
    assertThat(issueReporter.getAllIssuesAndClear().map { it.type }).isEmpty()
    assertThat(androidProblemsReporter.reportedSyncIssues)
      .isEqualTo(listOf(ReportedIssue(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, IssueReporter.Severity.ERROR, "")))
  }

  @Test
  fun testReportingSameIssueTwice() {
    issueReporter.reportError(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, RuntimeException(""))
    issueReporter.reportError(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, RuntimeException(""))
    assertThat(issueReporter.getAllIssuesAndClear().map { it.type }).containsExactly(IssueReporter.Type.BUILD_TOOLS_TOO_LOW.type)
    assertThat(androidProblemsReporter.reportedSyncIssues)
      .isEqualTo(listOf(ReportedIssue(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, IssueReporter.Severity.ERROR, "")))
  }

  @Test
  fun testReportingAfterClear() {
    issueReporter.reportError(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, RuntimeException(""))
    assertThat(issueReporter.getAllIssuesAndClear().map { it.type }).containsExactly(IssueReporter.Type.BUILD_TOOLS_TOO_LOW.type)
    issueReporter.reportError(IssueReporter.Type.COMPILE_SDK_VERSION_NOT_SET, RuntimeException(""))
    assertThat(issueReporter.getAllIssuesAndClear().map { it.type }).containsExactly(IssueReporter.Type.COMPILE_SDK_VERSION_NOT_SET.type)
    assertThat(androidProblemsReporter.reportedSyncIssues)
      .isEqualTo(
        listOf(
          ReportedIssue(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, IssueReporter.Severity.ERROR, ""),
          ReportedIssue(IssueReporter.Type.COMPILE_SDK_VERSION_NOT_SET, IssueReporter.Severity.ERROR, ""),
        )
      )
  }

  @Test
  fun testCloseReportsRemainingErrors() {
    issueReporter.reportError(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, RuntimeException("Error: 1"))
    issueReporter.reportError(IssueReporter.Type.COMPILE_SDK_VERSION_NOT_SET, RuntimeException("Error: 2"))
    issueReporter.reportError(IssueReporter.Type.EDIT_LOCKED_DSL_VALUE, RuntimeException("Error: 3"))
    issueReporter.reportWarning(IssueReporter.Type.DEPRECATED_DSL, RuntimeException("Warning"))

    val issueException = assertFailsWith<EvalIssueException> { issueReporter.close() }
    assertThat(issueException.suppressed).hasLength(2)
    // Ordering might change, so assert about the three errors together
    val errors = issueException.suppressed.asList() + issueException
    assertThat(errors.map { it.message }).containsExactly("Error: 1", "Error: 2", "Error: 3")
    assertThat(androidProblemsReporter.reportedSyncIssues)
      .isEqualTo(
        listOf(
          ReportedIssue(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, IssueReporter.Severity.ERROR, "Error: 1"),
          ReportedIssue(IssueReporter.Type.COMPILE_SDK_VERSION_NOT_SET, IssueReporter.Severity.ERROR, "Error: 2"),
          ReportedIssue(IssueReporter.Type.EDIT_LOCKED_DSL_VALUE, IssueReporter.Severity.ERROR, "Error: 3"),
          ReportedIssue(IssueReporter.Type.DEPRECATED_DSL, IssueReporter.Severity.WARNING, "Warning"),
        )
      )
  }

  @Test
  fun testReportingAfterClose() {
    issueReporter.reportError(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, RuntimeException(""))
    assertThat(issueReporter.getAllIssuesAndClear().map { it.type }).containsExactly(IssueReporter.Type.BUILD_TOOLS_TOO_LOW.type)
    issueReporter.close()
    val failure =
      assertFailsWith<IllegalStateException> { issueReporter.reportError(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, RuntimeException("")) }
    assertThat(failure).hasMessageThat().isEqualTo("Issue registered after handler locked.")
    // Problems API can still sed a message so should not be affected by handler locked.
    assertThat(androidProblemsReporter.reportedSyncIssues)
      .isEqualTo(
        listOf(
          ReportedIssue(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, IssueReporter.Severity.ERROR, ""),
          ReportedIssue(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, IssueReporter.Severity.ERROR, ""),
        )
      )
  }

  @Test
  fun testIgnoreWarnings() {
    issueReporter.reportError(IssueReporter.Type.GRADLE_TOO_OLD, RuntimeException("Error 1"))
    issueReporter.reportWarning(IssueReporter.Type.GRADLE_TOO_OLD, RuntimeException("Warning 1"))
    issueReporter.reportError(IssueReporter.Type.GENERIC, RuntimeException("Error 2"))
    issueReporter.reportWarning(IssueReporter.Type.GENERIC, RuntimeException("Warning 2"))

    assertThat(issueReporter.getAllIssuesAndClear().map { it.severity })
      .containsExactly(
        IssueReporter.Severity.ERROR.severity,
        IssueReporter.Severity.ERROR.severity,
        IssueReporter.Severity.WARNING.severity,
      )
  }
}
