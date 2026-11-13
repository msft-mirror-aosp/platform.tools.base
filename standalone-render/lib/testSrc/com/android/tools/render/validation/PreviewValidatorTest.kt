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
    for (invalidFormat in listOf("not_a_float", "NaN", "Infinity", "-Infinity")) {
      val result = validator.validateParams(mapOf("fontScale" to invalidFormat))
      assertFalse("fontScale '$invalidFormat' should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals(ValidationSeverity.ERROR, error.severity)
      assertEquals(ValidationCategory.PARAMETER, error.category)
      assertEquals("fontScale", error.target)
      assertEquals(invalidFormat, error.value)
      assertTrue(error.message.contains("must be a valid float number"))
    }

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
  fun testApiLevelMustBeWithinSupportedRange() {
    val min = ParameterValidators.MIN_API_LEVEL
    val max = ParameterValidators.MAX_API_LEVEL

    for (apiLevel in listOf(min, max, (min + max) / 2)) {
      val result = validator.validateParams(mapOf("apiLevel" to apiLevel.toString()))
      assertTrue("API level $apiLevel should be valid but got: ${result.issues}", result.issues.isEmpty())
    }

    for (apiLevel in listOf(min - 1, max + 1, 1)) {
      val result = validator.validateParams(mapOf("apiLevel" to apiLevel.toString()))
      assertFalse("API level $apiLevel should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals(ValidationSeverity.ERROR, error.severity)
      assertEquals(ValidationCategory.PARAMETER, error.category)
      assertEquals("apiLevel", error.target)
      assertEquals(apiLevel.toString(), error.value)
      assertTrue(error.message.contains("between $min and $max"))
    }
  }

  @Test
  fun testValidLocalesPassValidation() {
    // Resource qualifier forms accepted by Locale.create: language, language + region, uppercase, M.49 numeric region and BCP-47.
    for (locale in listOf("en", "EN", "fil", "en-rUS", "pt-rBR", "es-r419", "b+en+US", "b+sr+Latn+RS")) {
      val result = validator.validateParams(mapOf("locale" to locale))
      assertTrue("Locale '$locale' should be valid but got: ${result.issues}", result.isValid)
      assertTrue(result.issues.isEmpty())
    }
  }

  @Test
  fun testEmptyLocalePassesValidation() {
    assertTrue(validator.validateParams(mapOf("locale" to "")).issues.isEmpty())
    assertTrue(validator.validateParams(emptyMap()).issues.isEmpty())
  }

  @Test
  fun testInvalidLocalesProduceError() {
    // "en-US" is the most common mistake: the region must use the resource qualifier 'r' prefix (en-rUS).
    for (locale in listOf("en-US", "en_US", "english", "e", "en-rUSA", "en-r", "b+", " ", "en-rUS-x")) {
      val result = validator.validateParams(mapOf("locale" to locale))
      assertFalse("Locale '$locale' should be invalid", result.isValid)
      assertEquals(1, result.errors.size)
      val error = result.errors.single()
      assertEquals(ValidationSeverity.ERROR, error.severity)
      assertEquals(ValidationCategory.PARAMETER, error.category)
      assertEquals("locale", error.target)
      assertEquals(locale, error.value)
      assertTrue(error.message.contains("'en-rUS'"))
    }
  }

  @Test
  fun testValidBackgroundColorsPassValidation() {
    // Bytecode discovery reports the Long constant in decimal: 0xFF00FF00 -> 4278255360, 0xFFFFFFFF -> 4294967295.
    for (color in listOf("0", "4278255360", "4294967295", "255")) {
      val result = validator.validateParams(mapOf("backgroundColor" to color, "showBackground" to "true"))
      assertTrue("Color '$color' should be valid but got: ${result.issues}", result.issues.isEmpty())
    }
    assertTrue(validator.validateParams(mapOf("backgroundColor" to "", "showBackground" to "false")).issues.isEmpty())
  }

  @Test
  fun testBackgroundColorOutOfArgbRangeProducesError() {
    for (color in listOf("-1", "-4278255360", "4294967296", "281474976710655")) {
      val result = validator.validateParams(mapOf("backgroundColor" to color, "showBackground" to "true"))
      assertFalse("Color '$color' should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals(ValidationSeverity.ERROR, error.severity)
      assertEquals(ValidationCategory.PARAMETER, error.category)
      assertEquals("backgroundColor", error.target)
      assertEquals(color, error.value)
      assertTrue(error.message.contains("0xFFFFFFFF"))
    }
  }

  @Test
  fun testNonNumericBackgroundColorProducesError() {
    // Hexadecimal strings only reach the validator through the settings JSON; the renderer cannot parse them either.
    for (color in listOf("0xFF00FF00", "#FF00FF00", "red", "1.5")) {
      val result = validator.validateParams(mapOf("backgroundColor" to color, "showBackground" to "true"))
      assertFalse("Color '$color' should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals("backgroundColor", error.target)
      assertEquals(color, error.value)
      assertTrue(error.message.contains("Long"))
    }
  }

  @Test
  fun testBackgroundColorWithoutShowBackgroundProducesWarning() {
    for (showBackground in listOf(null, "false", "FALSE", "yes")) {
      val params = mutableMapOf("backgroundColor" to "4278255360")
      showBackground?.let { params["showBackground"] = it }

      val result = validator.validateParams(params)
      assertTrue("Ignored colors must not fail validation (showBackground=$showBackground)", result.isValid)
      assertTrue(result.hasWarnings)
      val warning = result.warnings.single()
      assertEquals(ValidationSeverity.WARNING, warning.severity)
      assertEquals("backgroundColor", warning.target)
      assertTrue(warning.message.contains("showBackground"))
    }

    // Case-insensitive like String.toBoolean(), which the renderer uses to read showBackground.
    assertTrue(validator.validateParams(mapOf("backgroundColor" to "4278255360", "showBackground" to "TRUE")).issues.isEmpty())
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
