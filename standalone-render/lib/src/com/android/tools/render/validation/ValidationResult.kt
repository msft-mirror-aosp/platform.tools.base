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

/** Severity levels for preview validation issues. */
enum class ValidationSeverity {
  /** Unrecoverable error that prevents preview discovery or rendering. */
  ERROR,
  /** Sub-optimal configuration, potential issue, or clamped parameter value. */
  WARNING,
  /** Informational suggestion or best-practice hint. */
  INFO,
}

/** Domain category of a validation check in the Compose preview system. */
enum class ValidationCategory {
  /** Preview annotation parameters (e.g., widthDp, heightDp, fontScale, device, etc.). */
  PARAMETER,
  /** Method structure (e.g., @Composable, non-private, zero-params / @PreviewParameter, return type). */
  METHOD,
  /** MultiPreview meta-annotation graphs, hierarchy depth, and cycle detection. */
  MULTI_PREVIEW,
  /** PreviewParameterProvider class instantiation and iteration. */
  PARAMETER_PROVIDER,
}

/**
 * Represents an individual issue identified during validation.
 *
 * @property message Human-readable explanation of the issue.
 * @property severity The severity level (ERROR, WARNING, INFO).
 * @property category The category of the check (PARAMETER, METHOD, MULTI_PREVIEW, PARAMETER_PROVIDER).
 * @property target The target entity being validated (e.g., parameter name "widthDp", method name "MyScreen", or class name).
 * @property value The actual value that triggered the issue, if applicable.
 */
data class ValidationIssue(
  val message: String,
  val severity: ValidationSeverity,
  val category: ValidationCategory = ValidationCategory.PARAMETER,
  val target: String? = null,
  val value: String? = null,
)

/** Aggregated result of a validation pass. */
data class ValidationResult(val issues: List<ValidationIssue> = emptyList()) {

  /** Returns true if there are no [ValidationSeverity.ERROR] issues. */
  val isValid: Boolean
    get() = issues.none { it.severity == ValidationSeverity.ERROR }

  val hasErrors: Boolean
    get() = issues.any { it.severity == ValidationSeverity.ERROR }

  val hasWarnings: Boolean
    get() = issues.any { it.severity == ValidationSeverity.WARNING }

  val errors: List<ValidationIssue>
    get() = issues.filter { it.severity == ValidationSeverity.ERROR }

  val warnings: List<ValidationIssue>
    get() = issues.filter { it.severity == ValidationSeverity.WARNING }

  val infos: List<ValidationIssue>
    get() = issues.filter { it.severity == ValidationSeverity.INFO }

  /** Returns all issues belonging to a specific [category]. */
  fun forCategory(category: ValidationCategory): List<ValidationIssue> = issues.filter { it.category == category }

  /** Combines this validation result with [other]. */
  operator fun plus(other: ValidationResult): ValidationResult = ValidationResult(this.issues + other.issues)

  companion object {
    val OK = ValidationResult(emptyList())

    fun error(message: String, category: ValidationCategory = ValidationCategory.PARAMETER, target: String? = null, value: String? = null) =
      ValidationResult(listOf(ValidationIssue(message, ValidationSeverity.ERROR, category, target, value)))

    fun warning(
      message: String,
      category: ValidationCategory = ValidationCategory.PARAMETER,
      target: String? = null,
      value: String? = null,
    ) = ValidationResult(listOf(ValidationIssue(message, ValidationSeverity.WARNING, category, target, value)))
  }
}

/** Generic validator contract for components in the standalone render engine. */
fun interface Validator<in T> {
  fun validate(target: T): ValidationResult
}
