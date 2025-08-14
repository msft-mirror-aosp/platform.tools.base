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
import com.android.builder.model.SyncIssue
import com.google.common.truth.Truth
import org.gradle.api.Action
import org.gradle.api.problems.AdditionalData
import org.gradle.api.problems.DocLink
import org.gradle.api.problems.ProblemGroup
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.Severity
import org.gradle.api.problems.internal.AdditionalDataSpec
import org.gradle.api.problems.internal.GeneralDataSpec
import org.gradle.api.problems.internal.InternalProblem
import org.gradle.api.problems.internal.InternalProblemReporter
import org.gradle.api.problems.internal.InternalProblemSpec
import org.gradle.api.problems.internal.InternalProblems
import org.gradle.api.provider.Property
import org.gradle.problems.ProblemDiagnostics
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import kotlin.test.fail

class AndroidProblemsReporterTest {

    @Test
    fun testNoInteractionWithProblemsApiWhenFlagOff() {
        val problemsServiceMock: InternalProblems = mock()
        val reporterProvider = object : AndroidProblemReporterProvider(problemsServiceMock) {
            override fun getParameters(): Parameters {
                return object : Parameters {
                    override val enableProblemsApi: Property<Boolean>
                        get() = FakeGradleProperty(false)
                }
            }
        }

        reporterProvider.reporter().reportSyncIssue(
            IssueReporter.Type.BUILD_TOOLS_TOO_LOW,
            IssueReporter.Severity.ERROR,
            EvalIssueException(RuntimeException(""))
        )

        Mockito.verifyNoInteractions(problemsServiceMock)
    }

    @Test
    fun testReportingCallsToProblemsApi() {
        val problemsServiceMock = mock<InternalProblems>()
        val reporterMock = mock<InternalProblemReporter>()
        val problemMock = mock<InternalProblem>()
        whenever(problemsServiceMock.internalReporter).thenReturn(reporterMock)
        whenever(reporterMock.internalCreate(any<Action<InternalProblemSpec>>()))
            .thenReturn(problemMock)

        val reporterProvider = object : AndroidProblemReporterProvider(problemsServiceMock) {
            override fun getParameters(): Parameters {
                return object : Parameters {
                    override val enableProblemsApi: Property<Boolean>
                        get() = FakeGradleProperty(true)
                }
            }
        }

        reporterProvider.reporter().reportSyncIssue(
            IssueReporter.Type.BUILD_TOOLS_TOO_LOW,
            IssueReporter.Severity.ERROR,
            EvalIssueException(RuntimeException(""))
        )

        verify(problemsServiceMock).internalReporter
        verify(reporterMock).internalCreate(any<Action<InternalProblemSpec>>())
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
            evalIssueException
        ).execute(problemSpec)

        problemSpec.verifyRecordedFields(
            mapOf(
                "severity" to Severity.ERROR,
                "id.name" to SyncIssue.TYPE_BUILD_TOOLS_TOO_LOW.toString(),
                "id.displayName" to "BUILD_TOOLS_TOO_LOW",
                "id.group" to ProblemGroup.create("agp-sync-issues", "Sync Issues"),
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
            evalIssueException
        ).execute(problemSpec)

        problemSpec.verifyRecordedFields(
            mapOf(
                "severity" to Severity.WARNING,
                "id.name" to SyncIssue.TYPE_DEPRECATED_DSL.toString(),
                "id.displayName" to "DEPRECATED_DSL",
                "id.group" to ProblemGroup.create("agp-sync-issues", "Sync Issues"),
                // Not sure if message should actually go to contextual label or somewhere else
                "contextualLabel" to "Warning 1",
                "exception" to evalIssueException,
            )
        )
    }

    @Test
    fun testProblemBuildingFromSyncIssue_MultilineErrorWithAdditionalData() {
        val problemSpec = FakeProblemSpec()
        val evalIssueException = EvalIssueException(
            "Error 1", "my-additional-data", listOf(
                "Line 1",
                "Line 2",
                "Line 3"
            )
        )
        AndroidProblemsReporterImpl.AndroidSyncIssueProblemBuilder(
            IssueReporter.Type.BUILD_TOOLS_TOO_LOW,
            IssueReporter.Severity.ERROR,
            evalIssueException
        ).execute(problemSpec)

        problemSpec.verifyRecordedFields(
            mapOf(
                "severity" to Severity.ERROR,
                "id.name" to SyncIssue.TYPE_BUILD_TOOLS_TOO_LOW.toString(),
                "id.displayName" to "BUILD_TOOLS_TOO_LOW",
                "id.group" to ProblemGroup.create("agp-sync-issues", "Sync Issues"),
                // Not sure if message should actually go to contextual label or somewhere else
                "contextualLabel" to "Error 1",
                "details" to "Line 1\nLine 2\nLine 3",
                "exception" to evalIssueException,
                "additionalDataInternal.class" to GeneralDataSpec::class.java,
                "additionalDataInternal.GeneralData.EvalIssueException.data" to "my-additional-data"
            )
        )
    }

