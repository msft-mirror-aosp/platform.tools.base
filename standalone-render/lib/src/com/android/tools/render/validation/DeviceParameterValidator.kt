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

import com.android.tools.preview.config.DEVICE_BY_ID_PREFIX
import com.android.tools.preview.config.DEVICE_BY_NAME_PREFIX
import com.android.tools.preview.config.DEVICE_BY_SPEC_PREFIX

/**
 * Validates the syntax of the `device` parameter of `@Preview`.
 *
 * The renderer (`PreviewConfiguration.applyTo`) silently falls back to the default device whenever it cannot resolve the value, so every
 * form it does not understand is reported as an error here. The accepted forms are:
 * - empty: the default device,
 * - `id:<device id>` or `name:<device name>`: a predefined device,
 * - `spec:<device spec>`: a device described with the DeviceSpec language, see [DeviceSpecValidator].
 *
 * Only the syntax is validated: whether the device referenced by `id:`, `name:` or `parent=` exists requires the device catalog of the
 * render environment and is not checked here.
 */
internal object DeviceParameterValidator {

  private const val PARAMETER_DEVICE = "device"

  private val PREFIXES = listOf(DEVICE_BY_ID_PREFIX, DEVICE_BY_NAME_PREFIX, DEVICE_BY_SPEC_PREFIX)

  /** Validates the `device` parameter [value] and returns the issues found, or an empty list when the value is valid. */
  fun validate(value: String?): List<ValidationIssue> {
    if (value.isNullOrBlank()) return emptyList()
    val problems =
      when {
        value.startsWith(DEVICE_BY_ID_PREFIX) -> validateReference(value, DEVICE_BY_ID_PREFIX, "device id")
        value.startsWith(DEVICE_BY_NAME_PREFIX) -> validateReference(value, DEVICE_BY_NAME_PREFIX, "device name")
        value.startsWith(DEVICE_BY_SPEC_PREFIX) -> DeviceSpecValidator.validate(value)
        else -> listOf(unsupportedFormMessage(value))
      }
    return problems.map { problem ->
      ValidationIssue(
        message = problem,
        severity = ValidationSeverity.ERROR,
        category = ValidationCategory.PARAMETER,
        target = PARAMETER_DEVICE,
        value = value,
      )
    }
  }

  /** Validates an `id:` or `name:` reference, which the renderer looks up verbatim in the device catalog. */
  private fun validateReference(value: String, prefix: String, description: String): List<String> {
    val reference = value.removePrefix(prefix)
    return when {
      reference.isBlank() -> listOf("Parameter '$PARAMETER_DEVICE' is missing the $description after '$prefix', got: '$value'")
      reference != reference.trim() ->
        listOf("Parameter '$PARAMETER_DEVICE' must not have whitespace around the $description after '$prefix', got: '$value'")
      else -> emptyList()
    }
  }

  private fun unsupportedFormMessage(value: String): String =
    "Parameter '$PARAMETER_DEVICE' must be empty, '$DEVICE_BY_ID_PREFIX<device id>', '$DEVICE_BY_NAME_PREFIX<device name>' or " +
      "'$DEVICE_BY_SPEC_PREFIX<device spec>', got: '$value'${unsupportedFormHint(value)}"

  /** Suggests the intended form of a [value] that has no known prefix, e.g. `ID:pixel_5`, `pixel_5` or `Pixel 5`. */
  private fun unsupportedFormHint(value: String): String {
    val lowercase = value.lowercase()
    val prefix = PREFIXES.firstOrNull { lowercase.startsWith(it) }
    return when {
      prefix != null -> " (the '$prefix' prefix is case-sensitive)"
      ':' in value -> ""
      value.any { it.isWhitespace() } -> " (did you mean '$DEVICE_BY_NAME_PREFIX$value'?)"
      else -> " (did you mean '$DEVICE_BY_ID_PREFIX$value'?)"
    }
  }
}
