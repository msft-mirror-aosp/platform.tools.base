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
package com.android.tools.lint.renderer

import com.android.tools.lint.HtmlReporter
import com.android.tools.lint.LintCliClient
import com.android.tools.lint.client.api.IssueRegistry.Companion.AOSP_VENDOR
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.TextFormat
import com.android.tools.lint.getErrorLines
import com.android.tools.lint.getPath
import com.android.tools.lint.renderer.data.LintCheck
import com.android.tools.lint.renderer.data.LintIssue
import com.android.tools.lint.renderer.data.LintLocation
import com.android.tools.lint.renderer.data.LintReport
import com.android.tools.lint.renderer.data.LintVendor
import com.android.utils.SdkUtils
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Builds a [LintReport] containing incidents, additional checks, and disabled checks
 *
 * @property client the [LintCliClient] to fetch file paths and source text
 * @property title the title of the report
 * @property rootProjectDir the root project directory, used to compute relative paths
 * @property displayRevision the revision/version of lint used
 * @property urlProvider a function providing an optional URL for a given [File]
 */
class LintReportBuilder(
  private val client: LintCliClient,
  private val title: String,
  private val rootProjectDir: File,
  private val displayRevision: String?,
  private val urlProvider: (File) -> String?,
) {

  fun buildReport(
    incidents: List<Incident>,
    extraIssues: List<Issue>,
    missingIssues: Map<Issue, String>,
    maxCount: Int = MAX_COUNT,
  ): LintReport {
    val counts = mutableMapOf<Issue, Int>()
    val lintIssues =
      incidents
        .filter { incident ->
          val count = counts.getOrDefault(incident.issue, 0)
          if (count < maxCount) {
            counts[incident.issue] = count + 1
            true
          } else {
            false
          }
        }
        .map(::createLintIssue)
    val additionalChecks = extraIssues.map(::createLintCheck)
    val disabledChecks = missingIssues.map { (issue, reason) -> createLintCheck(issue, reason) }

    return LintReport(
      name = title,
      timeStamp = getReportTimestamp(),
      issues = lintIssues,
      numberOfIssues = lintIssues.size,
      lintVersion = displayRevision,
      additionalChecks = additionalChecks,
      disabledChecks = disabledChecks,
    )
  }

  private fun createLintIssue(incident: Incident): LintIssue {
    val issue = incident.issue
    val locations =
      generateSequence(incident.location) { it.secondary }
        .map { curr ->
          LintLocation(
            file = incident.getPath(client, curr.file),
            line = curr.start?.line?.plus(1),
            column = curr.start?.column?.plus(1),
            url = urlProvider(curr.file),
          )
        }
        .toList()

    val primaryLocation = locations.firstOrNull()
    val errorLine = incident.getErrorLines(textProvider = { file -> client.getSourceText(file) })
    val (errorLine1, errorLine2) = parseErrorLines(errorLine)

    val sourceContext =
      if (primaryLocation?.line != null && incident.location.file != null) {
        extractSourceContext(primaryLocation, client.getSourceText(incident.location.file), errorLine2, HtmlReporter.CODE_WINDOW_SIZE)
      } else {
        null
      }

    val file = incident.file
    val relPath = file.relativeToOrNull(rootProjectDir)?.path ?: file.name
    val dir = relPath.substringBeforeLast(File.separator, "")
    val pkgName = if (dir.isNotEmpty()) dir.replace(File.separator, ".") else "default"

    val applicableVariants = incident.applicableVariants

    val images = mutableListOf<String>()
    var curr: Location? = incident.location
    while (curr != null) {
      val imageFile = curr.file
      if (SdkUtils.isBitmapFile(imageFile)) {
        urlProvider(imageFile)?.let { images.add(it) }
      }
      curr = curr.secondary
    }

    return LintIssue(
      id = issue.id,
      severityDescription = incident.severity?.description ?: "Unknown",
      message = incident.message ?: "",
      category = issue.category.fullName,
      priority = issue.priority,
      summary = issue.getBriefDescription(TextFormat.HTML) ?: "",
      explanation = issue.getExplanation(TextFormat.HTML) ?: "",
      location = primaryLocation,
      secondaryLocations = locations.drop(1),
      urls = issue.moreInfo,
      errorLine1 = errorLine1,
      errorLine2 = errorLine2,
      includedVariants = applicableVariants?.includedVariantNames ?: emptyList(),
      excludedVariants = applicableVariants?.excludedVariantNames ?: emptyList(),
      sourceContext = sourceContext,
      module = incident.project?.let { project -> project.buildModule?.modulePath ?: project.name }?.removePrefix(":") ?: "",
      packageName = pkgName,
      className = file.nameWithoutExtension,
      vendor = createLintVendor(issue),
      wasAutoFixed = incident.wasAutoFixed,
      images = images,
    )
  }

  private fun createLintCheck(issue: Issue, reason: String? = null): LintCheck {
    return LintCheck(
      id = issue.id,
      summary = issue.getBriefDescription(TextFormat.HTML) ?: "",
      explanation = issue.getExplanation(TextFormat.HTML) ?: "",
      category = issue.category.fullName,
      vendor = createLintVendor(issue),
      reason = reason,
    )
  }

  private fun parseErrorLines(errorLine: String?): Pair<String?, String?> {
    val lines = errorLine?.lines() ?: return null to null
    return lines.getOrNull(0) to lines.getOrNull(1)
  }

  private fun createLintVendor(issue: Issue): LintVendor? {
    val vendor = issue.vendor ?: issue.registry?.vendor
    return vendor
      ?.takeIf { it != AOSP_VENDOR }
      ?.let { LintVendor(name = it.vendorName, identifier = it.identifier, feedbackUrl = it.feedbackUrl, contact = it.contact) }
  }

  private fun getReportTimestamp(): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    val zonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())
    return zonedDateTime.format(formatter)
  }

  companion object {
    /** Maximum number of incidents shown per issue type */
    const val MAX_COUNT = 50
  }
}
