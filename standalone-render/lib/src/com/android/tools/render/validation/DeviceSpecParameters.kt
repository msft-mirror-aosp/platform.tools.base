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

import com.android.ide.common.util.enumValueOfOrNull
import com.android.tools.preview.config.Cutout
import com.android.tools.preview.config.DimUnit
import com.android.tools.preview.config.ExpandAxis
import com.android.tools.preview.config.KeyboardKind
import com.android.tools.preview.config.Navigation
import com.android.tools.preview.config.Orientation
import com.android.tools.preview.config.PointerPrecision
import com.android.tools.preview.config.Preview.DeviceSpec
import com.android.tools.preview.config.ViewingDistance
import com.android.tools.preview.config.WindowPosture
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A parameter of the DeviceSpec language.
 *
 * @property name The parameter name, e.g. `width`.
 * @property expected Description of the accepted values for error messages, e.g. `a positive integer such as 420`.
 * @property accepts Whether the renderer can parse a value of this parameter, see `DeviceConfig.toDeviceConfigOrNull`.
 */
internal class DeviceSpecParameter(val name: String, val expected: String, val accepts: (String) -> Boolean)

/** A dimension of the DeviceSpec language, e.g. `411dp`. */
internal class DeviceSpecDimension(val value: Float, val unit: DimUnit) {
  companion object {
    /**
     * Parses [text] like `parseAndroidNumberOrNull` in `DeviceConfig.kt` does: a float immediately followed by a [DimUnit] suffix.
     *
     * When [ignoreCase] is `true`, [text] is lowercased with [Locale.US] first, matching `parseMaxSizeOrNull` in `DeviceConfig.kt`.
     */
    fun parse(text: String, ignoreCase: Boolean = false): DeviceSpecDimension? {
      val normalized = if (ignoreCase) text.lowercase(Locale.US) else text
      val unit = enumValueOfOrNull<DimUnit>(normalized.takeLast(2)) ?: return null
      val value = normalized.dropLast(2).toFloatOrNull() ?: return null
      return DeviceSpecDimension(value, unit)
    }
  }
}

/**
 * The parameters of the DeviceSpec language, mirroring the grammar implemented by `DeviceConfig.toDeviceConfigOrNull`.
 *
 * Parameters are listed in the order error messages enumerate them.
 */
internal object DeviceSpecParameters {

  /** Parameters a custom device spec must declare. */
  val REQUIRED_CUSTOM = listOf(DeviceSpec.PARAMETER_WIDTH, DeviceSpec.PARAMETER_HEIGHT)

  /** Parameters of the legacy `spec:shape=Normal,width=360,height=640,unit=dp,dpi=480` format, which the renderer does not support. */
  val LEGACY = setOf("shape", "unit")

  /** Parameters accepted both by custom device specs and by `parent` based device specs. */
  private val COMMON =
    listOf(
      enumParameter<Orientation>(DeviceSpec.PARAMETER_ORIENTATION),
      enumParameter<Cutout>(DeviceSpec.PARAMETER_CUTOUT),
      enumParameter<Navigation>(DeviceSpec.PARAMETER_NAVIGATION),
      enumParameter<WindowPosture>(DeviceSpec.PARAMETER_WINDOW_POSTURE),
      enumParameter<PointerPrecision>(DeviceSpec.PARAMETER_POINTER_PRECISION),
      enumParameter<KeyboardKind>(DeviceSpec.PARAMETER_KEYBOARD_KIND),
      booleanParameter(DeviceSpec.PARAMETER_HAS_CAMERA),
      booleanParameter(DeviceSpec.PARAMETER_HAS_MICROPHONE),
      enumParameter<ViewingDistance>(DeviceSpec.PARAMETER_VIEWING_DISTANCE),
      expandParameter(DeviceSpec.PARAMETER_EXPAND),
      maxSizeParameter(DeviceSpec.PARAMETER_MAX_HEIGHT),
      maxSizeParameter(DeviceSpec.PARAMETER_MAX_WIDTH),
    )

  /**
   * Parameters of a custom device spec such as `spec:width=411dp,height=891dp,dpi=420`, see
   * `DeviceConfig.parseFromCustomDeviceSpecLanguage`.
   */
  val CUSTOM: Map<String, DeviceSpecParameter> =
    (listOf(
        dimensionParameter(DeviceSpec.PARAMETER_WIDTH, allowZero = false),
        dimensionParameter(DeviceSpec.PARAMETER_HEIGHT, allowZero = false),
        DeviceSpecParameter(DeviceSpec.PARAMETER_DPI, "a positive integer such as 420") { (it.toIntOrNull() ?: 0) > 0 },
        booleanParameter(DeviceSpec.PARAMETER_IS_ROUND),
        dimensionParameter(DeviceSpec.PARAMETER_CHIN_SIZE, allowZero = true),
        deviceIdParameter(DeviceSpec.PARAMETER_ID),
      ) + COMMON)
      .associateBy { it.name }

  /**
   * Parameters of a device spec based on an existing device such as `spec:parent=pixel_5,orientation=landscape`, see
   * `DeviceConfig.parseFromExistingDeviceWithDeviceSpecLanguage`.
   */
  val PARENT: Map<String, DeviceSpecParameter> = (listOf(deviceIdParameter(DeviceSpec.PARAMETER_PARENT)) + COMMON).associateBy { it.name }

  private fun deviceIdParameter(name: String) = DeviceSpecParameter(name, "a device id") { it.isNotBlank() }

  /** The renderer parses booleans with `toBooleanStrictOrNull`, so only the lowercase literals are accepted. */
  private fun booleanParameter(name: String) = DeviceSpecParameter(name, "'true' or 'false'") { it.toBooleanStrictOrNull() != null }

  /** The renderer resolves enum values with `enumValueOfOrNull`, so only the exact entry names are accepted. */
  private inline fun <reified E : Enum<E>> enumParameter(name: String): DeviceSpecParameter {
    val expected = "one of " + enumValues<E>().joinToString(", ") { "'${it.name}'" }
    return DeviceSpecParameter(name, expected) { enumValueOfOrNull<E>(it) != null }
  }

  private fun dimensionParameter(name: String, allowZero: Boolean): DeviceSpecParameter {
    val expected = "a ${if (allowZero) "non-negative" else "positive"} dimension with a 'dp' or 'px' suffix such as '411dp'"
    return DeviceSpecParameter(name, expected) { text ->
      val dimension = DeviceSpecDimension.parse(text)
      dimension != null && dimension.value.isFinite() && (if (allowZero) dimension.value >= 0f else dimension.value > 0f)
    }
  }

  /** The renderer parses `expand` case-insensitively with [ExpandAxis.fromSpecValue]. */
  private fun expandParameter(name: String): DeviceSpecParameter {
    val expected = "one of " + ExpandAxis.entries.joinToString(", ") { "'${it.name}'" }
    return DeviceSpecParameter(name, expected) { ExpandAxis.fromSpecValue(it) != null }
  }

  /**
   * The renderer parses `maxHeight` and `maxWidth` with `parseMaxSizeOrNull`, accepting case-insensitive `dp`/`px` suffixes and finite
   * values rounding to at least 1.
   */
  private fun maxSizeParameter(name: String): DeviceSpecParameter {
    val expected = "a positive dimension with a 'dp' or 'px' suffix such as '2000dp'"
    return DeviceSpecParameter(name, expected) { text ->
      val dimension = DeviceSpecDimension.parse(text, ignoreCase = true)
      dimension != null && dimension.value.isFinite() && dimension.value.roundToInt() >= 1
    }
  }
}
