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

/**
 * Renders a [RootReport] as Markdown, the content of `report.md`.
 *
 * The report is meant to be read by coding agents and in terminals, so it stays small: a YAML front matter block with the totals, a summary
 * table per module, test suite and variant, and passed tests as counts per class rather than one row per test.
 */
class MarkdownReportRenderer {

  /** Returns the Markdown for [report]. */
  fun render(report: RootReport): String {
    val executions = collectExecutions(report)
    val markdown = buildString {
      appendFrontMatter(report, executions)
      appendLine("# Test report: ${report.projectName}")
      appendLine()
      if (executions.isEmpty()) {
        appendLine("No test results were found.")
        return@buildString
      }
      appendSummary(executions)
      appendPassed(executions)
    }
    // Each section ends with a blank line; end the file with a single newline instead.
    return markdown.trimEnd('\n') + "\n"
  }

  private fun StringBuilder.appendFrontMatter(report: RootReport, executions: List<Execution>) {
    appendLine("---")
    appendLine("project: \"${escapeYaml(report.projectName)}\"")
    appendLine("total: ${executions.size}")
    appendLine("passed: ${executions.count { it.status == STATUS_PASS }}")
    appendLine("failed: ${executions.count { it.status == STATUS_FAIL }}")
    appendLine("skipped: ${executions.count { it.status == STATUS_SKIPPED }}")
    appendLine("---")
    appendLine()
  }

  private fun StringBuilder.appendSummary(executions: List<Execution>) {
    appendLine("## Summary")
    appendLine()
    appendLine("| Module | Suite | Variant | Total | Passed | Failed | Skipped |")
    appendLine("| :--- | :--- | :--- | ---: | ---: | ---: | ---: |")
    executions
      .groupBy { Triple(it.module, it.suite, it.variant) }
      .toSortedMap(compareBy({ it.first }, { it.second }, { it.third }))
      .forEach { (key, group) ->
        val (module, suite, variant) = key
        appendLine(
          "| ${cell(module)} | ${cell(suite)} | ${cell(variant)} | ${group.size} | ${group.count { it.status == STATUS_PASS }} | " +
            "${group.count { it.status == STATUS_FAIL }} | ${group.count { it.status == STATUS_SKIPPED }} |"
        )
      }
    appendLine()
  }

  private fun StringBuilder.appendPassed(executions: List<Execution>) {
    val passedByClass = executions.filter { it.status == STATUS_PASS }.groupingBy { it.className }.eachCount().toSortedMap()
    if (passedByClass.isEmpty()) return
    appendLine("## Passed (${passedByClass.values.sum()})")
    appendLine()
    appendLine("| Class | Passed |")
    appendLine("| :--- | ---: |")
    passedByClass.forEach { (className, count) -> appendLine("| ${cell(className)} | $count |") }
    appendLine()
  }

  /** One run of a test case: a test case on one target, in one test suite and variant. */
  internal data class Execution(
    val module: String,
    val className: String,
    val testName: String,
    val target: String,
    val suite: String,
    val variant: String,
    val status: String,
    val result: VariantTestResult,
    val stackTrace: String?,
  )

  internal companion object {

    /** Flattens [report] into one [Execution] per test case, target, test suite and variant. */
    fun collectExecutions(report: RootReport): List<Execution> =
      report.modules.flatMap { module ->
        module.packages.flatMap { pkg ->
          pkg.classes.flatMap { clazz ->
            val className = if (pkg.name.isEmpty()) clazz.name else "${pkg.name}.${clazz.name}"
            clazz.testCases.flatMap { testCase ->
              testCase.targets.flatMap { target ->
                val stackTraces = target.commonStackTraces.associate { it.id to it.stackTrace }
                target.testSuiteResults.flatMap { suiteResult ->
                  suiteResult.variantResults.map { (variant, result) ->
                    Execution(
                      module = module.name,
                      className = className,
                      testName = testCase.name,
                      target = target.name,
                      suite = suiteResult.testSuiteName,
                      variant = variant,
                      status = result.status,
                      result = result,
                      stackTrace = result.stackTraceId?.let { stackTraces[it] },
                    )
                  }
                }
              }
            }
          }
        }
      }

    /** Escapes [text] for use in a Markdown table cell. */
    fun cell(text: String): String = text.replace("|", "\\|").replace(Regex("\\s*\\R\\s*"), " ")

    /** Escapes [text] for use in a double-quoted YAML string. */
    fun escapeYaml(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")
  }
}
