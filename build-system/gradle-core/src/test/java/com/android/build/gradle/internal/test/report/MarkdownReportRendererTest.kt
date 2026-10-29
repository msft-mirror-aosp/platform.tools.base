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
import org.junit.Test

class MarkdownReportRendererTest {

  private val renderer = MarkdownReportRenderer()

  @Test
  fun render_allPassing() {
    val report =
      report(
        module(
          ":app",
          testClass("com.example.app.MathTest", testCase("adds"), testCase("subtracts")),
          testClass("com.example.app.StringTest", testCase("concatenates")),
        )
      )

    assertThat(renderer.render(report))
      .isEqualTo(
        """
        ---
        project: ":app"
        total: 3
        passed: 3
        failed: 0
        skipped: 0
        ---

        # Test report: :app

        ## Summary

        | Module | Suite | Variant | Total | Passed | Failed | Skipped |
        | :--- | :--- | :--- | ---: | ---: | ---: | ---: |
        | :app | unitTest | debug | 3 | 3 | 0 | 0 |

        ## Passed (3)

        | Class | Passed |
        | :--- | ---: |
        | com.example.app.MathTest | 2 |
        | com.example.app.StringTest | 1 |

        """
          .trimIndent()
      )
  }

  @Test
  fun render_noTests() {
    val output = renderer.render(report())

    assertThat(output).contains("total: 0")
    assertThat(output).contains("No test results were found.")
    assertThat(output).doesNotContain("## Summary")
  }

  @Test
  fun render_countsEveryStatus() {
    val report =
      report(
        module(
          ":app",
          testClass(
            "com.example.app.MathTest",
            testCase("adds"),
            testCase("divides", status = STATUS_FAIL),
            testCase("ignored", status = STATUS_SKIPPED),
          ),
        )
      )

    val output = renderer.render(report)

    assertThat(output).contains("total: 3\npassed: 1\nfailed: 1\nskipped: 1\n")
    assertThat(output).contains("| :app | unitTest | debug | 3 | 1 | 1 | 1 |")
    assertThat(output).contains("## Passed (1)")
  }

  @Test
  fun render_summaryHasOneRowPerModuleSuiteAndVariant() {
    val report =
      report(
        module(":app", testClass("com.example.app.MathTest", testCase("adds", variants = listOf("debug", "release")))),
        module(":lib", testClass("com.example.lib.LibTest", testCase("works", suite = "screenshotTest"))),
      )

    val output = renderer.render(report)

    assertThat(output).contains("| :app | unitTest | debug | 1 | 1 | 0 | 0 |\n| :app | unitTest | release | 1 | 1 | 0 | 0 |")
    assertThat(output).contains("| :lib | screenshotTest | debug | 1 | 1 | 0 | 0 |")
    assertThat(output).contains("total: 3")
  }

  @Test
  fun render_countsEachTarget() {
    val report = report(module(":app", testClass("com.example.app.UiTest", testCase("opens", targets = listOf("Pixel_8", "Pixel_9")))))

    val output = renderer.render(report)

    assertThat(output).contains("total: 2")
    assertThat(output).contains("| com.example.app.UiTest | 2 |")
  }

  @Test
  fun render_escapesTableCellsAndYaml() {
    val report = report(module(":app", testClass("Weird|Class", testCase("adds"))), projectName = "My \"Project\"")

    val output = renderer.render(report)

    assertThat(output).contains("project: \"My \\\"Project\\\"\"")
    assertThat(output).contains("| Weird\\|Class | 1 |")
  }

  @Test
  fun render_classInDefaultPackage() {
    val report = report(module(":app", testClass("NoPackageTest", testCase("adds"))))

    assertThat(renderer.render(report)).contains("| NoPackageTest | 1 |")
  }

  @Test
  fun render_failureWithStackTrace() {
    val stackTrace = "java.lang.AssertionError: expected:<0> but was:<3>\n\tat com.example.app.MathTest.divides(MathTest.kt:12)"
    val report =
      report(
        module(":app", testClass("com.example.app.MathTest", testCase("adds"), testCase("divides", STATUS_FAIL, stackTrace = stackTrace)))
      )

    assertThat(renderer.render(report))
      .contains(
        """
        ## Failures (1)

        ### :app > com.example.app.MathTest > divides

        Failed in: unitTest / debug.

        ```
        java.lang.AssertionError: expected:<0> but was:<3>
        	at com.example.app.MathTest.divides(MathTest.kt:12)
        ```

        ## Passed (1)
        """
          .trimIndent()
      )
  }

