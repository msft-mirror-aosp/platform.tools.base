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

/** Validator for `@PreviewParameter` annotation attributes on composable method parameters. */
object PreviewParameterValidator {

  /** Name of the attribute referencing the `PreviewParameterProvider` class in `@PreviewParameter`. */
  private const val PARAMETER_PROVIDER = "provider"

  /** Name of the attribute specifying the maximum number of items to render from the provider in `@PreviewParameter`. */
  private const val PARAMETER_LIMIT = "limit"

  private val VALID_ATTRIBUTES = setOf(PARAMETER_PROVIDER, PARAMETER_LIMIT)

  /** Validates a `@PreviewParameter` annotation attribute map. */
  fun validate(methodParam: Map<String, String>): List<ValidationIssue> = buildList {
    for ((key, value) in methodParam) {
      if (key !in VALID_ATTRIBUTES) {
        add(
          ValidationIssue(
            message =
              "Unexpected attribute '$key' found on @PreviewParameter annotation. Only '$PARAMETER_PROVIDER' and '$PARAMETER_LIMIT' are supported.",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = key,
            value = value,
          )
        )
      }
    }

    methodParam[PARAMETER_LIMIT]?.let { limit ->
      val limitInt = limit.toIntOrNull()
      if (limitInt == null || limitInt <= 0) {
        add(
          ValidationIssue(
            message = "Parameter '$PARAMETER_LIMIT' on @PreviewParameter must be a positive integer, got: '$limit'",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = PARAMETER_LIMIT,
            value = limit,
          )
        )
      }
    }

    val provider = methodParam[PARAMETER_PROVIDER]
    if (provider == null) {
      add(
        ValidationIssue(
          message = "Required parameter '$PARAMETER_PROVIDER' on @PreviewParameter is missing",
          severity = ValidationSeverity.ERROR,
          category = ValidationCategory.PARAMETER,
          target = PARAMETER_PROVIDER,
          value = null,
        )
      )
    } else if (provider.isBlank()) {
      add(
        ValidationIssue(
          message = "Parameter '$PARAMETER_PROVIDER' on @PreviewParameter must not be blank",
          severity = ValidationSeverity.ERROR,
          category = ValidationCategory.PARAMETER,
          target = PARAMETER_PROVIDER,
          value = provider,
        )
      )
    }
  }
}
