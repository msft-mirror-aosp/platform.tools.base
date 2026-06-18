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
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtmlReporterV2Test {
  @get:Rule var temporaryFolder = TemporaryFolder()

  @Test
  fun testLocalReportTitle() {
    val output = File(temporaryFolder.newFolder(), "report.html")
    val flags = LintCliFlags()
    flags.isCheckDependencies = false
    val client = createFakeClient(flags)
    val reporter = HtmlReporterV2(client, output, flags)

    val stats = LintStats(0, 0)
    reporter.write(stats, emptyList(), createFakeRegistry())

    val html = output.readText()
    assertTrue(html.contains("<title>Local Lint Report</title>"))
    assertTrue(html.contains("<h1 class=\"header-title\" id=\"project-name\">Local Lint Report</h1>"))
  }

  @Test
  fun testAggregateReportTitle() {
    val output = File(temporaryFolder.newFolder(), "report.html")
    val flags = LintCliFlags()
    flags.isCheckDependencies = true
    val client = createFakeClient(flags)
    val reporter = HtmlReporterV2(client, output, flags)

    val stats = LintStats(0, 0)
    reporter.write(stats, emptyList(), createFakeRegistry())

    val html = output.readText()
    assertTrue(html.contains("<title>Aggregate Lint Report</title>"))
    assertTrue(html.contains("<h1 class=\"header-title\" id=\"project-name\">Aggregate Lint Report</h1>"))
  }

  @Test
  fun testCustomTitle() {
    val output = File(temporaryFolder.newFolder(), "report.html")
    val flags = LintCliFlags()
    flags.isCheckDependencies = false
    val client = createFakeClient(flags)
    val reporter = HtmlReporterV2(client, output, flags)
    reporter.title = "Custom Report"

    val stats = LintStats(0, 0)
    reporter.write(stats, emptyList(), createFakeRegistry())

    val html = output.readText()
    assertTrue(html.contains("<title>Local Custom Report</title>"))
    assertTrue(html.contains("<h1 class=\"header-title\" id=\"project-name\">Local Custom Report</h1>"))
  }

  @Test
  fun testClientDisplayNameInJson() {
    val output = File(temporaryFolder.newFolder(), "report.html")
    val flags = LintCliFlags()
    val client =
      object : LintCliClient(flags, "Test Client") {
        override fun getRootDir(): File? = temporaryFolder.root

        override fun getClientDisplayName(): String = "AGP (9.3.0-dev)"
      }
    val reporter = HtmlReporterV2(client, output, flags)

    val stats = LintStats(0, 0)
    reporter.write(stats, emptyList(), createFakeRegistry())

    val html = output.readText()
    assertTrue(html.contains("\"lintVersion\":\"AGP (9.3.0-dev)\""))
  }

  private fun createFakeClient(flags: LintCliFlags): LintCliClient {
    return object : LintCliClient(flags, "test-client") {
      override fun getRootDir(): File? = temporaryFolder.root
    }
  }

  private fun createFakeRegistry(): IssueRegistry {
    return object : IssueRegistry() {
      override val issues = emptyList<com.android.tools.lint.detector.api.Issue>()
    }
  }
}