  @Test
  fun render_groupsRunsOfTheSameFailure() {
    val report =
      report(
        module(
          ":app",
          testClass(
            "com.example.app.UiTest",
            testCase("opens", STATUS_FAIL, variants = listOf("debug", "release"), targets = listOf("Pixel_8"), stackTrace = "Boom"),
          ),
        )
      )

    val output = renderer.render(report)

    assertThat(output).contains("## Failures (2)")
    assertThat(output).contains("Failed in: unitTest / debug on Pixel_8, unitTest / release on Pixel_8.")
    assertThat(output.split("### :app > com.example.app.UiTest > opens")).hasSize(2)
  }

  @Test
  fun render_printsEachStackTraceOnce() {
    val report =
      report(
        module(
          ":app",
          testClass(
            "com.example.app.MathTest",
            testCase("divides", STATUS_FAIL, stackTrace = "java.lang.IllegalStateException: shared"),
            testCase("multiplies", STATUS_FAIL, stackTrace = "java.lang.IllegalStateException: shared"),
          ),
        )
      )

    val output = renderer.render(report)

    assertThat(output.split("java.lang.IllegalStateException: shared")).hasSize(2)
    assertThat(output).contains("Same stack trace as :app > com.example.app.MathTest > divides.")
  }

  @Test
  fun render_identifiesTheModuleOfEachFailure() {
    val report =
      report(
        module(":app", testClass("com.example.MathTest", testCase("divides", STATUS_FAIL, stackTrace = "Boom"))),
        module(":lib", testClass("com.example.MathTest", testCase("divides", STATUS_FAIL, stackTrace = "Boom"))),
      )

    val output = renderer.render(report)

    assertThat(output).contains("### :app > com.example.MathTest > divides")
    assertThat(output).contains("### :lib > com.example.MathTest > divides")
    assertThat(output).contains("Same stack trace as :app > com.example.MathTest > divides.")
  }

  @Test
  fun render_failureWithoutStackTrace() {
    val report = report(module(":app", testClass("com.example.app.MathTest", testCase("divides", STATUS_FAIL))))

    assertThat(renderer.render(report)).contains("No stack trace was recorded.")
  }

  @Test
  fun render_fenceIsLongerThanBackticksInStackTrace() {
    val report =
      report(module(":app", testClass("com.example.app.MathTest", testCase("divides", STATUS_FAIL, stackTrace = "Expected ```a```"))))

    assertThat(renderer.render(report)).contains("````\nExpected ```a```\n````")
  }

  @Test
  fun render_screenshotMismatch() {
    val report =
      report(
        module(
          ":app",
          testClass("com.example.app.HomeScreenTest", screenshotFailure("HomeScreenPreview_dark", MISMATCH_TRACE, diffPercent = "0.0482")),
        ),
        relativeRootDir = "../../../../",
      )

    val output = renderer.render(report)

    assertThat(output)
      .contains(
        """
        ## Screenshot failures (1)

        | Test | Variant | Result | Diff | Reference | Actual | Diff image |
        | :--- | :--- | :--- | ---: | :--- | :--- | :--- |
        | :app > com.example.app.HomeScreenTest > HomeScreenPreview_dark | debug | Mismatch | 4.82% | ![Reference](../../../../app/src/screenshotTestDebug/reference/HomeScreenPreview_dark.png) | ![Actual](../../../../app/build/outputs/screenshotTest/rendered/HomeScreenPreview_dark.png) | ![Diff](../../../../app/build/outputs/screenshotTest/diffs/HomeScreenPreview_dark.png) |
        """
          .trimIndent()
      )
    assertThat(output).doesNotContain("## Failures")
    assertThat(output).contains("failed: 1")
  }

