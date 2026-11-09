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

import com.android.ide.common.resources.configuration.LocaleQualifier
import com.android.ide.common.resources.parseColor
import com.android.resources.ResourceType
import com.android.resources.ResourceUrl
import com.android.sdklib.SdkVersionInfo
import com.android.sdklib.devices.Device
import com.android.tools.configurations.UiModeState
import com.android.tools.configurations.Wallpaper
import com.android.tools.preview.MAX_DIMENSION_DP
import com.android.tools.preview.MAX_FONT_SCALE
import com.android.tools.preview.UNDEFINED_API_LEVEL
import com.android.tools.preview.UNDEFINED_DIMENSION
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.drawable.DrawablePreviewView
import com.android.tools.render.drawable.DrawableScreenshot
import com.android.tools.render.layout.LayoutScreenshot
import java.util.HexFormat

const val PARAMETER_LEVEL = "level"
const val PARAMETER_STATE = "state"

/** Validation rules and limits for individual preview annotation parameters. */
object ParameterValidators {

  const val MIN_FONT_SCALE = 0.0f

  /** Smallest valid `backgroundColor`: fully transparent black (`0x00000000`). */
  const val MIN_BACKGROUND_COLOR = 0L

  /** Largest valid `backgroundColor`: opaque white (`0xFFFFFFFF`), the maximum value of a 32-bit ARGB color. */
  const val MAX_BACKGROUND_COLOR = 0xFFFFFFFFL

  /** Lowest `apiLevel` accepted for a preview, the same lower bound Android Studio uses for `compileSdk`. */
  const val MIN_API_LEVEL = SdkVersionInfo.LOWEST_COMPILE_SDK_VERSION

  /** Highest `apiLevel` accepted for a preview, the highest API level supported by the tools. */
  const val MAX_API_LEVEL = SdkVersionInfo.HIGHEST_SUPPORTED_API

  /** Value for `wallpaper` when dynamic color is disabled (`Wallpapers.NONE`). */
  const val NO_WALLPAPER = -1

  /** Highest valid `wallpaper` index, corresponding to the last entry in [Wallpaper]. */
  val MAX_WALLPAPER = Wallpaper.entries.lastIndex

  /** Lowest valid `level` for a drawable (`Drawable.setLevel`). */
  const val MIN_DRAWABLE_LEVEL = 0

  /**
   * Highest valid `level` for a drawable: `Drawable.setLevel` takes levels from 0 to 10000, which `<level-list>` items, `<clip>` and
   * `<scale>` drawables map to the full range of their effect.
   */
  const val MAX_DRAWABLE_LEVEL = 10000

  /** Drawable states accepted by the `state` parameter, see [DrawablePreviewView.States]. */
  val DRAWABLE_STATES: Set<String> = DrawablePreviewView.States.ATTRS.keys + DrawablePreviewView.States.DISABLED

  private const val UI_MODE_TYPE_MASK = UiModeState.UI_MODE_TYPE_MASK
  /** Highest defined `Configuration.UI_MODE_TYPE_*` value (`UI_MODE_TYPE_VR_HEADSET`). */
  private const val MAX_UI_MODE_TYPE = 0x07
  private const val UI_MODE_NIGHT_MASK = UiModeState.UI_MODE_NIGHT_NO or UiModeState.UI_MODE_NIGHT_YES
  private const val VALID_UI_MODE_BITS = UI_MODE_TYPE_MASK or UI_MODE_NIGHT_MASK

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
      value.toFloatOrNull()?.takeIf { it.isFinite() }
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

