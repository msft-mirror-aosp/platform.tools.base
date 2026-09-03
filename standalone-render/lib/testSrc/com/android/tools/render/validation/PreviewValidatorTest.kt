/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.tools.render.compose.ComposeScreenshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewValidatorTest {

  private val validator = PreviewValidator()

  @Test
  fun testValidPreviewParametersPassValidation() {
    val preview =
      ComposeScreenshot(
        methodFQN = "com.example.MyPreviewKt.Preview",
        previewId = "prev_1",
        previewParams = mapOf("widthDp" to "300", "heightDp" to "600", "fontScale" to "1.5", "apiLevel" to "33"),
        methodParams = emptyList(),
      )

    val result = validator.validate(preview)
    assertTrue("Valid parameters should pass validation", result.isValid)
    assertFalse("Result should not have errors", result.hasErrors)
    assertFalse("Result should not have warnings", result.hasWarnings)
    assertTrue("Issues list should be empty", result.issues.isEmpty())
  }

  @Test
  fun testUndefinedParametersPassValidation() {
    val preview =
      ComposeScreenshot(
        methodFQN = "com.example.MyPreviewKt.Preview",
        previewId = "prev_1",
        previewParams = mapOf("widthDp" to "-1", "heightDp" to "-1", "apiLevel" to "-1"),
        methodParams = emptyList(),
      )

    val result = validator.validate(preview)
    assertTrue(result.isValid)
    assertEquals(0, result.issues.size)
  }

  @Test
  fun testInvalidDimensionFormats() {
    val result = validator.validateParams(mapOf("widthDp" to "invalid_int", "heightDp" to "abc"))

    assertFalse(result.isValid)
    assertEquals(2, result.errors.size)
    assertEquals("widthDp", result.errors[0].target)
    assertEquals(ValidationSeverity.ERROR, result.errors[0].severity)
    assertEquals(ValidationCategory.PARAMETER, result.errors[0].category)
    assertEquals("heightDp", result.errors[1].target)
  }

  @Test
  fun testNonPositiveDimensionsProduceErrors() {
    val result = validator.validateParams(mapOf("widthDp" to "0", "heightDp" to "-5"))

    assertFalse(result.isValid)
    assertEquals(2, result.errors.size)
    assertTrue(result.errors.any { it.target == "widthDp" && it.value == "0" })
    assertTrue(result.errors.any { it.target == "heightDp" && it.value == "-5" })
  }

  @Test
  fun testDimensionExceedingMaxProducesError() {
    val result = validator.validateParams(mapOf("widthDp" to "3500", "heightDp" to "4000"))

    assertFalse("Dimensions exceeding max should produce errors", result.isValid)
    assertTrue(result.hasErrors)
    assertEquals(2, result.errors.size)
    assertEquals(ValidationSeverity.ERROR, result.errors[0].severity)
  }

  @Test
  fun testInvalidFontScaleFormatsAndValues() {
    val nonFloatResult = validator.validateParams(mapOf("fontScale" to "not_a_float"))
    assertFalse(nonFloatResult.isValid)
    assertEquals(1, nonFloatResult.errors.size)

    val zeroResult = validator.validateParams(mapOf("fontScale" to "0.0"))
    assertFalse(zeroResult.isValid)
    assertEquals(1, zeroResult.errors.size)

    val negativeResult = validator.validateParams(mapOf("fontScale" to "-0.5"))
    assertFalse(negativeResult.isValid)
    assertEquals(1, negativeResult.errors.size)

    val excessiveResult = validator.validateParams(mapOf("fontScale" to "12.0"))
    assertFalse("FontScale > 10 produces error", excessiveResult.isValid)
    assertEquals(1, excessiveResult.errors.size)
    assertEquals("fontScale", excessiveResult.errors[0].target)
    assertEquals(ValidationSeverity.ERROR, excessiveResult.errors[0].severity)
  }

  @Test
  fun testInvalidApiLevels() {
    val nonIntResult = validator.validateParams(mapOf("apiLevel" to "Tiramisu"))
    assertFalse(nonIntResult.isValid)
    assertEquals(1, nonIntResult.errors.size)

    val zeroResult = validator.validateParams(mapOf("apiLevel" to "0"))
    assertFalse(zeroResult.isValid)
    assertEquals(1, zeroResult.errors.size)

    val negativeResult = validator.validateParams(mapOf("apiLevel" to "-10"))
    assertFalse(negativeResult.isValid)
    assertEquals(1, negativeResult.errors.size)
  }

  @Test
  fun testValidationResultAggregationAndComposition() {
    val r1 = ValidationResult.error("Error 1", ValidationCategory.PARAMETER, target = "widthDp")
    val r2 = ValidationResult.warning("Warning 1", ValidationCategory.METHOD, target = "myMethod")

    val combined = r1 + r2
    assertFalse(combined.isValid)
    assertTrue(combined.hasErrors)
    assertTrue(combined.hasWarnings)
    assertEquals(1, combined.errors.size)
    assertEquals(1, combined.warnings.size)
    assertEquals(1, combined.forCategory(ValidationCategory.PARAMETER).size)
    assertEquals(1, combined.forCategory(ValidationCategory.METHOD).size)
  }
}
