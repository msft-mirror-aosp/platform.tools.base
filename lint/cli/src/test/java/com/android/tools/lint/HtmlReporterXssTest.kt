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

package com.android.tools.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Severity
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtmlReporterXssTest {
  @get:Rule var temporaryFolder = TemporaryFolder()

  @Test
  fun testV1ReportXssInTitle() {
    val output = File(temporaryFolder.newFolder(), "report.html")
    val flags = LintCliFlags()
    val client = createFakeClient(flags)
    val reporter = HtmlReporter(client, output, flags)
    val xssPayload = "\"><img src=x onerror=alert(1)>"
    reporter.title = "Report $xssPayload"

    val stats = LintStats(0, 0)
    reporter.write(stats, emptyList(), createFakeRegistry())

    val html = output.readText()
    if (html.contains(xssPayload)) {
      fail("V1 Report title should be escaped, but found raw payload in:\n$html")
    }
    assertTrue("Should contain escaped payload", html.contains("Report \">&lt;img src=x onerror=alert(1)>"))
  }

  @Test
  fun testV1ReportXssInLocation() {
    val output = File(temporaryFolder.newFolder(), "report.html")
    val flags = LintCliFlags()
    val client = createFakeClient(flags)
    val reporter = HtmlReporter(client, output, flags)

    val xssPayload = "\"><img src=x onerror=alert(1)>"
    val maliciousFile = File(temporaryFolder.root, "app/src/main/res/values/a$xssPayload.xml")

    val incident =
      Incident(
        Issue.create(
          "TestIssue",
          "Summary",
          "Explanation",
          com.android.tools.lint.detector.api.Category.CORRECTNESS,
          5,
          Severity.ERROR,
          com.android.tools.lint.detector.api.Implementation(
            com.android.tools.lint.detector.api.Detector::class.java,
            com.android.tools.lint.detector.api.Scope.RESOURCE_FILE_SCOPE,
          ),
        ),
        "Test Message",
        com.android.tools.lint.detector.api.Location.create(maliciousFile),
      )

    val stats = LintStats(1, 0)
    reporter.write(stats, listOf(incident), createFakeRegistry())

    val html = output.readText()
    assertFalse("V1 Report location should be escaped", html.contains(xssPayload))
  }

  private fun createFakeClient(flags: LintCliFlags): LintCliClient {
    return object : LintCliClient(flags, "test-client") {
      override fun getRootDir(): File? = temporaryFolder.root
    }
  }

  private fun createFakeRegistry(): IssueRegistry {
    return object : IssueRegistry() {
      override val issues = emptyList<Issue>()
    }
  }
}
