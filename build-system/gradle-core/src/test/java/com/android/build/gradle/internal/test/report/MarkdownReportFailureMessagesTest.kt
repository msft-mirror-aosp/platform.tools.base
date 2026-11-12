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

package com.android.build.gradle.internal.test.report

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.internal.exceptions.MarkedVerificationException
import org.gradle.api.tasks.VerificationException
import org.junit.Test

class MarkdownReportFailureMessagesTest {

  private val markdownReport = File("/project/app/build/reports/tests/testDebugUnitTest/report.md")

  @Test
  fun withMarkdownReportLine_addsTheReportToAFailingTestsMessage() {
    val failure = VerificationException("There were failing tests. See the report at: file:///project/index.html")

    val result = withMarkdownReportLine(failure, markdownReport)

    assertThat(result.message!!.lines().first()).isEqualTo(failure.message)
    assertThat(result.message!!.lines().last()).isEqualTo(markdownReportFailureLine(markdownReport))
    assertThat(result.message!!.lines().last()).startsWith("Markdown version: ")
    assertThat(result.message!!.lines().last()).endsWith("report.md")
  }

  @Test
  fun withMarkdownReportLine_keepsGradlesExceptionType() {
    val failure = MarkedVerificationException("There were failing tests. See the report at: file:///project/index.html")

    assertThat(withMarkdownReportLine(failure, markdownReport)).isInstanceOf(MarkedVerificationException::class.java)
  }

  @Test
  fun withMarkdownReportLine_withoutReport_returnsTheFailureUnchanged() {
    val failure = VerificationException("There were failing tests. See the report at: file:///project/index.html")

    assertThat(withMarkdownReportLine(failure, null)).isSameInstanceAs(failure)
  }

  @Test
  fun withMarkdownReportLine_otherFailure_returnsTheFailureUnchanged() {
    val failure = VerificationException("No tests found for given includes")

    assertThat(withMarkdownReportLine(failure, markdownReport)).isSameInstanceAs(failure)
  }
}