    class FakeProblemSpec : InternalProblemSpec {

        val records = mutableMapOf<String, Any>()

        fun record(name: String, value: Any) {
            if (name in records) {
                fail("$name is recorded second time.")
            }
            records.put(name, value)
        }

        fun verifyRecordedFields(expectedMap: Map<String, Any>) {
            Truth.assertThat(records.keys).containsExactlyElementsIn(expectedMap.keys)
            expectedMap.forEach {
                Truth.assertThat(records).containsEntry(it.key, it.value)
            }
        }

        override fun <U : AdditionalDataSpec> additionalDataInternal(
            p0: Class<out U>,
            p1: Action<in U>
        ): InternalProblemSpec = apply {
            record("additionalDataInternal.class", p0)
            val generalDataSpec = object : GeneralDataSpec {
                override fun put(name: String, value: String): GeneralDataSpec = apply {
                    record("additionalDataInternal.GeneralData.$name", value)
                }
            }
            p1.execute(generalDataSpec as U)
        }

        override fun <T : AdditionalData?> additionalData(
            p0: Class<T?>,
            p1: Action<in T>
        ): InternalProblemSpec {
            fail("Not expected to be called")
        }

        override fun taskLocation(buildTreePath: String): InternalProblemSpec = apply {
            record("taskPathLocation", buildTreePath)
        }

        override fun documentedAt(p0: DocLink?): InternalProblemSpec = apply {
            record("documentedAt.url", p0?.url ?: "null")
        }

        override fun id(id: ProblemId): InternalProblemSpec = apply {
            record("id.name", id.name)
            record("id.displayName", id.displayName)
            record("id.group", id.group)
        }

        override fun id(
            name: String,
            displayName: String,
            group: ProblemGroup
        ): InternalProblemSpec =
            id(ProblemId.create(name, displayName, group))

        override fun contextualLabel(p0: String): InternalProblemSpec = apply {
            record("contextualLabel", p0)
        }

        override fun documentedAt(p0: String): InternalProblemSpec = apply {
            record("documentedAt", p0)
        }

        override fun fileLocation(path: String): InternalProblemSpec =
            lineInFileLocation(path, -1, -1, -1)

        override fun lineInFileLocation(path: String, line: Int): InternalProblemSpec =
            lineInFileLocation(path, line, -1, -1)

        override fun lineInFileLocation(path: String, line: Int, column: Int): InternalProblemSpec =
            lineInFileLocation(path, line, column, -1)

        override fun lineInFileLocation(
            path: String,
            line: Int,
            column: Int,
            length: Int
        ): InternalProblemSpec = apply {
            record("lineInFileLocation.path", path)
            record("lineInFileLocation.line", line)
            record("lineInFileLocation.column", column)
            record("lineInFileLocation.length", length)
        }

        override fun offsetInFileLocation(
            path: String,
            offset: Int,
            length: Int
        ): InternalProblemSpec = apply {
            record("offsetInFileLocation.path", path)
            record("offsetInFileLocation.offset", offset)
            record("offsetInFileLocation.length", length)
        }

        override fun stackLocation(): InternalProblemSpec {
            fail("Not expected to be called")
        }

        override fun details(p0: String): InternalProblemSpec = apply {
            record("details", p0)
        }

        override fun solution(p0: String): InternalProblemSpec = apply {
            record("solution", p0)
        }

        override fun withException(p0: Throwable): InternalProblemSpec = apply {
            record("exception", p0)
        }

        override fun severity(p0: Severity): InternalProblemSpec = apply {
            record("severity", p0)
        }

        override fun diagnostics(diagnostics: ProblemDiagnostics): InternalProblemSpec = apply {
            record("diagnostics", diagnostics)
        }

    }
}