  @Test
  fun render_screenshotMissingBaseline() {
    val report =
      report(module(":app", testClass("com.example.app.SettingsTest", screenshotFailure("SettingsPreview", MISSING_BASELINE_TRACE))))

    val output = renderer.render(report)

    assertThat(output)
      .contains(
        "| :app > com.example.app.SettingsTest > SettingsPreview | debug | Missing baseline |  |  | " +
          "![Actual](app/build/outputs/screenshotTest/rendered/SettingsPreview.png) |  |"
      )
  }

  @Test
  fun render_screenshotRenderErrorIsListedWithFailures() {
    val report =
      report(
        module(
          ":app",
          testClass(
            "com.example.app.BrokenTest",
            screenshotFailure("BrokenPreview", "com.android.tools.screenshot.ScreenshotRenderException: Screenshot rendering failed"),
          ),
        )
      )

    val output = renderer.render(report)

    assertThat(output).doesNotContain("## Screenshot failures")
    assertThat(output).contains("### :app > com.example.app.BrokenTest > BrokenPreview")
  }

  @Test
  fun formatDiffPercent_convertsTheEngineFractionToAPercentage() {
    assertThat(MarkdownReportRenderer.formatDiffPercent("0.0482")).isEqualTo("4.82%")
    assertThat(MarkdownReportRenderer.formatDiffPercent("1.0")).isEqualTo("100.00%")
    assertThat(MarkdownReportRenderer.formatDiffPercent("0.00004")).isEqualTo("0.00%")
    assertThat(MarkdownReportRenderer.formatDiffPercent("not a number")).isEqualTo("not a number")
  }

  @Test
  fun formatDiffPercent_hasNoBinaryRoundingArtifacts() {
    // As doubles, 0.07 * 100 and 0.29 * 100 are 7.000000000000001 and 28.999999999999996.
    assertThat(MarkdownReportRenderer.formatDiffPercent("0.07")).isEqualTo("7.00%")
    assertThat(MarkdownReportRenderer.formatDiffPercent("0.29")).isEqualTo("29.00%")
    // Ties round to even.
    assertThat(MarkdownReportRenderer.formatDiffPercent("0.00125")).isEqualTo("0.12%")
  }

  @Test
  fun formatDiffPercent_returnsNonFiniteValuesUnchanged() {
    assertThat(MarkdownReportRenderer.formatDiffPercent("NaN")).isEqualTo("NaN")
    assertThat(MarkdownReportRenderer.formatDiffPercent("Infinity")).isEqualTo("Infinity")
    assertThat(MarkdownReportRenderer.formatDiffPercent("-Infinity")).isEqualTo("-Infinity")
  }

  @Test
  fun image_wrapsPathsWithSpacesAndKeepsAbsolutePaths() {
    assertThat(MarkdownReportRenderer.image("Actual", "my app/shot (1).png", "../")).isEqualTo("![Actual](<../my app/shot (1).png>)")
    assertThat(MarkdownReportRenderer.image("Actual", "/tmp/shot.png", "../")).isEqualTo("![Actual](/tmp/shot.png)")
    assertThat(MarkdownReportRenderer.image("Actual", "C:\\shots\\shot.png", "../")).isEqualTo("![Actual](C:/shots/shot.png)")
    assertThat(MarkdownReportRenderer.image("Actual", null, "../")).isEmpty()
  }

  @Test
  fun truncateStackTrace_keepsShortStackTraces() {
    val stackTrace = (1..MarkdownReportRenderer.MAX_STACK_TRACE_LINES).joinToString("\n") { "\tat Frame$it" }

    assertThat(MarkdownReportRenderer.truncateStackTrace(stackTrace)).isEqualTo(stackTrace)
  }

  @Test
  fun truncateStackTrace_keepsLeadingLinesAndCauses() {
    val lines = (1..60).map { "\tat Frame$it" }.toMutableList()
    lines[0] = "java.lang.RuntimeException: outer"
    lines[45] = "Caused by: java.lang.IllegalStateException: inner"

    val truncated = MarkdownReportRenderer.truncateStackTrace(lines.joinToString("\n")).lines()

    assertThat(truncated).hasSize(MarkdownReportRenderer.KEPT_STACK_TRACE_LINES + 2)
    assertThat(truncated.first()).isEqualTo("java.lang.RuntimeException: outer")
    assertThat(truncated[MarkdownReportRenderer.KEPT_STACK_TRACE_LINES]).isEqualTo("\t... 34 lines omitted")
    assertThat(truncated.last()).isEqualTo("Caused by: java.lang.IllegalStateException: inner")
  }
}