  /**
   * Validates the `apiLevel` parameter.
   *
   * Besides `-1` (undefined), only API levels within [MIN_API_LEVEL]..[MAX_API_LEVEL] are accepted, matching the range enforced by the
   * `PreviewApiLevelMustBeValid` inspection in Android Studio when no SDK platform is installed.
   */
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
      apiLevel !in MIN_API_LEVEL..MAX_API_LEVEL ->
        listOf(
          ValidationIssue(
            message =
              "Parameter 'apiLevel' must be -1 (undefined) or an API level between $MIN_API_LEVEL and $MAX_API_LEVEL, got: $apiLevel",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = "apiLevel",
            value = value,
          )
        )
      else -> emptyList()
    }
  }

  /**
   * Validates the `locale` parameter.
   *
   * The value must be a locale resource qualifier (`en`, `en-rUS`, `b+sr+Latn+RS`) because that is what the renderer resolves it with
   * (`Locale.create`), which silently falls back to the default locale for anything else, e.g. the BCP-47 style `en-US`.
   */
  fun validateLocale(value: String?): List<ValidationIssue> {
    if (value.isNullOrEmpty() || LocaleQualifier.getQualifier(value) != null) return emptyList()
    return listOf(
      ValidationIssue(
        message = "Parameter 'locale' must be a locale resource qualifier such as 'en', 'en-rUS' or 'b+sr+Latn+RS', got: '$value'",
        severity = ValidationSeverity.ERROR,
        category = ValidationCategory.PARAMETER,
        target = "locale",
        value = value,
      )
    )
  }

  /**
   * Validates the `uiMode` parameter.
   *
   * The value must be `0` (unset) or a `Configuration.UI_MODE_*` bitmask combining a `UI_MODE_TYPE_*` value (`0..7`) with at most one of
   * `UI_MODE_NIGHT_NO` (`0x10`) and `UI_MODE_NIGHT_YES` (`0x20`). `UiModeState.setUiModeFlagValue` ignores unknown upper bits and falls
   * back to `NORMAL` / `NOTNIGHT` for unknown type values or conflicting night flags.
   */
  fun validateUiMode(value: String?): List<ValidationIssue> {
    if (value.isNullOrEmpty()) return emptyList()
    val uiMode =
      value.toIntOrNull()
        ?: return listOf(
          ValidationIssue(
            message = "Parameter 'uiMode' must be an integer, got: '$value'",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = "uiMode",
            value = value,
          )
        )

    val isValidBitmask =
      uiMode and VALID_UI_MODE_BITS.inv() == 0 &&
        (uiMode and UI_MODE_TYPE_MASK) <= MAX_UI_MODE_TYPE &&
        (uiMode and UI_MODE_NIGHT_MASK) != UI_MODE_NIGHT_MASK
    if (isValidBitmask) return emptyList()

    return listOf(
      ValidationIssue(
        message = "Parameter 'uiMode' must be a valid Configuration.UI_MODE_* bitmask, got: $uiMode",
        severity = ValidationSeverity.ERROR,
        category = ValidationCategory.PARAMETER,
        target = "uiMode",
        value = value,
      )
    )
  }

  /**
   * Validates the `backgroundColor` parameter, together with the `showBackground` parameter that controls whether it is applied on
   * annotation-based previews.
   *
   * When [usesStringBasedBackgroundColor] is `false` (the default, used for `@Preview` annotations), the value must be the decimal
   * representation of a 32-bit ARGB `Long` (`4278255360` for `0xFF00FF00`) and warns when `showBackground` is not `true`.
   *
   * When [usesStringBasedBackgroundColor] is `true` (used for resource previews such as drawables and layouts), the value must be a
   * string-based color (`#RGB`, `#ARGB`, `#RRGGBB` or `#AARRGGBB`), a color resource (`@color/name`, `@android:color/name`), or a theme
   * attribute (`?attr/name`, `?android:attr/name`, `?name`). Private references such as `@*android:color/name` are rejected.
   */
  fun validateBackgroundColor(
    value: String?,
    showBackground: String?,
    usesStringBasedBackgroundColor: Boolean = false,
  ): List<ValidationIssue> {
    if (value.isNullOrEmpty()) return emptyList()
    if (usesStringBasedBackgroundColor) return validateStringBasedBackgroundColor(value)

    val color =
      value.toLongOrNull()
        ?: return listOf(
          ValidationIssue(
            message =
              "Parameter 'backgroundColor' must be a 32-bit ARGB color as a decimal Long, e.g. 4278255360 (0xFF00FF00 in Kotlin), " +
                "got: '$value'. Hexadecimal strings are not supported",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = "backgroundColor",
            value = value,
          )
        )

    return when {
      color !in MIN_BACKGROUND_COLOR..MAX_BACKGROUND_COLOR ->
        listOf(
          ValidationIssue(
            message = "Parameter 'backgroundColor' must be a 32-bit ARGB color between 0 and 4294967295 (0xFFFFFFFF), got: $value",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = "backgroundColor",
            value = value,
          )
        )
      !showBackground.equals("true", ignoreCase = true) ->
        listOf(
          ValidationIssue(
            message = "Parameter 'backgroundColor' has no effect unless 'showBackground' is set to true",
            severity = ValidationSeverity.WARNING,
            category = ValidationCategory.PARAMETER,
            target = "backgroundColor",
            value = value,
          )
        )
      else -> emptyList()
    }
  }

  /**
   * Validates the `wallpaper` parameter.
   *
   * Accepted values are [NO_WALLPAPER] (`-1`, dynamic color disabled) or a [Wallpaper] ordinal (`0..`[MAX_WALLPAPER]).
   * `PreviewConfiguration.applyTo` resolves the index with `Wallpaper.values().getOrNull`, which silently drops out-of-range values.
   */
  fun validateWallpaper(value: String?): List<ValidationIssue> {
    if (value.isNullOrEmpty()) return emptyList()
    val wallpaper =
      value.toIntOrNull()
        ?: return listOf(
          ValidationIssue(
            message = "Parameter 'wallpaper' must be an integer, got: '$value'",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = "wallpaper",
            value = value,
          )
        )

    if (wallpaper == NO_WALLPAPER || wallpaper in 0..MAX_WALLPAPER) return emptyList()
    return listOf(
      ValidationIssue(
        message = "Parameter 'wallpaper' must be $NO_WALLPAPER (none) or between 0 and $MAX_WALLPAPER, got: $wallpaper",
        severity = ValidationSeverity.ERROR,
        category = ValidationCategory.PARAMETER,
        target = "wallpaper",
        value = value,
      )
    )
  }

  /** Validates the `level` parameter for drawable previews (`0..10000`, matching `Drawable.setLevel`). */
  fun validateLevel(value: String?): List<ValidationIssue> {
    if (value.isNullOrEmpty()) return emptyList()
    val level =
      value.toIntOrNull()
        ?: return listOf(
          ValidationIssue(
            message = "Parameter '$PARAMETER_LEVEL' must be an integer, got: '$value'",
            severity = ValidationSeverity.ERROR,
            category = ValidationCategory.PARAMETER,
            target = PARAMETER_LEVEL,
            value = value,
          )
        )

    if (level in MIN_DRAWABLE_LEVEL..MAX_DRAWABLE_LEVEL) return emptyList()
    return listOf(
      ValidationIssue(
        message = "Parameter '$PARAMETER_LEVEL' must be between $MIN_DRAWABLE_LEVEL and $MAX_DRAWABLE_LEVEL, got: $level",
        severity = ValidationSeverity.ERROR,
        category = ValidationCategory.PARAMETER,
        target = PARAMETER_LEVEL,
        value = value,
      )
    )
  }

  /**
   * Validates the comma-separated `state` parameter for drawable previews (`pressed`, `focused`, `selected`, `checked`, `checkable`,
   * `activated`, `hovered`, or `disabled`).
   */
  fun validateState(value: String?): List<ValidationIssue> {
    if (value.isNullOrEmpty()) return emptyList()
    val invalidStates = value.split(',').map { it.trim() }.filter { it.isNotEmpty() && it !in DRAWABLE_STATES }.distinct()
    if (invalidStates.isEmpty()) return emptyList()
    val allowed = DRAWABLE_STATES.joinToString()
    return invalidStates.map { state ->
      ValidationIssue(
        message = "Parameter '$PARAMETER_STATE' has invalid state '$state'; expected one of: $allowed",
        severity = ValidationSeverity.ERROR,
        category = ValidationCategory.PARAMETER,
        target = PARAMETER_STATE,
        value = value,
      )
    }
  }

  /**
   * Validates the `device` parameter: its syntax and, when the device catalog [devices] is given, that the referenced devices exist.
   *
   * @see DeviceParameterValidator
   */
  fun validateDevice(value: String?, devices: Collection<Device>? = null): List<ValidationIssue> =
    validateDevice(value, devices?.let(::DeviceCatalog))

  /** Like [validateDevice], with the device catalog already indexed, so that it is not indexed again for every preview. */
  internal fun validateDevice(value: String?, catalog: DeviceCatalog?): List<ValidationIssue> =
    DeviceParameterValidator.validate(value, catalog)

  private fun validateStringBasedBackgroundColor(value: String): List<ValidationIssue> {
    if (isValidHexColor(value) || isValidColorReference(value)) return emptyList()
    return listOf(
      ValidationIssue(
        message =
          "Parameter 'backgroundColor' must be #RGB, #ARGB, #RRGGBB, #AARRGGBB, @[<package>:]color/<name> or " +
            "?[<package>:][attr/]<name>, got: '$value'",
        severity = ValidationSeverity.ERROR,
        category = ValidationCategory.PARAMETER,
        target = "backgroundColor",
        value = value,
      )
    )
  }

  private fun isValidHexColor(value: String): Boolean =
    value.startsWith('#') && value.drop(1).all { HexFormat.isHexDigit(it.code) } && parseColor(value) != null

  private fun isValidColorReference(value: String): Boolean {
    val colon = value.indexOf(':')
    val slash = value.indexOf('/')
    if (colon >= 0 && slash in 0 until colon) return false
    val url = ResourceUrl.parse(value) ?: return false
    if (url.isPrivateAccessOverride || !url.hasValidName()) return false
    val namespace = url.namespace
    if (namespace != null && !isValidPackageName(namespace)) return false
    return when (url.urlType) {
      ResourceUrl.UrlType.NORMAL -> url.type == ResourceType.COLOR
      ResourceUrl.UrlType.THEME -> url.type == ResourceType.ATTR
      else -> false
    }
  }

  private fun isValidPackageName(value: String): Boolean =
    value.split('.').all { segment ->
      segment.isNotEmpty() &&
        Character.isJavaIdentifierStart(segment[0]) &&
        (1 until segment.length).all { Character.isJavaIdentifierPart(segment[it]) }
    }
}

