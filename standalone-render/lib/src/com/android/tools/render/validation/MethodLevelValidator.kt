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
  fun validate(
    methodFQN: String,
    isComposable: Boolean,
    previewParamsList: List<Map<String, String>> = emptyList(),
    discoveredWrappers: List<String> = emptyList(),
  ): ValidationResult {
    val issues = mutableListOf<ValidationIssue>()

    issues.addAll(validateComposablePresence(methodFQN, isComposable, previewParamsList))
    issues.addAll(validatePreviewWrapper(methodFQN, isComposable, previewParamsList, discoveredWrappers))

    return ValidationResult(issues)
  }

  /**
   * Generates a [ValidationResult] with an error when a requested class, method, or `@Preview` annotation is missing.
   *
   * @param methodFQN Fully-qualified name of the target method.
   * @param className The class name part of the target method.
   * @param methodName The method name part of the target method.
   * @param classFound Whether the bytecode for [className] was found on the classpath.
   * @param methodFound Whether any method matching [methodName] was found in the class bytecode.
   */
  fun validateMissingMethodOrPreview(
    methodFQN: String,
    className: String,
    methodName: String,
    classFound: Boolean,
    methodFound: Boolean,
  ): ValidationResult {
    val message =
      when {
        !classFound -> "Class '$className' could not be found on the classpath"
        !methodFound -> "Method '$methodName' not found in class '$className'"
        else -> "No @Preview annotations found on method '$methodFQN'"
      }
    return ValidationResult(
      listOf(
        ValidationIssue(
          message = message,
          severity = ValidationSeverity.ERROR,
          category = ValidationCategory.METHOD,
          target = methodFQN,
        )
      )
    )
  }

  private fun validateComposablePresence(
    methodFQN: String,
    isComposable: Boolean,
    previewParamsList: List<Map<String, String>>,
  ): List<ValidationIssue> {
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
    return issues
  }

  private fun validatePreviewWrapper(
    methodFQN: String,
    isComposable: Boolean,
    previewParamsList: List<Map<String, String>>,
    discoveredWrappers: List<String>,
  ): List<ValidationIssue> {
    if (discoveredWrappers.isEmpty()) return emptyList()

    val issues = mutableListOf<ValidationIssue>()
    val hasPreviewAnnotations = previewParamsList.isNotEmpty()

    if (!hasPreviewAnnotations) {
      val message =
        if (!isComposable) {
          "Method '$methodFQN' annotated with @PreviewWrapper must be annotated with @Preview and @Composable"
        } else {
          "Method '$methodFQN' annotated with @PreviewWrapper must also be annotated with @Preview"
        }
      issues.add(
        ValidationIssue(
          message = message,
          severity = ValidationSeverity.ERROR,
          category = ValidationCategory.METHOD,
          target = methodFQN,
        )
      )
    }

    if (discoveredWrappers.size > 1) {
      issues.add(
        ValidationIssue(
          message = "Multiple @PreviewWrapper annotations found for method '$methodFQN': ${discoveredWrappers.joinToString(", ")}",
          severity = ValidationSeverity.ERROR,
          category = ValidationCategory.METHOD,
          target = methodFQN,
        )
      )
    }
    return issues
  }
}
