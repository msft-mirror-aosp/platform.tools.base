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
import com.android.tools.preview.config.DEVICE_BY_SPEC_PREFIX
import com.android.tools.preview.config.DeviceConfig
import com.android.tools.preview.config.Preview.DeviceSpec

/**
 * Validates `spec:` values of the `device` parameter against the DeviceSpec language grammar implemented by
 * [DeviceConfig.toDeviceConfigOrNull].
 *
 * A device spec is a comma separated list of `name=value` parameters describing either:
 * - a custom device: `spec:width=<dimension>,height=<dimension>[,dpi=<int>,...]`, or
 * - an existing device with overrides: `spec:parent=<device id>[,orientation=<orientation>,...]`.
 *
 * The accepted parameters and values are defined in [DeviceSpecParameters]. Whether the device referenced by `parent` exists is only
 * checked when the device catalog is given, see [DeviceParameterValidator].
 */
internal object DeviceSpecValidator {

  /**
   * Returns the problems found in [spec], a `device` value starting with `spec:`, or an empty list when the renderer can parse it.
   *
   * @param devices the device catalog used by the renderer to resolve `parent`, or null to only validate the syntax.
   */
  fun validate(spec: String, devices: Collection<Device>? = null): List<String> = validate(spec, devices?.let(::DeviceCatalog))

  /** Returns the problems found in [spec], a `device` value starting with `spec:`, against the pre-indexed [catalog]. */
  fun validate(spec: String, catalog: DeviceCatalog?): List<String> = DeviceSpecValidation(spec, catalog).findProblems()
}

private const val SPEC_EXAMPLE = "spec:width=411dp,height=891dp"

/** The validation of a single device [spec]: splits it into parameters, then checks them against the DeviceSpec language. */
private class DeviceSpecValidation(private val spec: String, private val catalog: DeviceCatalog?) {

  private val problems = mutableListOf<String>()

  /** The `name=value` parameters of the spec, in declaration order. */
  private val parameters = LinkedHashMap<String, String>()

  private val isParentBased: Boolean
    get() = DeviceSpec.PARAMETER_PARENT in parameters

  private val supportedParameters: Map<String, DeviceSpecParameter>
    get() = if (isParentBased) DeviceSpecParameters.PARENT else DeviceSpecParameters.CUSTOM

  fun findProblems(): List<String> {
    splitParameters()
    checkParameterValues()
    if (isParentBased) {
      checkParentExists()
      checkDimensionUnits()
    } else {
      checkRequiredParameters()
      checkDimensionUnits()
      checkRendererCanParse()
    }
    return problems
  }

  /** Splits the spec into its `name=value` parameters, reporting the malformed, empty and repeated ones. */
  private fun splitParameters() {
    val repeated = LinkedHashSet<String>()
    for (segment in spec.removePrefix(DEVICE_BY_SPEC_PREFIX).split(DeviceSpec.SEPARATOR)) {
      if (segment.isBlank()) continue
      val name = segment.substringBefore(DeviceSpec.OPERATOR, missingDelimiterValue = "").trim()
      if (name.isEmpty()) {
        report("Device spec parameter '${segment.trim()}' must have the form '<name>${DeviceSpec.OPERATOR}<value>'")
        continue
      }
      val value = segment.substringAfter(DeviceSpec.OPERATOR).trim()
      when {
        parameters.putIfAbsent(name, value) != null -> repeated += name
        value.isEmpty() -> report("Device spec parameter '$name' has no value")
      }
    }
    repeated.forEach { report("Device spec parameter '$it' is specified more than once") }
  }

  /** Checks that every parameter is supported by this kind of spec and that the renderer can parse its value. */
  private fun checkParameterValues() {
    for ((name, value) in parameters) {
      val parameter = supportedParameters[name]
      when {
        parameter == null -> reportUnknownParameter(name)
        value.isEmpty() -> {} // Already reported by splitParameters.
        !parameter.accepts(value) -> report("Device spec parameter '$name' must be ${parameter.expected}, got: '$value'")
      }
    }
  }