/**
 * Main orchestrator for validating preview parameters.
 *
 * @param devices the device catalog the previews are rendered with, used to check that the devices they reference exist. When null, only
 *   the syntax of the `device` parameter is validated.
 */
class PreviewValidator(devices: Collection<Device>? = null) : Validator<PreviewScreenshot> {

  private val deviceCatalog: DeviceCatalog? = devices?.let(::DeviceCatalog)

  /**
   * Validates a [PreviewScreenshot]. Resource-based previews ([DrawableScreenshot] and [LayoutScreenshot]) use string-based
   * `backgroundColor` values (`#RRGGBB`, `@color/name`, `?attr/name`) and trim their parameter values, whereas annotation-based previews
   * take `backgroundColor` as the decimal ARGB `Long` value of `@Preview`.
   */
  override fun validate(target: PreviewScreenshot): ValidationResult {
    if (target is DrawableScreenshot || target is LayoutScreenshot) {
      return validateParams(
        target.previewParams.mapValues { it.value.trim() },
        usesStringBasedBackgroundColor = true,
      )
    }
    return validateParams(target.previewParams)
  }

  /**
   * Validates raw preview parameters map. When [usesStringBasedBackgroundColor] is `true`, `backgroundColor` is validated as a string-based
   * color or color/attribute reference rather than a `@Preview` decimal ARGB `Long`.
   */
  fun validateParams(previewParams: Map<String, String>, usesStringBasedBackgroundColor: Boolean = false): ValidationResult {
    val issues = mutableListOf<ValidationIssue>()

    issues.addAll(ParameterValidators.validateDimension("widthDp", previewParams["widthDp"]))
    issues.addAll(ParameterValidators.validateDimension("heightDp", previewParams["heightDp"]))
    issues.addAll(ParameterValidators.validateFontScale(previewParams["fontScale"]))
    issues.addAll(ParameterValidators.validateApiLevel(previewParams["apiLevel"]))
    issues.addAll(ParameterValidators.validateLocale(previewParams["locale"]))
    issues.addAll(ParameterValidators.validateUiMode(previewParams["uiMode"]))
    issues.addAll(
      ParameterValidators.validateBackgroundColor(
        previewParams["backgroundColor"],
        previewParams["showBackground"],
        usesStringBasedBackgroundColor = usesStringBasedBackgroundColor,
      )
    )
    issues.addAll(ParameterValidators.validateWallpaper(previewParams["wallpaper"]))
    issues.addAll(ParameterValidators.validateLevel(previewParams[PARAMETER_LEVEL]))
    issues.addAll(ParameterValidators.validateState(previewParams[PARAMETER_STATE]))
    issues.addAll(ParameterValidators.validateDevice(previewParams["device"], deviceCatalog))

    return ValidationResult(issues)
  }
}
