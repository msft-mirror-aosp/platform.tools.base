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

/** Validates method-level constraints and annotations for Jetpack Compose preview functions. */
class MethodLevelValidator {

  /**
   * Validates that the discovered preview method overload complies with Compose preview rules.
   *
   * @param methodFQN Fully-qualified name of the method being validated.
   * @param isComposable Whether the method is annotated with `@Composable`.
   * @param previewParamsList List of discovered preview parameters for this method overload.
   * @param discoveredWrappers List of discovered `@PreviewWrapper` class names for this method overload.
   * @return A [ValidationResult] containing any errors or warnings found.
   */
  fun validate(methodFQN: String, isComposable: Boolean, previewParamsList: List<Map<String, String>> = emptyList()): ValidationResult {
    val issues = mutableListOf<ValidationIssue>()

    val hasPreviewAnnotations = previewParamsList.isNotEmpty()
    if (hasPreviewAnnotations && !isComposable) {
      issues.add(
        ValidationIssue(
          message = "Method '$methodFQN' annotated with @Preview must be annotated with @Composable",
          severity = ValidationSeverity.ERROR,
          category = ValidationCategory.METHOD,
          target = methodFQN,
        )
      )
    }

    return ValidationResult(issues)
  }
}
