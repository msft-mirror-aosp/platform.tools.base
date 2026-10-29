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
 * table per module, test suite and variant, failing tests with their stack traces, and passed tests as counts per class rather than one row
 * per test. Each unique stack trace is printed once, and long ones are truncated.
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
      appendFailures(executions.filter { it.status == STATUS_FAIL })
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

  /**
   * Appends one entry per failing test. Runs of the same test that failed with the same stack trace, for example in several variants, share
   * an entry, and a stack trace that was already printed for another test is referenced instead of printed again. Each entry's title starts
   * with the module, so tests with the same class name in different modules can be told apart.
   */
  private fun StringBuilder.appendFailures(failures: List<Execution>) {
    if (failures.isEmpty()) return
    appendLine("## Failures (${failures.size})")
    appendLine()
    val firstTestWithStackTrace = mutableMapOf<String, String>()
    failures
      .groupBy { FailureKey(it.module, it.className, it.testName, it.stackTrace) }
      .entries
      .sortedWith(compareBy({ it.key.module }, { it.key.className }, { it.key.testName }, { it.key.stackTrace.orEmpty() }))
      .forEach { (key, runs) ->
        val title = testTitle(key.module, key.className, key.testName)
        val stackTrace = key.stackTrace
        appendLine("### $title")
        appendLine()
        appendLine("Failed in: ${runs.map(::describeRun).distinct().sorted().joinToString(", ")}.")
        appendLine()
        when {
          stackTrace == null -> appendLine("No stack trace was recorded.")
          stackTrace in firstTestWithStackTrace -> appendLine("Same stack trace as ${firstTestWithStackTrace.getValue(stackTrace)}.")
          else -> {
            firstTestWithStackTrace[stackTrace] = title
            appendCodeBlock(truncateStackTrace(stackTrace))
          }
        }
        appendLine()
      }
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

  /** Groups runs of the same failing test that share a stack trace. */
  private data class FailureKey(val module: String, val className: String, val testName: String, val stackTrace: String?)

  internal companion object {

    /** Identifies a test across modules, for example `:app > com.example.MathTest > divides`. */
    fun testTitle(module: String, className: String, testName: String): String = "$module > $className > $testName"

    /** Stack traces longer than this many lines are truncated. */
    const val MAX_STACK_TRACE_LINES = 40

    /** Number of leading lines kept from a truncated stack trace, besides its `Caused by:` lines. */
    const val KEPT_STACK_TRACE_LINES = 25

    /**
     * Returns [stackTrace] unchanged if it has at most [MAX_STACK_TRACE_LINES] lines. Otherwise, keeps the first [KEPT_STACK_TRACE_LINES]
     * lines and every later `Caused by:` line, so the root cause is still visible.
     */
    fun truncateStackTrace(stackTrace: String): String {
      val lines = stackTrace.lines()
      if (lines.size <= MAX_STACK_TRACE_LINES) return stackTrace
      val causes = lines.drop(KEPT_STACK_TRACE_LINES).filter { it.trimStart().startsWith("Caused by:") }
      val omitted = lines.size - KEPT_STACK_TRACE_LINES - causes.size
      return (lines.take(KEPT_STACK_TRACE_LINES) + "\t... $omitted lines omitted" + causes).joinToString("\n")
    }

    /** Describes where [run] ran, for example `unitTest / debug` or `connected / debug on Pixel_8`. */
    fun describeRun(run: Execution): String =
      if (run.target == UNKNOWN_TARGET) "${run.suite} / ${run.variant}" else "${run.suite} / ${run.variant} on ${run.target}"

    /** Appends [text] as a fenced code block, with a fence longer than any run of backticks in [text]. */
    fun StringBuilder.appendCodeBlock(text: String) {
      val longestBacktickRun = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
      val fence = "`".repeat(maxOf(3, longestBacktickRun + 1))
      appendLine(fence)
      appendLine(text)
      appendLine(fence)
    }

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