private const val MISMATCH_TRACE =
  "com.android.tools.screenshot.ImageComparisonAssertionError: Image does not match.\n\tat com.android.tools.screenshot.Engine.verify(Engine.kt:1)"

private const val MISSING_BASELINE_TRACE =
  "com.android.tools.screenshot.ScreenshotImageNotFoundException: Reference image file does not exist (app/src/screenshotTestDebug/reference/x.png)."

/**
 * A failed screenshot test with the image properties the screenshot test engine publishes. Like the engine, it always sets `refImagePath`,
 * and it also sets a diff image path, which may be left over from an earlier run.
 */
private fun screenshotFailure(previewName: String, stackTrace: String, diffPercent: String? = null): TestCaseSpec =
  testCase(
    previewName,
    STATUS_FAIL,
    stackTrace = stackTrace,
    result =
      VariantTestResult(
        status = STATUS_FAIL,
        diffPercent = diffPercent,
        previewName = previewName,
        refImagePath = "app/src/screenshotTestDebug/reference/$previewName.png",
        newImagePath = "app/build/outputs/screenshotTest/rendered/$previewName.png",
        diffImagePath = "app/build/outputs/screenshotTest/diffs/$previewName.png",
      ),
  )

/** A test case to add to a [testClass]: one result per variant and target. */
private data class TestCaseSpec(
  val name: String,
  val status: String,
  val variants: List<String>,
  val targets: List<String>,
  val suite: String,
  val stackTrace: String?,
  val result: VariantTestResult?,
)

private fun testCase(
  name: String,
  status: String = STATUS_PASS,
  variants: List<String> = listOf("debug"),
  targets: List<String> = listOf(UNKNOWN_TARGET),
  suite: String = "unitTest",
  stackTrace: String? = null,
  result: VariantTestResult? = null,
) = TestCaseSpec(name, status, variants, targets, suite, stackTrace, result)

private fun testClass(fullName: String, vararg testCases: TestCaseSpec): Pair<String, List<TestCaseSpec>> = fullName to testCases.toList()

private fun module(name: String, vararg classes: Pair<String, List<TestCaseSpec>>): Module {
  val packages =
    classes
      .groupBy { (fullName, _) -> fullName.substringBeforeLast('.', "") }
      .map { (packageName, packageClasses) ->
        Package(
          name = packageName,
          testSuiteSummaries = emptyList(),
          classes =
            packageClasses.map { (fullName, specs) ->
              ClassType(name = fullName.substringAfterLast('.'), testSuiteSummaries = emptyList(), testCases = specs.map(::toTestCase))
            },
        )
      }
  return Module(name = name, testSuiteSummaries = emptyList(), packages = packages)
}

private fun toTestCase(spec: TestCaseSpec): TestCase {
  val stackTraceId = spec.stackTrace?.let { "st-${it.hashCode()}" }
  val result = spec.result ?: VariantTestResult(status = spec.status)
  val targets =
    spec.targets.map { targetName ->
      Target(
        name = targetName,
        testSuiteSummaries = emptyList(),
        testSuiteResults =
          listOf(
            TestSuiteTestResult(spec.suite, spec.variants.associateWith { result.copy(stackTraceId = stackTraceId ?: result.stackTraceId) })
          ),
        commonStackTraces =
          if (spec.stackTrace != null) listOf(StackTraceGroup(stackTraceId!!, spec.stackTrace, mapOf(spec.suite to spec.variants)))
          else emptyList(),
      )
    }
  return TestCase(name = spec.name, targets = targets)
}

private fun report(vararg modules: Module, projectName: String = ":app", relativeRootDir: String = ""): RootReport =
  RootReport(
    projectName = projectName,
    timestamp = "Oct 29, 2026, 5:00 PM",
    numberOfModules = modules.size,
    numberOfPackages = modules.sumOf { it.packages.size },
    numberOfClasses = modules.sumOf { module -> module.packages.sumOf { it.classes.size } },
    variants = emptyList(),
    testSuites = emptyList(),
    targets = emptyList(),
    modules = modules.toList(),
    relativeRootDir = relativeRootDir,
  )
