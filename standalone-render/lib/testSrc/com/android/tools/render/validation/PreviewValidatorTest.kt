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
import com.android.tools.render.drawable.DrawableScreenshot
import com.android.tools.render.layout.LayoutScreenshot
import com.android.tools.render.wear.WearTileScreenshot
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
  fun testResourceScreenshotUsesStringBasedBackgroundColorAndTrimsParameters() {
    val drawable =
      DrawableScreenshot(
        methodFQN = "R.drawable.ic_logo",
        previewParams =
          mapOf("widthDp" to " 300 ", "heightDp" to "   ", "locale" to " fr-rCA ", "uiMode" to " 32 ", "backgroundColor" to " @color/bg "),
        previewId = "ic_logo",
      )
    val drawableResult = validator.validate(drawable)
    assertTrue(drawableResult.isValid)
    assertTrue(drawableResult.issues.isEmpty())

    val layout =
      LayoutScreenshot(
        methodFQN = "R.layout.activity_main",
        previewParams = mapOf("widthDp" to " 300 ", "locale" to " fr-rCA ", "backgroundColor" to " ?attr/colorBackground "),
        previewId = "activity_main",
      )
    assertTrue(validator.validate(layout).issues.isEmpty())

    val invalidDrawable =
      DrawableScreenshot(
        methodFQN = "R.drawable.ic_logo",
        previewParams = mapOf("backgroundColor" to "@*android:color/white"),
        previewId = "ic_logo",
      )
    assertFalse(validator.validate(invalidDrawable).isValid)

    val composeWithHexColor =
      ComposeScreenshot(
        methodFQN = "com.example.MyPreviewKt.Preview",
        previewId = "prev_1",
        previewParams = mapOf("backgroundColor" to "#FF00FF00", "showBackground" to "true"),
        methodParams = emptyList(),
      )
    assertFalse(validator.validate(composeWithHexColor).isValid)

    val composeWithPaddedWidth =
      ComposeScreenshot(
        methodFQN = "com.example.MyPreviewKt.Preview",
        previewId = "prev_1",
        previewParams = mapOf("widthDp" to " 300 "),
        methodParams = emptyList(),
      )
    assertFalse(validator.validate(composeWithPaddedWidth).isValid)

    val wearTileWithPaddedLocale =
      WearTileScreenshot(
        methodFQN = "com.example.MyTilePreviewKt.TilePreview",
        previewId = "tile_1",
        previewParams = mapOf("locale" to " fr "),
      )
    assertFalse(validator.validate(wearTileWithPaddedLocale).isValid)
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
  fun testValidUiModesPassValidation() {
    // 0 (unset), UI_MODE_TYPE_* 1..7, UI_MODE_NIGHT_NO (0x10) | 0..7, and UI_MODE_NIGHT_YES (0x20) | 0..7.
    val validUiModes = (0..7) + (0x10..0x17) + (0x20..0x27)
    for (uiMode in validUiModes) {
      val result = validator.validateParams(mapOf("uiMode" to uiMode.toString()))
      assertTrue("uiMode $uiMode should be valid but got: ${result.issues}", result.issues.isEmpty())
    }
    assertTrue(validator.validateParams(mapOf("uiMode" to "")).issues.isEmpty())
  }

  @Test
  fun testInvalidUiModeFormatsAndBitmasksProduceError() {
    for (nonInt in listOf("night", "0x20", " 32 ", "1.5")) {
      val result = validator.validateParams(mapOf("uiMode" to nonInt))
      assertFalse("uiMode '$nonInt' should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals(ValidationSeverity.ERROR, error.severity)
      assertEquals(ValidationCategory.PARAMETER, error.category)
      assertEquals("uiMode", error.target)
      assertEquals(nonInt, error.value)
      assertTrue(error.message.contains("must be an integer"))
    }

    // Negative values, unknown UI_MODE_TYPE_* (8..15), both night flags set (0x30/0x31), and unknown upper bits (0x40, 0xFFFF).
    for (invalidMask in listOf(-1, -32, 8, 15, 0x18, 0x28, 0x30, 0x31, 0x40, 0xFFFF)) {
      val result = validator.validateParams(mapOf("uiMode" to invalidMask.toString()))
      assertFalse("uiMode $invalidMask should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals(ValidationSeverity.ERROR, error.severity)
      assertEquals(ValidationCategory.PARAMETER, error.category)
      assertEquals("uiMode", error.target)
      assertEquals(invalidMask.toString(), error.value)
      assertTrue(error.message.contains("Configuration.UI_MODE_*"))
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
  fun testValidWallpapersPassValidation() {
    for (wallpaper in ParameterValidators.NO_WALLPAPER..ParameterValidators.MAX_WALLPAPER) {
      val result = validator.validateParams(mapOf("wallpaper" to wallpaper.toString()))
      assertTrue("wallpaper $wallpaper should be valid but got: ${result.issues}", result.issues.isEmpty())
    }
    assertTrue(validator.validateParams(mapOf("wallpaper" to "")).issues.isEmpty())
  }

  @Test
  fun testInvalidWallpapersProduceError() {
    for (nonInt in listOf("red", "NONE", " 0 ", "1.5")) {
      val result = validator.validateParams(mapOf("wallpaper" to nonInt))
      assertFalse("wallpaper '$nonInt' should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals(ValidationSeverity.ERROR, error.severity)
      assertEquals(ValidationCategory.PARAMETER, error.category)
      assertEquals("wallpaper", error.target)
      assertEquals(nonInt, error.value)
      assertTrue(error.message.contains("must be an integer"))
    }

    for (outOfRange in listOf(-2, -10, ParameterValidators.MAX_WALLPAPER + 1, 99)) {
      val result = validator.validateParams(mapOf("wallpaper" to outOfRange.toString()))
      assertFalse("wallpaper $outOfRange should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals(ValidationSeverity.ERROR, error.severity)
      assertEquals(ValidationCategory.PARAMETER, error.category)
      assertEquals("wallpaper", error.target)
      assertEquals(outOfRange.toString(), error.value)
      assertTrue(error.message.contains("between 0 and ${ParameterValidators.MAX_WALLPAPER}"))
    }
  }

  @Test
  fun testValidStringBasedBackgroundColorsPassValidationWithoutShowBackgroundWarning() {
    for (color in
      listOf(
        "",
        "#F00",
        "#8F00",
        "#00ff00",
        "#800000FF",
        "@color/accent",
        "@android:color/white",
        "@com.example.app:color/surface.tint",
        "?attr/colorPrimary",
        "?android:attr/colorBackground",
        "?colorPrimary",
        "?android:colorBackground",
      )) {
      val result = validator.validateParams(mapOf("backgroundColor" to color), usesStringBasedBackgroundColor = true)
      assertTrue("String-based color '$color' should be valid but got: ${result.issues}", result.issues.isEmpty())
    }
  }

  @Test
  fun testInvalidStringBasedBackgroundColorsProduceError() {
    for (invalid in
      listOf(
        " ",
        " #F00 ",
        "4278255360",
        "red",
        "0xFF0000FF",
        "#",
        "#12",
        "#12345",
        "#1234567",
        "#123456789",
        "#-12",
        "#+12",
        "#-1234567",
        "#GGG",
        "#FF0000FF ; x",
        "@",
        "?",
        "@null",
        "@color/",
        "?attr/",
        "@:color/white",
        "?:attr/colorAccent",
        "?:colorAccent",
        "?android:",
        "@+color/accent",
        "@*android:color/white",
        "?*android:attr/colorAccent",
        "?*colorAccent",
        "@drawable/bg",
        "@style/Theme.App",
        "?color/accent",
        "?style/Theme.App",
        "color/accent",
        "android:color/white",
        "attr/colorPrimary",
        "@color/foo:bar",
        "?attr/foo:bar",
        "@123:color/white",
        "@..:color/white",
        "@com..example:color/white",
        "@com.example.:color/white",
        "@color/123",
        "@color/.accent",
        "@color/foo bar",
        "@color/a/b",
      )) {
      val result = validator.validateParams(mapOf("backgroundColor" to invalid), usesStringBasedBackgroundColor = true)
      assertFalse("String-based color '$invalid' should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals(ValidationSeverity.ERROR, error.severity)
      assertEquals(ValidationCategory.PARAMETER, error.category)
      assertEquals("backgroundColor", error.target)
      assertEquals(invalid, error.value)
      assertTrue(error.message.contains("@[<package>:]color/<name>"))
    }
  }

  @Test
  fun testValidDrawableLevelsPassValidation() {
    for (level in listOf("", "0", "1", "5000", "10000")) {
      val result = validator.validateParams(mapOf("level" to level))
      assertTrue("level '$level' should be valid but got: ${result.issues}", result.issues.isEmpty())
    }
    assertTrue(
      validator
        .validate(DrawableScreenshot(methodFQN = "R.drawable.ic_logo", previewParams = mapOf("level" to " 5000 "), previewId = "id"))
        .isValid
    )
  }

  @Test
  fun testInvalidDrawableLevelsProduceError() {
    for (nonInt in listOf("abc", "1.5", " 5000 ", "0x10", "99999999999999999999")) {
      val result = validator.validateParams(mapOf("level" to nonInt))
      assertFalse("level '$nonInt' should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals("level", error.target)
      assertEquals(nonInt, error.value)
      assertTrue(error.message.contains("must be an integer"))
    }

    for (outOfRange in listOf("-1", "-100", "10001")) {
      val result = validator.validateParams(mapOf("level" to outOfRange))
      assertFalse("level '$outOfRange' should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals("level", error.target)
      assertEquals(outOfRange, error.value)
      assertTrue(error.message.contains("between ${ParameterValidators.MIN_DRAWABLE_LEVEL} and ${ParameterValidators.MAX_DRAWABLE_LEVEL}"))
    }
  }

  @Test
  fun testValidDrawableStatesPassValidation() {
    for (state in
      listOf(
        "",
        "   ",
        " , ",
        "pressed",
        "disabled",
        "pressed,checked",
        " pressed , \n checked , ",
        ParameterValidators.DRAWABLE_STATES.joinToString(","),
      )) {
      val result = validator.validateParams(mapOf("state" to state))
      assertTrue("state '$state' should be valid but got: ${result.issues}", result.issues.isEmpty())
    }
  }

  @Test
  fun testInvalidDrawableStatesProduceError() {
    for (invalid in listOf("clicked", "PRESSED", "pressed,clicked", "clicked,clicked")) {
      val result = validator.validateParams(mapOf("state" to invalid))
      assertFalse("state '$invalid' should be invalid", result.isValid)
      val error = result.errors.single()
      assertEquals("state", error.target)
      assertEquals(invalid, error.value)
      assertTrue(error.message.contains("expected one of:"))
    }

    val multiInvalid = validator.validateParams(mapOf("state" to "foo,pressed,bar"))
    assertEquals(2, multiInvalid.errors.size)
    assertTrue(multiInvalid.errors[0].message.contains("'foo'"))
    assertTrue(multiInvalid.errors[1].message.contains("'bar'"))
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
