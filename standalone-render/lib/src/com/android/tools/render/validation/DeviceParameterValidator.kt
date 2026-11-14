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

import com.android.sdklib.devices.Device
import com.android.tools.preview.config.DEVICE_BY_ID_PREFIX
import com.android.tools.preview.config.DEVICE_BY_NAME_PREFIX
import com.android.tools.preview.config.DEVICE_BY_SPEC_PREFIX
import com.android.utils.text.editDistance

/**
 * Validates the `device` parameter of `@Preview`.
 *
 * The renderer (`PreviewConfiguration.applyTo`) silently falls back to the default device whenever it cannot resolve the value, so every
 * form it does not understand is reported as an error here. The accepted forms are:
 * - empty: the default device,
 * - `id:<device id>` or `name:<device name>`: a predefined device,
 * - `spec:<device spec>`: a device described with the DeviceSpec language, see [DeviceSpecValidator].
 *
 * The syntax is always validated. When the device catalog of the render environment is given, the devices referenced by `id:`, `name:` or
 * `parent=` must also exist in it, because the renderer looks them up in that catalog.
 */
internal object DeviceParameterValidator {

  private const val PARAMETER_DEVICE = "device"

  private val PREFIXES = listOf(DEVICE_BY_ID_PREFIX, DEVICE_BY_NAME_PREFIX, DEVICE_BY_SPEC_PREFIX)

  /**
   * Validates the `device` parameter [value] and returns the issues found, or an empty list when the value is valid.
   *
   * @param devices the device catalog used by the renderer, or null to only validate the syntax.
   */
  fun validate(value: String?, devices: Collection<Device>? = null): List<ValidationIssue> = validate(value, devices?.let(::DeviceCatalog))

