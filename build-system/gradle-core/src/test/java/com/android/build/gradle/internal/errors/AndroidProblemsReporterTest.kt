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

package com.android.build.gradle.internal.errors

import com.android.build.gradle.internal.fixtures.FakeGradleProperty
import com.android.builder.errors.EvalIssueException
import com.android.builder.errors.IssueReporter
import com.google.common.truth.Truth
import java.lang.reflect.Proxy
import kotlin.test.fail
import org.gradle.api.Action
import org.gradle.api.problems.AdditionalData
import org.gradle.api.problems.Problem
import org.gradle.api.problems.ProblemGroup
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.ProblemReporter
import org.gradle.api.problems.ProblemSpec
import org.gradle.api.problems.Problems
import org.gradle.api.problems.Severity
import org.gradle.api.provider.Property
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

class AndroidProblemsReporterTest {

  @Test
  fun testNoInteractionWithProblemsApiWhenFlagOff() {
    val problemsServiceMock: Problems = mock()
    val reporterProvider =
      object : AndroidProblemReporterProvider(problemsServiceMock) {
        override fun getParameters(): Parameters {
          return object : Parameters {
            override val enableProblemsApi: Property<Boolean>
              get() = FakeGradleProperty(false)
          }
        }
      }

    reporterProvider
      .reporter()
      .reportSyncIssue(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, IssueReporter.Severity.ERROR, EvalIssueException(RuntimeException("")))

    Mockito.verifyNoInteractions(problemsServiceMock)
  }

  @Test
  fun testReportingCallsToProblemsApi() {
    val problemsServiceMock = mock<Problems>()
    val reporterMock = mock<ProblemReporter>()
    val problemMock = mock<Problem>()
    whenever(problemsServiceMock.reporter).thenReturn(reporterMock)
    whenever(reporterMock.create(any<ProblemId>(), any<Action<ProblemSpec>>())).thenReturn(problemMock)

    val reporterProvider =
      object : AndroidProblemReporterProvider(problemsServiceMock) {
        override fun getParameters(): Parameters {
          return object : Parameters {
            override val enableProblemsApi: Property<Boolean>
              get() = FakeGradleProperty(true)
          }
        }
      }

    reporterProvider
      .reporter()
      .reportSyncIssue(IssueReporter.Type.BUILD_TOOLS_TOO_LOW, IssueReporter.Severity.ERROR, EvalIssueException(RuntimeException("")))
    val expectedId =
      ProblemId.create(
        /* name = */ IssueReporter.Type.BUILD_TOOLS_TOO_LOW.type.toString(),
        /* displayName = */ IssueReporter.Type.BUILD_TOOLS_TOO_LOW.name,
        /* group = */ ProblemGroup.create("agp-sync-issues", "Sync Issues"),
      )
    verify(problemsServiceMock).reporter
    verify(reporterMock).create(eq(expectedId), any<Action<ProblemSpec>>())
    verify(reporterMock).report(same(problemMock))
    verifyNoMoreInteractions(problemsServiceMock)
    verifyNoMoreInteractions(reporterMock)
  }

  @Test
  fun testProblemBuildingFromSyncIssue_ErrorSimple() {
    val problemSpec = FakeProblemSpec()

    val evalIssueException = EvalIssueException(RuntimeException("Error 1"))
    AndroidProblemsReporterImpl.AndroidSyncIssueProblemBuilder(
        IssueReporter.Type.BUILD_TOOLS_TOO_LOW,
        IssueReporter.Severity.ERROR,
        evalIssueException,
      )
      .execute(problemSpec)

    // Note: id is now part of reporting call outside of problem spec.
    problemSpec.verifyRecordedFields(
      mapOf(
        "severity" to Severity.ERROR,
        // Not sure if message should actually go to contextual label or somewhere else
        "contextualLabel" to "Error 1",
        "exception" to evalIssueException,
      )
    )
  }

