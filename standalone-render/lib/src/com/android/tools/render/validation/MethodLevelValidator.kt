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

import com.android.tools.render.discovery.MethodDiscoveryContext

/** Validates method-level constraints and annotations for Jetpack Compose preview functions. */
class MethodLevelValidator {

  /**
   * Validates that the discovered preview method overload complies with Compose preview rules.
   *
   * @param methodFQN Fully-qualified name of the method being validated.
   * @param discoveryContext Discovered preview annotations, parameters, and wrapper metadata for this overload.
   * @return A [ValidationResult] containing any errors or warnings found.
   */
  fun validate(
    methodFQN: String,
    discoveryContext: MethodDiscoveryContext = MethodDiscoveryContext(),
  ): ValidationResult {
    val issues = mutableListOf<ValidationIssue>()

    issues.addAll(validateComposablePresence(methodFQN, discoveryContext.isComposable, discoveryContext.previewConfigurations))
    issues.addAll(
      validatePreviewWrapper(
        methodFQN,
        discoveryContext.isComposable,
        discoveryContext.previewConfigurations,
        discoveryContext.previewWrapperFqns,
      )
    )
    if (discoveryContext.isComposable) {
      issues.addAll(
        validateMethodPreviewParameter(
          methodFQN = methodFQN,
          previewParameterConfigs = discoveryContext.previewParameterConfigs,
        )
      )
    }

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
    previewConfigurations: List<Map<String, String>>,
  ): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    val hasPreviewAnnotations = previewConfigurations.isNotEmpty()

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
    previewConfigurations: List<Map<String, String>>,
    previewWrapperFqns: List<String>,
  ): List<ValidationIssue> {
    if (previewWrapperFqns.isEmpty()) return emptyList()

    val issues = mutableListOf<ValidationIssue>()
    val hasPreviewAnnotations = previewConfigurations.isNotEmpty()

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

    if (previewWrapperFqns.size > 1) {
      issues.add(
        ValidationIssue(
          message = "Multiple @PreviewWrapper annotations found for method '$methodFQN': ${previewWrapperFqns.joinToString(", ")}",
          severity = ValidationSeverity.ERROR,
          category = ValidationCategory.METHOD,
          target = methodFQN,
        )
      )
    }
    return issues
  }

  private fun validateMethodPreviewParameter(
    methodFQN: String,
    previewParameterConfigs: List<Map<String, String>>,
  ): List<ValidationIssue> {
    if (previewParameterConfigs.isEmpty()) return emptyList()

    if (previewParameterConfigs.size > 1) {
      return listOf(
        ValidationIssue(
          message = "Composable preview functions can have at most one @PreviewParameter",
          severity = ValidationSeverity.ERROR,
          category = ValidationCategory.METHOD,
          target = methodFQN,
        )
      )
    }

    return PreviewParameterValidator.validate(previewParameterConfigs.first())
  }
}
