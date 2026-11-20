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

package com.android.tools.render.validation

import com.android.tools.render.common.PreviewRenderingResult
import com.android.tools.render.common.PreviewScreenshotResult
import com.android.tools.render.common.readPreviewRenderingResultJson
import com.android.tools.render.common.writePreviewRenderingResult
import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationResultTest {

  @Test
  fun testFormatProblemWithTargetAndValue() {
    val issue =
      ValidationIssue(
        message = "Dimension 'widthDp' must be positive or -1 (undefined), got: -5",
        severity = ValidationSeverity.ERROR,
        category = ValidationCategory.PARAMETER,
        target = "widthDp",
        value = "-5",
      )

    assertEquals(
      "[PARAMETER] widthDp='-5': Dimension 'widthDp' must be positive or -1 (undefined), got: -5",
      issue.formatProblem(),
    )
  }

  @Test
  fun testFormatProblemWithTargetOnly() {
    val issue =
      ValidationIssue(
        message = "Method 'com.example.AppKt.MyPreview' annotated with @Preview must be annotated with @Composable",
        severity = ValidationSeverity.ERROR,
        category = ValidationCategory.METHOD,
        target = "com.example.AppKt.MyPreview",
      )

    assertEquals(
      "[METHOD] com.example.AppKt.MyPreview: Method 'com.example.AppKt.MyPreview' annotated with @Preview must be annotated with @Composable",
      issue.formatProblem(),
    )
  }

  @Test
  fun testFormatProblemWithValueOnlyOrNeither() {
    val valueOnly =
      ValidationIssue(
        message = "Invalid value",
        severity = ValidationSeverity.ERROR,
        category = ValidationCategory.PARAMETER_PROVIDER,
        target = null,
        value = "",
      )
    assertEquals("[PARAMETER_PROVIDER] '': Invalid value", valueOnly.formatProblem())

    val neither =
      ValidationIssue(
        message = "Too many previews",
        severity = ValidationSeverity.ERROR,
        category = ValidationCategory.MULTI_PREVIEW,
        target = null,
        value = null,
      )
    assertEquals("[MULTI_PREVIEW] Too many previews", neither.formatProblem())
  }

  @Test
  fun testToScreenshotErrorSingleErrorPreservesConciseMessageAndStructuredProblem() {
    val result =
      ValidationResult.error(
        message = "Parameter 'locale' must be a locale resource qualifier, got: 'en-US'",
        category = ValidationCategory.PARAMETER,
        target = "locale",
        value = "en-US",
      )

    val error = result.toScreenshotError()

    assertEquals(ValidationResult.VALIDATION_ERROR_STATUS, error.status)
    assertEquals("Parameter 'locale' must be a locale resource qualifier, got: 'en-US'", error.message)
    assertEquals("", error.stackTrace)
    assertEquals(1, error.problems.size)
    assertEquals(
      "[PARAMETER] locale='en-US': Parameter 'locale' must be a locale resource qualifier, got: 'en-US'",
      error.problems.single().html,
    )
    assertNull(error.problems.single().stackTrace)
    assertTrue(error.brokenClasses.isEmpty())
    assertTrue(error.missingClasses.isEmpty())
  }

  @Test
  fun testToScreenshotErrorMultipleErrorsCreatesConciseSummaryAndExcludesWarnings() {
    val result =
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
            message = "Parameter 'backgroundColor' has no effect unless 'showBackground' is set to true",
            severity = ValidationSeverity.WARNING,
            category = ValidationCategory.PARAMETER,
            target = "backgroundColor",
            value = "4278255360",
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

    val error = result.toScreenshotError()

    assertEquals(ValidationResult.VALIDATION_ERROR_STATUS, error.status)
    assertEquals(
      "2 validation errors: Dimension 'widthDp' must be positive or -1 (undefined), got: -5 (+1 more)",
      error.message,
    )
    assertEquals(2, error.problems.size)
    assertEquals(
      "[PARAMETER] widthDp='-5': Dimension 'widthDp' must be positive or -1 (undefined), got: -5",
      error.problems[0].html,
    )
    assertEquals(
      "[PARAMETER] locale='en-US': Parameter 'locale' must be a locale resource qualifier, got: 'en-US'",
      error.problems[1].html,
    )
  }

  @Test
  fun testToScreenshotErrorRoundTripsThroughResultsJson() {
    val error =
      ValidationResult(
          listOf(
            ValidationIssue(
              message = "Dimension 'widthDp' must be positive or -1 (undefined), got: -5",
              severity = ValidationSeverity.ERROR,
              category = ValidationCategory.PARAMETER,
              target = "widthDp",
              value = "-5",
            )
          )
        )
        .toScreenshotError()

    val original =
      PreviewRenderingResult(
        globalError = null,
        screenshotResults =
          listOf(
            PreviewScreenshotResult(
              previewId = "my_preview",
              methodFQN = "com.example.AppKt.MyPreview",
              imagePath = "com/example/AppKt/my_preview_0.png",
              error = error,
            )
          ),
      )

    val json = StringWriter().also { writePreviewRenderingResult(it, original) }.toString()
    val parsed = readPreviewRenderingResultJson(StringReader(json))

    assertEquals(original, parsed)
  }
}
