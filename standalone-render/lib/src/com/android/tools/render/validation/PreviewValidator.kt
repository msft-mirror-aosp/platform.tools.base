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

import com.android.tools.preview.MAX_DIMENSION_DP
import com.android.tools.preview.MAX_FONT_SCALE
import com.android.tools.preview.UNDEFINED_API_LEVEL
import com.android.tools.preview.UNDEFINED_DIMENSION
import com.android.tools.render.common.PreviewScreenshot

/** Validation rules and limits for individual preview annotation parameters. */
object ParameterValidators {

  const val MIN_FONT_SCALE = 0.0f

  /** Validates dimension parameters (`widthDp`, `heightDp`). */
  fun validateDimension(paramName: String, value: String?): List<ValidationIssue> {
    if (value.isNullOrEmpty()) return emptyList()
    val intValue =
      value.toIntOrNull()
        ?: return listOf(
          ValidationIssue(
            message = "Parameter '$paramName' must be an integer, got: '$value'",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = paramName,
            value = value,
          )
        )

    return when {
      intValue == UNDEFINED_DIMENSION -> emptyList()
      intValue <= 0 ->
        listOf(
          ValidationIssue(
            message = "Dimension '$paramName' must be positive or -1 (undefined), got: $intValue",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = paramName,
            value = value,
          )
        )
      intValue > MAX_DIMENSION_DP ->
        listOf(
          ValidationIssue(
            message = "Dimension '$paramName' ($intValue dp) exceeds maximum allowed (${MAX_DIMENSION_DP}dp)",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = paramName,
            value = value,
          )
        )
      else -> emptyList()
    }
  }

  /** Validates fontScale parameter. */
  fun validateFontScale(value: String?): List<ValidationIssue> {
    if (value.isNullOrEmpty()) return emptyList()
    val floatValue =
      value.toFloatOrNull()
        ?: return listOf(
          ValidationIssue(
            message = "Parameter 'fontScale' must be a valid float number, got: '$value'",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = "fontScale",
            value = value,
          )
        )

    return when {
      floatValue <= MIN_FONT_SCALE ->
        listOf(
          ValidationIssue(
            message = "Parameter 'fontScale' must be strictly positive (> $MIN_FONT_SCALE), got: $floatValue",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = "fontScale",
            value = value,
          )
        )
      floatValue > MAX_FONT_SCALE ->
        listOf(
          ValidationIssue(
            message = "Parameter 'fontScale' ($floatValue) exceeds maximum ($MAX_FONT_SCALE)",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = "fontScale",
            value = value,
          )
        )
      else -> emptyList()
    }
  }

  /** Validates apiLevel parameter. */
  fun validateApiLevel(value: String?): List<ValidationIssue> {
    if (value.isNullOrEmpty()) return emptyList()
    val apiLevel =
      value.toIntOrNull()
        ?: return listOf(
          ValidationIssue(
            message = "Parameter 'apiLevel' must be an integer, got: '$value'",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = "apiLevel",
            value = value,
          )
        )

    return when {
      apiLevel == UNDEFINED_API_LEVEL -> emptyList()
      apiLevel <= 0 ->
        listOf(
          ValidationIssue(
            message = "API level must be positive or -1 (undefined), got: $apiLevel",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = "apiLevel",
            value = value,
          )
        )
      else -> emptyList()
    }
  }
}

/** Main orchestrator for validating Compose preview. */
class PreviewValidator : Validator<PreviewScreenshot> {

  /** Validates a [PreviewScreenshot]. */
  override fun validate(target: PreviewScreenshot): ValidationResult {
    return validateParams(target.previewParams)
  }

  /** Validates raw preview parameters map. */
  fun validateParams(previewParams: Map<String, String>): ValidationResult {
    val issues = mutableListOf<ValidationIssue>()

    issues.addAll(ParameterValidators.validateDimension("widthDp", previewParams["widthDp"]))
    issues.addAll(ParameterValidators.validateDimension("heightDp", previewParams["heightDp"]))
    issues.addAll(ParameterValidators.validateFontScale(previewParams["fontScale"]))
    issues.addAll(ParameterValidators.validateApiLevel(previewParams["apiLevel"]))

    return ValidationResult(issues)
  }
}