  /** Validates the `device` parameter [value] against the pre-indexed [catalog], or only its syntax when [catalog] is null. */
  fun validate(value: String?, catalog: DeviceCatalog?): List<ValidationIssue> {
    if (value.isNullOrBlank()) return emptyList()
    val problems =
      when {
        value.startsWith(DEVICE_BY_ID_PREFIX) -> validateReference(value, DEVICE_BY_ID_PREFIX, "device id", catalog?.ids)
        value.startsWith(DEVICE_BY_NAME_PREFIX) -> validateReference(value, DEVICE_BY_NAME_PREFIX, "device name", catalog?.names)
        value.startsWith(DEVICE_BY_SPEC_PREFIX) -> DeviceSpecValidator.validate(value, catalog)
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

  /**
   * Validates an `id:` or `name:` reference, which the renderer looks up verbatim in the device catalog.
   *
   * @param knownReferences the ids or names of the devices in the catalog, or null when the catalog is not available.
   */
  private fun validateReference(value: String, prefix: String, description: String, knownReferences: Set<String>?): List<String> {
    val reference = value.removePrefix(prefix)
    return when {
      reference.isBlank() -> listOf("Parameter '$PARAMETER_DEVICE' is missing the $description after '$prefix', got: '$value'")
      reference != reference.trim() ->
        listOf("Parameter '$PARAMETER_DEVICE' must not have whitespace around the $description after '$prefix', got: '$value'")
      knownReferences != null && reference !in knownReferences -> {
        val hint = didYouMeanDeviceHint(reference, knownReferences, prefix)
        listOf(
          "Parameter '$PARAMETER_DEVICE' references an unknown $description, got: '$value'$hint. " +
            "The preview would be rendered on the default device instead. Only the built-in device definitions are available, " +
            "not devices defined in Android Studio's Device Manager"
        )
      }
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

/** The ids and display names of the devices in the render environment's catalog, indexed lazily once per catalog. */
internal class DeviceCatalog(devices: Collection<Device>) {
  val ids: Set<String> by lazy { devices.mapTo(LinkedHashSet()) { it.id } }
  val names: Set<String> by lazy { devices.mapTo(LinkedHashSet()) { it.displayName } }
}

private val DEVICE_REFERENCE_SEPARATORS = Regex("[\\s_()-]+")

/**
 * Formats a ` (did you mean ...?)` suffix suggesting the closest entries in [knownReferences] for [reference], each prefixed with [prefix],
 * or returns an empty string when no entry is close enough.
 */
internal fun didYouMeanDeviceHint(reference: String, knownReferences: Collection<String>, prefix: String = ""): String {
  val suggestions = findSimilarDeviceReferences(reference, knownReferences).map { "'$prefix$it'" }
  return when (suggestions.size) {
    0 -> ""
    1 -> " (did you mean ${suggestions.single()}?)"
    else -> " (did you mean ${suggestions.dropLast(1).joinToString(", ")} or ${suggestions.last()}?)"
  }
}

/**
 * Returns all entries of [knownReferences] that most likely are what the user meant by the unknown device id or name [reference], for
 * example `tv_1080p` for `tv_1080`, `Pixel 5` for `Pixel5`, or `pixel_9_pro_xl` for `pixel_9_xl`.
 *
 * References are compared case-insensitively and split into tokens at separators (spaces, `_`, `-`, `(` and `)`). Matches are searched in
 * order of specificity, and the first step that finds any is used:
 * 1. Entries that are equal once case and separators are ignored, e.g. `Pixel5` -> `Pixel 5` or `Television 1080p` -> `Television (1080p)`.
 * 2. Entries that contain all tokens of [reference] in order, with the fewest extra tokens, e.g. `pixel_9_xl` (`pixel`, `9`, `xl`) ->
 *    `pixel_9_pro_xl` (`pixel`, `9`, `pro`, `xl`), or `tv` -> `tv_1080p`, `tv_4k` and `tv_720p`.
 * 3. Entries at the minimum edit distance of the tokens joined together, if within `max(2, length / 4)`.
 *
 * All equally good matches are returned, sorted alphabetically, so that none is hidden, e.g. `Nexus 4`, `Nexus 5`, `Nexus 6`, `Nexus 7`,
 * `Nexus 9` and `Nexus S` for `Nexus 8`.
 */
internal fun findSimilarDeviceReferences(reference: String, knownReferences: Collection<String>): List<String> {
  val normalizedReference = reference.normalizeDeviceReference()
  if (normalizedReference.isEmpty()) return emptyList()
  val candidates =
    knownReferences.distinct().map { it to it.normalizeDeviceReference() }.filter { (_, normalized) -> normalized.isNotEmpty() }

  val equalIgnoringSeparators = candidates.filter { (_, normalized) -> normalized == normalizedReference }.map { (known, _) -> known }
  if (equalIgnoringSeparators.isNotEmpty()) return equalIgnoringSeparators.sorted()

  val referenceTokens = reference.tokenizeDeviceReference()
  val containingTokens =
    candidates
      .map { (known, _) -> known to known.tokenizeDeviceReference() }
      .filter { (_, tokens) -> isTokenSubsequence(referenceTokens, tokens) }
  if (containingTokens.isNotEmpty()) {
    val minTokenCount = containingTokens.minOf { (_, tokens) -> tokens.size }
    return containingTokens.filter { (_, tokens) -> tokens.size == minTokenCount }.map { (known, _) -> known }.sorted()
  }

  val maxDistance = maxOf(2, normalizedReference.length / 4)
  val withinMaxDistance =
    candidates
      .map { (known, normalized) -> known to editDistance(normalizedReference, normalized, maxDistance) }
      .filter { (_, distance) -> distance <= maxDistance }
  if (withinMaxDistance.isEmpty()) return emptyList()
  val minDistance = withinMaxDistance.minOf { (_, distance) -> distance }
  return withinMaxDistance.filter { (_, distance) -> distance == minDistance }.map { (known, _) -> known }.sorted()
}

private fun String.normalizeDeviceReference(): String = lowercase().replace(DEVICE_REFERENCE_SEPARATORS, "")

private fun String.tokenizeDeviceReference(): List<String> = lowercase().split(DEVICE_REFERENCE_SEPARATORS).filter { it.isNotEmpty() }

/** Whether [tokens] contain all of [subsequence] in the same order, possibly with other tokens in between. */
private fun isTokenSubsequence(subsequence: List<String>, tokens: List<String>): Boolean {
  if (subsequence.isEmpty()) return true
  var subIndex = 0
  for (token in tokens) {
    if (token == subsequence[subIndex]) {
      subIndex++
      if (subIndex == subsequence.size) return true
    }
  }
  return false
}
