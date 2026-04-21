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

import com.android.tools.lint.renderer.LINTSCRIPT_JS
import com.android.tools.lint.renderer.STYLE_CSS
import com.android.tools.lint.renderer.data.LintCheck
import com.android.tools.lint.renderer.data.LintIssue
import com.android.tools.lint.renderer.data.LintLocation
import com.android.tools.lint.renderer.data.LintProject
import com.android.tools.lint.renderer.data.LintReport
import com.google.gson.GsonBuilder
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlReporterV2IndexTest {

  @Test
  fun testGetIndexHtml() {
    val reportData = "const lintReport = { 'issues': [] };"
    val html = getIndexHtml(reportData)

    // Check basic structure
    assertTrue(html.contains("<!DOCTYPE html>"))
    assertTrue(html.contains("<html lang=\"en\">"))
    assertTrue(html.contains("<title>Lint Report</title>"))

    // Check inclusion of CSS and JS
    assertTrue(html.contains(STYLE_CSS))
    assertTrue(html.contains(LINTSCRIPT_JS))
    assertTrue(html.contains(reportData))

    // Check for some of the new CSS classes/rules we added
    assertTrue(STYLE_CSS.contains(".text-green-600"))
    assertTrue(STYLE_CSS.contains(".hover\\:underline:hover"))
    assertTrue(STYLE_CSS.contains(".pl-level-0"))
    assertTrue(STYLE_CSS.contains(".pl-level-8"))
    assertTrue(STYLE_CSS.contains(".table-compact"))
    assertTrue(STYLE_CSS.contains(".segmented-control"))
    assertTrue(STYLE_CSS.contains(".search-container"))
    assertTrue(STYLE_CSS.contains(".search-input-wrapper"))
    assertTrue(STYLE_CSS.contains(".search-input"))

    // Check for some key JS logic
    assertTrue(LINTSCRIPT_JS.contains("this.lintReport.issues"))
    assertTrue(LINTSCRIPT_JS.contains("searchQuery: ''"))
    assertTrue(LINTSCRIPT_JS.contains("sort: { by: 'severity', order: 'desc' }"))
    assertTrue(LINTSCRIPT_JS.contains("this.escapeHTML(locationStr)"))

    // Check HTML structure matches what JS expects
    assertTrue(html.contains("id=\"project-name\""))
    assertTrue(html.contains("id=\"total-issues\""))
    assertTrue(html.contains("id=\"lint-data\""))
    assertTrue(html.contains("id=\"search-reveal-btn\""))
    assertTrue(html.contains("id=\"search-input\""))
    assertTrue(html.contains("id=\"ExtraIssues\""))
    assertTrue(html.contains("id=\"MissingIssues\""))
  }

  @Test
  fun testDensityControls() {
    val reportData = "const lintReport = { 'issues': [] };"
    val html = getIndexHtml(reportData)

    // Check for density control buttons
    assertTrue(html.contains("data-value=\"comfy\""))
    assertTrue(html.contains("data-value=\"compact\""))
    assertTrue(html.contains("data-tooltip=\"Comfortable Density\""))
    assertTrue(html.contains("data-tooltip=\"Compact Density\""))

    // Check default starting state: Comfortable Density is active by default
    assertTrue(html.contains("<button data-value=\"comfy\" class=\"segment-btn active\""))
    assertTrue(LINTSCRIPT_JS.contains("density: 'comfy'"))

    // Check that LINTSCRIPT_JS caches and binds events for density segments
    assertTrue(LINTSCRIPT_JS.contains("densitySegments: document.getElementById('density-segments')"))
    assertTrue(LINTSCRIPT_JS.contains("this.setupDensitySegments"))
  }

  @Test
  fun testSearchControl() {
    val reportData = "const lintReport = { 'issues': [] };"
    val html = getIndexHtml(reportData)

    // Check for search components
    assertTrue(html.contains("id=\"search-reveal-btn\""))
    assertTrue(html.contains("id=\"search-input\""))
    assertTrue(html.contains("id=\"search-clear-btn\""))

    // Check search-related JS
    assertTrue(LINTSCRIPT_JS.contains("searchQuery: ''"))
    assertTrue(LINTSCRIPT_JS.contains("searchRevealBtn: document.getElementById('search-reveal-btn')"))
    assertTrue(LINTSCRIPT_JS.contains("searchWrapper: document.getElementById('search-wrapper')"))
    assertTrue(LINTSCRIPT_JS.contains("this.matchesSearch(i, this.state.searchQuery)"))
    assertTrue(LINTSCRIPT_JS.contains("matchesSearch(issue, query)"))
    assertTrue(LINTSCRIPT_JS.contains("debounce(func, wait)"))
  }

  @Test
  fun testGetIndexHtmlWithFullReport() {
    val report =
      LintReport(
        name = "Comprehensive Report",
        timeStamp = "2026-05-20 12:00:00",
        issues =
          listOf(
            LintIssue(
              id = "TestIssue",
              severityDescription = "Error",
              message = "This is a test message with \"quotes\"",
              category = "Correctness",
              priority = 5,
              summary = "Test summary",
              explanation = "Test explanation",
              location = LintLocation("src/Test.kt", 10, 5),
              secondaryLocations = listOf(LintLocation("src/Other.kt", 20, 1)),
              wasAutoFixed = true,
              includedVariants = listOf("debug"),
              excludedVariants = listOf("release"),
              vendor = "Android Open Source Project",
            )
          ),
        numberOfIssues = 1,
        lintVersion = "8.6.0",
        additionalChecks = listOf(LintCheck(id = "AdditionalId", summary = "Additional summary", category = "Security", vendor = "Google")),
        disabledChecks = listOf(LintCheck(id = "DisabledId", summary = "Disabled summary", reason = "Explicitly disabled")),
        projects = listOf(LintProject(name = "app", relativePath = "app/", errorCount = 0, warningCount = 1)),
      )
    val json = GsonBuilder().create().toJson(report)
    val reportData = "const lintReport = $json;"
    val html = getIndexHtml(reportData)

    assertTrue(html.contains(reportData))
    assertTrue(html.contains("\"id\":\"TestIssue\""))
    assertTrue(html.contains("\"severityDescription\":\"Error\""))
    assertTrue(html.contains("\"message\":\"This is a test message with \\\"quotes\\\"\""))
    assertTrue(html.contains("\"wasAutoFixed\":true"))
    assertTrue(html.contains("\"vendor\":\"Android Open Source Project\""))
    assertTrue(html.contains("src/Test.kt"))
    assertTrue(html.contains("src/Other.kt"))
    assertTrue(html.contains("\"id\":\"AdditionalId\""))
    assertTrue(html.contains("\"id\":\"DisabledId\""))
    assertTrue(html.contains("\"name\":\"app\""))
    assertTrue(html.contains("\"lintVersion\":\"8.6.0\""))
  }

  @Test
  fun testGetIndexHtmlNoIssues() {
    val report = LintReport(name = "Lint Report", timeStamp = "2026-05-20", issues = emptyList(), numberOfIssues = 0)
    val json = GsonBuilder().create().toJson(report)
    val reportData = "const lintReport = $json;"
    val html = getIndexHtml(reportData)

    assertTrue(html.contains(reportData))
    assertTrue(html.contains("\"issues\":[]"))
  }

  @Test
  fun testGetIndexHtmlWithMultipleIssues() {
    val report =
      LintReport(
        name = "Multiple Issues Report",
        timeStamp = "2026-05-20",
        issues =
          listOf(
            LintIssue(
              id = "Issue1",
              severityDescription = "Error",
              message = "Message 1",
              category = "Correctness",
              priority = 5,
              summary = "Summary 1",
              explanation = "Explanation 1",
              location = LintLocation("File1.kt", 1, 1),
            ),
            LintIssue(
              id = "Issue2",
              severityDescription = "Warning",
              message = "Message 2",
              category = "Performance",
              priority = 3,
              summary = "Summary 2",
              explanation = "Explanation 2",
              location = LintLocation("File2.kt", 10, 1),
            ),
          ),
        numberOfIssues = 2,
      )
    val json = GsonBuilder().create().toJson(report)
    val reportData = "const lintReport = $json;"
    val html = getIndexHtml(reportData)

    assertTrue(html.contains("\"id\":\"Issue1\""))
    assertTrue(html.contains("\"id\":\"Issue2\""))
  }

  @Test
  fun testGetIndexHtmlWithUrls() {
    val report =
      LintReport(
        name = "Report with URLs",
        timeStamp = "2026-05-20",
        issues =
          listOf(
            LintIssue(
              id = "UrlIssue",
              severityDescription = "Error",
              message = "Message",
              category = "Correctness",
              priority = 5,
              summary = "Summary",
              explanation = "Explanation",
              location = LintLocation("File.kt", 1, 1),
              urls = listOf("https://example.com/1", "https://example.com/2"),
            )
          ),
        numberOfIssues = 1,
      )
    val json = GsonBuilder().create().toJson(report)
    val reportData = "const lintReport = $json;"
    val html = getIndexHtml(reportData)

    assertTrue(html.contains("\"urls\":[\"https://example.com/1\",\"https://example.com/2\"]"))
    // Also verify that LINTSCRIPT_JS contains the logic to render them
    assertTrue(LINTSCRIPT_JS.contains("issue.urls"))
    assertTrue(LINTSCRIPT_JS.contains("more-info-list"))
    // And STYLE_CSS contains the style
    assertTrue(STYLE_CSS.contains(".more-info-list"))
  }

  @Test
  fun testSeverityFilter() {
    val reportData = "const lintReport = { 'issues': [] };"
    val html = getIndexHtml(reportData)

    // Check for the "Add Filter" button and its container
    assertTrue(html.contains("class=\"relative\" id=\"add-filter-container\""))
    assertTrue(html.contains("id=\"add-filter-btn\""))
    assertTrue(html.contains("Add Filter"))

    // Check for the "Add Filter" dropdown
    assertTrue(html.contains("id=\"add-filter-dropdown\" class=\"dropdown-menu hidden\""))
    assertTrue(html.contains("data-filter-type=\"severity\""))
    assertTrue(html.contains("Severity</div>"))

    // Check for the severity filter chip (hidden by default)
    assertTrue(html.contains("id=\"sev-chip-container\" class=\"hidden\""))
    assertTrue(html.contains("id=\"sev-filter-btn\" class=\"flex items-center cursor-pointer\""))
    assertTrue(html.contains("id=\"sev-filter-text\""))
    assertTrue(html.contains("Severity: All"))

    // Check for the severity dropdown list container
    assertTrue(html.contains("id=\"sev-filter-list\""))

    // Check for the remove filter button
    assertTrue(html.contains("id=\"remove-severity-filter\""))

    // Verify LINTSCRIPT_JS contains filter state and logic
    assertTrue(LINTSCRIPT_JS.contains("filters: { severities: [], categories: [], modules: [] }"))
    assertTrue(LINTSCRIPT_JS.contains("isSeverityAdded: false"))
    assertTrue(LINTSCRIPT_JS.contains("getFilteredIssues(issues)"))

    // Verify STYLE_CSS contains filter styles
    assertTrue(STYLE_CSS.contains(".add-filter-btn"))
    assertTrue(STYLE_CSS.contains(".filter-chip"))
    assertTrue(STYLE_CSS.contains(".dropdown-menu"))
    assertTrue(STYLE_CSS.contains(".popover-item"))
  }

  @Test
  fun testCategoryFilter() {
    val reportData = "const lintReport = { 'issues': [] };"
    val html = getIndexHtml(reportData)

    // Check for the category filter option in the "Add Filter" dropdown
    assertTrue(html.contains("data-filter-type=\"category\""))
    assertTrue(html.contains("Category</div>"))

    // Check for the category filter chip (hidden by default)
    assertTrue(html.contains("id=\"cat-chip-container\" class=\"hidden\""))
    assertTrue(html.contains("id=\"cat-filter-btn\" class=\"flex items-center cursor-pointer\""))
    assertTrue(html.contains("id=\"cat-filter-text\""))
    assertTrue(html.contains("Category: All"))

    // Check for the category dropdown list container
    assertTrue(html.contains("id=\"cat-filter-list\""))

    // Check for the remove filter button
    assertTrue(html.contains("id=\"remove-category-filter\""))

    // Verify LINTSCRIPT_JS contains filter state and logic for categories
    assertTrue(LINTSCRIPT_JS.contains("filters: { severities: [], categories: [], modules: [] }"))
    assertTrue(LINTSCRIPT_JS.contains("isCategoryAdded: false"))
    assertTrue(LINTSCRIPT_JS.contains("if (this.state.filters.categories.length > 0)"))
  }

  @Test
  fun testModuleFilter() {
    val reportData = "const lintReport = { 'issues': [] };"
    val html = getIndexHtml(reportData)

    // Check for the module filter option in the "Add Filter" dropdown
    assertTrue(html.contains("data-filter-type=\"module\""))
    assertTrue(html.contains("Module</div>"))

    // Check for the module filter chip (hidden by default)
    assertTrue(html.contains("id=\"mod-chip-container\" class=\"hidden\""))
    assertTrue(html.contains("id=\"mod-filter-btn\" class=\"flex items-center cursor-pointer\""))
    assertTrue(html.contains("id=\"mod-filter-text\""))
    assertTrue(html.contains("Module: All"))

    // Check for the module dropdown list container
    assertTrue(html.contains("id=\"mod-filter-list\""))

    // Check for the remove filter button
    assertTrue(html.contains("id=\"remove-module-filter\""))

    // Verify LINTSCRIPT_JS contains filter state and logic for modules
    assertTrue(LINTSCRIPT_JS.contains("filters: { severities: [], categories: [], modules: [] }"))
    assertTrue(LINTSCRIPT_JS.contains("isModuleAdded: false"))
    assertTrue(LINTSCRIPT_JS.contains("if (this.state.filters.modules.length > 0)"))
  }

  @Test
  fun testGroupByDropdown() {
    val reportData = "const lintReport = { 'issues': [] };"
    val html = getIndexHtml(reportData)

    // Check for the "Group By" row
    assertTrue(html.contains("class=\"breadcrumb-row-style\""))

    // Check for the "Group By" button and label
    assertTrue(html.contains("id=\"group-by-btn\" class=\"group-by-btn-style\""))
    assertTrue(html.contains("<span class=\"group-by-label\">Group By:</span>"))
    assertTrue(html.contains("id=\"group-by-text\" class=\"group-by-value\">Issues</span>"))

    // Check for the "Group By" dropdown
    assertTrue(html.contains("id=\"group-by-dropdown\" class=\"dropdown-menu right-0 hidden group-by-dropdown-style\""))
    assertTrue(html.contains("<div class=\"dropdown-item\" data-value=\"issues\">Issues</div>"))

    // Verify LINTSCRIPT_JS contains group-by caching and logic
    assertTrue(LINTSCRIPT_JS.contains("groupByBtn: document.getElementById('group-by-btn')"))
    assertTrue(LINTSCRIPT_JS.contains("groupByText: document.getElementById('group-by-text')"))
    assertTrue(LINTSCRIPT_JS.contains("groupByDropdown: document.getElementById('group-by-dropdown')"))
    assertTrue(LINTSCRIPT_JS.contains("this.state.currentView = target.dataset.value"))

    // Verify STYLE_CSS contains group-by styles
    assertTrue(STYLE_CSS.contains(".group-by-btn-style"))
    assertTrue(STYLE_CSS.contains(".group-by-label"))
    assertTrue(STYLE_CSS.contains(".group-by-value"))
    assertTrue(STYLE_CSS.contains(".group-by-icon"))
    assertTrue(STYLE_CSS.contains(".breadcrumb-row-style"))
  }

  @Test
  fun testImages() {
    val report =
      LintReport(
        name = "Report with Images",
        timeStamp = "2026-05-20",
        issues =
          listOf(
            LintIssue(
              id = "ImageIssue",
              severityDescription = "Error",
              message = "Message",
              category = "Correctness",
              priority = 5,
              summary = "Summary",
              explanation = "Explanation",
              location = LintLocation("File.kt", 1, 1),
              images = listOf("http://example.com/icon1.png", "http://example.com/icon2.jpg"),
            )
          ),
        numberOfIssues = 1,
      )
    val json = GsonBuilder().create().toJson(report)
    val reportData = "const lintReport = $json;"
    val html = getIndexHtml(reportData)

    assertTrue(html.contains("\"images\":[\"http://example.com/icon1.png\",\"http://example.com/icon2.jpg\"]"))
    // Also verify that LINTSCRIPT_JS contains the logic to render them
    assertTrue(LINTSCRIPT_JS.contains("issue.images"))
    assertTrue(LINTSCRIPT_JS.contains("<img src=\"\${this.escapeHTML(url)}\""))
    // And STYLE_CSS contains the style
    assertTrue(STYLE_CSS.contains(".h-32"))
    assertTrue(STYLE_CSS.contains(".object-contain"))
  }
}