  @Test
  fun testProblemBuildingFromSyncIssue_WarningSimple() {
    val problemSpec = FakeProblemSpec()

    val evalIssueException = EvalIssueException(RuntimeException("Warning 1"))
    AndroidProblemsReporterImpl.AndroidSyncIssueProblemBuilder(
        IssueReporter.Type.DEPRECATED_DSL,
        IssueReporter.Severity.WARNING,
        evalIssueException,
      )
      .execute(problemSpec)

    // Note: id is now part of reporting call outside of problem spec.
    problemSpec.verifyRecordedFields(
      mapOf(
        "severity" to Severity.WARNING,
        // Not sure if message should actually go to contextual label or somewhere else
        "contextualLabel" to "Warning 1",
        "exception" to evalIssueException,
      )
    )
  }

  @Test
  fun testProblemBuildingFromSyncIssue_MultilineErrorWithAdditionalData() {
    val problemSpec = FakeProblemSpec()
    val evalIssueException = EvalIssueException("Error 1", "my-additional-data", listOf("Line 1", "Line 2", "Line 3"))
    AndroidProblemsReporterImpl.AndroidSyncIssueProblemBuilder(
        IssueReporter.Type.BUILD_TOOLS_TOO_LOW,
        IssueReporter.Severity.ERROR,
        evalIssueException,
      )
      .execute(problemSpec)

    // Note: id is now part of reporting call outside of problem spec.
    problemSpec.verifyRecordedFields(
      mapOf(
        "severity" to Severity.ERROR,
        // Not sure if message should actually go to contextual label or somewhere else
        "contextualLabel" to "Error 1",
        "details" to "Line 1\nLine 2\nLine 3",
        "additionalData.class" to SyncIssueData::class.java,
        "additionalData.setData" to listOf("my-additional-data"),
        "exception" to evalIssueException,
      )
    )
  }

  class FakeProblemSpec : ProblemSpec {

    val records = mutableMapOf<String, Any>()

    fun record(name: String, value: Any) {
      if (name in records) {
        fail("$name is recorded second time.")
      }
      records.put(name, value)
    }

    fun verifyRecordedFields(expectedMap: Map<String, Any>) {
      Truth.assertThat(records.keys).containsExactlyElementsIn(expectedMap.keys)
      expectedMap.forEach { Truth.assertThat(records).containsEntry(it.key, it.value) }
    }

    override fun <T : AdditionalData> additionalData(type: Class<T>, config: Action<in T>): ProblemSpec = apply {
      //            val instance = DirectInstantiator.instantiate(type)
      val instance =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
          record("additionalData." + method.name, args.asList())
        }
      record("additionalData.class", type)
      config.execute(instance as T)
    }

    override fun contextualLabel(p0: String): ProblemSpec = apply { record("contextualLabel", p0) }

    override fun documentedAt(p0: String): ProblemSpec = apply { record("documentedAt", p0) }

    override fun fileLocation(path: String): ProblemSpec = lineInFileLocation(path, -1, -1, -1)

    override fun lineInFileLocation(path: String, line: Int): ProblemSpec = lineInFileLocation(path, line, -1, -1)

    override fun lineInFileLocation(path: String, line: Int, column: Int): ProblemSpec = lineInFileLocation(path, line, column, -1)

    override fun lineInFileLocation(path: String, line: Int, column: Int, length: Int): ProblemSpec = apply {
      record("lineInFileLocation.path", path)
      record("lineInFileLocation.line", line)
      record("lineInFileLocation.column", column)
      record("lineInFileLocation.length", length)
    }

    override fun offsetInFileLocation(path: String, offset: Int, length: Int): ProblemSpec = apply {
      record("offsetInFileLocation.path", path)
      record("offsetInFileLocation.offset", offset)
      record("offsetInFileLocation.length", length)
    }

    override fun stackLocation(): ProblemSpec {
      fail("Not expected to be called")
    }

    override fun details(p0: String): ProblemSpec = apply { record("details", p0) }

    override fun solution(p0: String): ProblemSpec = apply { record("solution", p0) }

    override fun withException(p0: Throwable): ProblemSpec = apply { record("exception", p0) }

    override fun severity(p0: Severity): ProblemSpec = apply { record("severity", p0) }
  }
}
