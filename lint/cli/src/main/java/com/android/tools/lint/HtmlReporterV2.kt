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
import com.android.tools.lint.renderer.LintReportBuilder
import com.android.tools.lint.renderer.data.LintReport
import com.android.utils.SdkUtils
import com.google.gson.GsonBuilder
import java.io.File

/**
 * A reporter which emits lint results into an HTML report, version 2. This is a temporary placeholder which currently delegates to
 * [HtmlReporter], and will be implemented later.
 */
class HtmlReporterV2(client: LintCliClient, output: File, flags: LintCliFlags) : HtmlReporter(client, output, flags) {
  override fun write(stats: LintStats, incidents: List<Incident>, registry: IssueRegistry) {
    val output = this.output ?: return
    val rootProjectDir = client.getRootDir() ?: output.parentFile ?: File(".")
    val titlePrefix = if (flags.isCheckDependencies) "Aggregate" else "Local"
    val reportTitle = "$titlePrefix $title"

    val builder = LintReportBuilder(client, reportTitle, rootProjectDir, client.getClientDisplayName()) { getUrl(it) }
    val lintReport = builder.buildReport(incidents, computeExtraIssues(registry), computeMissingIssues(registry, incidents))

    render(lintReport, output)

    if (!client.flags.isQuiet && (stats.errorCount > 0 || stats.warningCount > 0)) {
      val url = SdkUtils.fileToUrlString(output.absoluteFile)
      println("Wrote HTML report to $url")
    }
  }

  private fun render(lintReport: LintReport, outputHtml: File) {
    val json = GsonBuilder().create().toJson(lintReport)
    val finalHtml = getIndexHtml("const lintReport = $json;", lintReport.name)
    outputHtml.writeText(finalHtml)
  }
}
