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

package com.android.tools.render.compose

import com.android.testutils.TestUtils
import com.android.tools.render.common.PreviewRendering
import com.android.tools.render.common.RenderProblem
import com.android.tools.render.common.ScreenshotError
import com.android.tools.render.common.readPreviewRenderingResultJson
import com.android.tools.render.common.reportScreenshotError
import com.android.tools.render.common.writePreviewRenderingToJson
import com.android.tools.render.validation.ValidationCategory
import com.android.tools.render.validation.ValidationIssue
import com.android.tools.render.validation.ValidationResult
import com.android.tools.render.validation.ValidationSeverity
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.io.path.absolutePathString
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComposeCliValidationTest {

  @get:Rule val tmpFolder = TemporaryFolder()

  private fun captureReport(methodFQN: String, previewId: String, error: ScreenshotError): String {
    val bytes = ByteArrayOutputStream()
    PrintStream(bytes, true, StandardCharsets.UTF_8).use { stream ->
      reportScreenshotError(methodFQN, previewId, error, stream)
    }
    return bytes.toString(StandardCharsets.UTF_8)
  }

  @Test
  fun testReportScreenshotErrorFormatsValidationErrorsWithoutDuplicateEcho() {
    val error =
      ValidationResult(
          listOf(
            ValidationIssue(
              message = "Dimension 'widthDp' must be positive or -1 (undefined), got: -5",
              severity = ValidationSeverity.ERROR,
              category = ValidationCategory.PARAMETER,
              target = "widthDp",
              value = "-5",
            ),
            ValidationIssue(
              message = "Parameter 'locale' must be a locale resource qualifier, got: 'en-US'",
              severity = ValidationSeverity.ERROR,
              category = ValidationCategory.PARAMETER,
              target = "locale",
              value = "en-US",
            ),
          )
        )
        .toScreenshotError()

    val output = captureReport("com.example.AppKt.MyPreview", "my_preview", error)

    assertThat(output.trimEnd())
      .isEqualTo(
        """
        Validation error in com.example.AppKt.MyPreview (my_preview):
          [PARAMETER] widthDp='-5': Dimension 'widthDp' must be positive or -1 (undefined), got: -5
          [PARAMETER] locale='en-US': Parameter 'locale' must be a locale resource qualifier, got: 'en-US'
        """
          .trimIndent()
      )
    assertThat(output).doesNotContain("Error rendering")
    assertThat(output).doesNotContain("Problem:")
  }

  @Test
  fun testReportScreenshotErrorOmitsDuplicatePreviewIdAndFallsBackToMessage() {
    val errorWithEmptyProblems =
      ScreenshotError(
        status = ValidationResult.VALIDATION_ERROR_STATUS,
        message = "No @Preview annotations found on method 'com.example.AppKt.MyPreview'",
        stackTrace = "",
        problems = emptyList(),
        brokenClasses = emptyList(),
        missingClasses = emptyList(),
      )

    val output = captureReport("com.example.AppKt.MyPreview", "com.example.AppKt.MyPreview", errorWithEmptyProblems)

    assertThat(output.trimEnd())
      .isEqualTo(
        """
        Validation error in com.example.AppKt.MyPreview:
          No @Preview annotations found on method 'com.example.AppKt.MyPreview'
        """
          .trimIndent()
      )
  }

  @Test
  fun testReportScreenshotErrorNamesThePreviewWhenMethodNameOrPreviewIdIsEmpty() {
    val error = ValidationResult.error("No @Preview annotations found", ValidationCategory.METHOD).toScreenshotError()

    // Without a method name, the header has the preview ID alone, not " (my_preview)".
    assertThat(captureReport(methodFQN = "", previewId = "my_preview", error = error).trimEnd())
      .isEqualTo(
        """
        Validation error in my_preview:
          [METHOD] No @Preview annotations found
        """
          .trimIndent()
      )

    assertThat(captureReport(methodFQN = "com.example.AppKt.MyPreview", previewId = "", error = error).trimEnd())
      .isEqualTo(
        """
        Validation error in com.example.AppKt.MyPreview:
          [METHOD] No @Preview annotations found
        """
          .trimIndent()
      )
  }

  @Test
  fun testReportScreenshotErrorKeepsRenderErrorFormatForNonValidationErrors() {
    val renderError =
      ScreenshotError(
        status = "ERROR_RENDER_TASK",
        message = "Render failed",
        stackTrace = "java.lang.IllegalStateException: boom",
        problems = listOf(RenderProblem("Inflation failed", null)),
        brokenClasses = emptyList(),
        missingClasses = emptyList(),
      )

    val output = captureReport("com.example.AppKt.MyPreview", "my_preview", renderError)

    assertThat(output.trimEnd())
      .isEqualTo(
        """
        Error rendering my_preview: Render failed
        java.lang.IllegalStateException: boom
          Problem: Inflation failed
        """
          .trimIndent()
      )
  }

  @Test
  fun testCliReportsParameterAndMethodValidationErrorsInStderrAndResultsJson() {
    val outputFolder = tmpFolder.newFolder("output")
    val metaDataFolder = tmpFolder.newFolder("metadata")
    val resultsFile = tmpFolder.newFile("results.json")
    val settingsFile = tmpFolder.newFile("settings.json")
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val resourceApkPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib/data/framework_res.jar")

    val rendering =
      PreviewRendering(
        fontsPath = null,
        layoutlibPath = layoutlibPath.absolutePathString(),
        outputFolder = outputFolder.absolutePath,
        metaDataFolder = metaDataFolder.absolutePath,
        classPath = emptyList(),
        projectClassPath = emptyList(),
        namespace = "com.example.app",
        resourceApkPath = resourceApkPath.absolutePathString(),
        screenshots =
          listOf(
            ComposeScreenshot(
              methodFQN = "com.example.AppKt.InvalidParamsPreview",
              methodParams = emptyList(),
              previewParams = mapOf("widthDp" to "-5", "locale" to "en-US"),
              previewId = "invalid_params_preview",
            ),
            ComposeScreenshot(
              methodFQN = "com.example.MissingKt.MissingPreview",
              methodParams = emptyList(),
              previewParams = emptyMap(),
              previewId = "missing_preview",
            ),
          ),
        resultsFilePath = resultsFile.absolutePath,
      )
    settingsFile.bufferedWriter().use { writePreviewRenderingToJson(it, rendering) }

    val cliResult = executeComposeCliRender(listOf(settingsFile.absolutePath))

    assertThat(cliResult.exitCode).isEqualTo(1)
    assertThat(cliResult.stdout).isEmpty()

    // Structured stderr without duplicate echoes or runtime "Error rendering" headers.
    assertThat(cliResult.stderr).contains("Validation error in com.example.AppKt.InvalidParamsPreview (invalid_params_preview):")
    assertThat(cliResult.stderr).contains("  [PARAMETER] widthDp='-5': Dimension 'widthDp' must be positive or -1 (undefined), got: -5")
    assertThat(cliResult.stderr).contains("  [PARAMETER] locale='en-US': Parameter 'locale' must be a locale resource qualifier")
    assertThat(cliResult.stderr).contains("Validation error in com.example.MissingKt.MissingPreview (missing_preview):")
    assertThat(cliResult.stderr)
      .contains("  [METHOD] com.example.MissingKt.MissingPreview: Class 'com.example.MissingKt' could not be found on the classpath")
    assertThat(cliResult.stderr).contains("Error: Rendering failed with errors")
    assertThat(cliResult.stderr).doesNotContain("Error rendering")
    assertThat(cliResult.stderr).doesNotContain("Problem:")

    // Results JSON contract stays intact with concise summary message + structured problems.
    val parsed = readPreviewRenderingResultJson(resultsFile.bufferedReader())
    assertThat(parsed.globalError).isNull()
    val byId = parsed.screenshotResults.associateBy { it.previewId }
    assertThat(byId.keys).containsExactly("invalid_params_preview", "missing_preview")

    val invalidParamsError = byId.getValue("invalid_params_preview").error!!
    assertThat(invalidParamsError.status).isEqualTo(ValidationResult.VALIDATION_ERROR_STATUS)
    assertThat(invalidParamsError.message)
      .isEqualTo("2 validation errors: Dimension 'widthDp' must be positive or -1 (undefined), got: -5 (+1 more)")
    assertThat(invalidParamsError.problems).hasSize(2)
    assertThat(invalidParamsError.problems[0].html)
      .isEqualTo("[PARAMETER] widthDp='-5': Dimension 'widthDp' must be positive or -1 (undefined), got: -5")
    assertThat(invalidParamsError.problems[1].html).startsWith("[PARAMETER] locale='en-US': ")

    val missingClassError = byId.getValue("missing_preview").error!!
    assertThat(missingClassError.status).isEqualTo(ValidationResult.VALIDATION_ERROR_STATUS)
    assertThat(missingClassError.message).isEqualTo("Class 'com.example.MissingKt' could not be found on the classpath")
    assertThat(missingClassError.problems).hasSize(1)
    assertThat(missingClassError.problems.single().html)
      .isEqualTo("[METHOD] com.example.MissingKt.MissingPreview: Class 'com.example.MissingKt' could not be found on the classpath")
  }
}