  private fun reportUnknownParameter(name: String) {
    val supportedNames = supportedParameters.keys.joinToString(", ") { "'$it'" }
    when {
      name in DeviceSpecParameters.LEGACY ->
        report(
          "Device spec parameter '$name' belongs to the legacy device spec format",
          help = "This format is not supported, use the DeviceSpec language instead, e.g. 'spec:width=360dp,height=640dp,dpi=480'",
        )
      isParentBased && name in DeviceSpecParameters.CUSTOM ->
        report(
          "Device spec parameter '$name' cannot be combined with '${DeviceSpec.PARAMETER_PARENT}'",
          help = "Parameters supported with '${DeviceSpec.PARAMETER_PARENT}': $supportedNames",
        )
      else -> {
        val similarName = supportedParameters.keys.firstOrNull { it.equals(name, ignoreCase = true) }
        val hint = if (similarName != null) " (did you mean '$similarName'?)" else ""
        report("Unknown device spec parameter '$name'$hint", help = "Supported parameters: $supportedNames")
      }
    }
  }

  /** The renderer resolves `parent` by device id and falls back to the default device when the id is not in the catalog. */
  private fun checkParentExists() {
    val ids = catalog?.ids ?: return
    val parentId = parameters[DeviceSpec.PARAMETER_PARENT]?.takeIf { it.isNotEmpty() } ?: return
    if (parentId in ids) return
    val hint = didYouMeanDeviceHint(parentId, ids)
    report(
      "Device spec parameter '${DeviceSpec.PARAMETER_PARENT}' references an unknown device id '$parentId'$hint",
      help =
        "It must be the id of a built-in device definition, as accepted by '$DEVICE_BY_ID_PREFIX' (not a device defined in " +
          "Android Studio's Device Manager), otherwise the preview is rendered on the default device",
    )
  }

  /** A custom device spec must declare `width` and `height`. */
  private fun checkRequiredParameters() {
    for (name in DeviceSpecParameters.REQUIRED_CUSTOM) {
      if (name !in parameters) report("Device spec must declare '$name'", help = "Expected e.g. '$SPEC_EXAMPLE'")
    }
  }

  /**
   * The renderer requires all dimension parameters in a spec (e.g. `width`, `height`, `chinSize`, `maxHeight`, `maxWidth`) to share the
   * same unit.
   */
  private fun checkDimensionUnits() {
    val dimensions =
      parameters
        .filterKeys { it in supportedParameters && DeviceSpec.isDimensionParameter(it) }
        .mapNotNull { (name, value) ->
          val ignoreCase = name == DeviceSpec.PARAMETER_MAX_HEIGHT || name == DeviceSpec.PARAMETER_MAX_WIDTH
          DeviceSpecDimension.parse(value, ignoreCase)?.let { dimension -> name to dimension }
        }
    if (dimensions.distinctBy { (_, dimension) -> dimension.unit }.size <= 1) return
    val names = dimensions.joinToString(", ") { (name, _) -> "'$name'" }
    val values = dimensions.joinToString(", ") { (name, _) -> "$name${DeviceSpec.OPERATOR}${parameters[name]}" }
    report("Device spec parameters $names must use the same unit, got: $values")
  }

  /** Safety net in case the grammar implemented by the renderer drifts from the checks above. */
  private fun checkRendererCanParse() {
    if (problems.isEmpty() && DeviceConfig.toDeviceConfigOrNull(spec, emptyList()) == null) {
      report("Device spec cannot be parsed by the renderer", help = "Expected e.g. '$SPEC_EXAMPLE'")
    }
  }

  /** Records [problem], followed by the spec it was found in and, when given, the [help] on how to fix it. */
  private fun report(problem: String, help: String? = null) {
    val suffix = if (help != null) ". $help" else ""
    problems += "$problem in: '$spec'$suffix"
  }
}
