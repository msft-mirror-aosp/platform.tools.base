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

package com.android.tools.ui.inspector

import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol

/** Converts a CamelCase string into a snake_case string. */
private fun String.camelToSnake(): String = buildString {
  for (char in this@camelToSnake) {
    if (char.isUpperCase()) {
      if (isNotEmpty()) append('_')
      append(char.lowercaseChar())
    } else {
      append(char)
    }
  }
}

/**
 * Formats a protobuf Enum value name into a clean, human-readable lowercase string.
 *
 * Protobuf enum names are traditionally declared in UPPERCASE and prefixed with their enum type prefix (e.g., `ORIENTATION_LANDSCAPE` or
 * `TOUCH_SCREEN_FINGER`) to avoid namespace conflicts in generated bindings. This helper dynamically determines the prefix from the enum's
 * class name, removes it, and converts the remaining string to lowercase for clean console output.
 */
private fun Enum<*>.protobufPrettyPrint(): String {
  val prefix = this::class.java.simpleName.camelToSnake()
  return name.lowercase().removePrefix("${prefix}_")
}

/** Prints the device configuration to the console in a human-readable format. */
internal fun printDeviceConfiguration(config: ViewInspectorProtocol.Configuration, stringTable: Map<Int, String>) {
  val language = stringTable[config.locale.language] ?: ""
  val country = stringTable[config.locale.country] ?: ""
  val variant = stringTable[config.locale.variant] ?: ""
  val script = stringTable[config.locale.script] ?: ""
  val localeStr = listOf(language, country, variant, script).filter { it.isNotEmpty() }.joinToString("-")

  val orientationStr = config.orientation.protobufPrettyPrint()
  val sizeStr = config.screenLayoutSize.protobufPrettyPrint()
  val aspectStr = config.screenLayoutLong.protobufPrettyPrint()
  val directionStr = config.layoutDirection.protobufPrettyPrint()
  val shapeStr = config.screenLayoutRound.protobufPrettyPrint()
  val wideGamutStr = config.colorModeWideGamut.protobufPrettyPrint()
  val hdrStr = config.colorModeHdr.protobufPrettyPrint()
  val touchStr = config.touchScreen.protobufPrettyPrint()
  val keyboardStr = config.keyboard.protobufPrettyPrint()
  val keyboardHiddenStr = config.keyboardHidden.protobufPrettyPrint()
  val hardKeyboardHiddenStr = config.hardKeyboardHidden.protobufPrettyPrint()
  val navigationStr = config.navigation.protobufPrettyPrint()
  val navigationHiddenStr = config.navigationHidden.protobufPrettyPrint()
  val uiModeTypeStr = config.uiModeType.protobufPrettyPrint()
  val uiModeNightStr = config.uiModeNight.protobufPrettyPrint()
  val grammaticalGenderStr = config.grammaticalGender.protobufPrettyPrint()

  System.out.println("Device Configuration:")
  System.out.println(" Orientation: $orientationStr")
  System.out.println(" Density: ${config.density} dpi")
  System.out.println(" Screen Width: ${config.screenWidthDp} dp")
  System.out.println(" Screen Height: ${config.screenHeightDp} dp")
  System.out.println(" Smallest Screen Width: ${config.smallestScreenWidthDp} dp")
  System.out.println(" Screen Size: $sizeStr")
  System.out.println(" Screen Aspect: $aspectStr")
  System.out.println(" Layout Direction: $directionStr")
  System.out.println(" Screen Shape: $shapeStr")
  System.out.println(" Color Wide Gamut: $wideGamutStr")
  System.out.println(" Color HDR: $hdrStr")
  System.out.println(" Touchscreen: $touchStr")
  System.out.println(" Keyboard: $keyboardStr")
  System.out.println(" Keyboard Hidden: $keyboardHiddenStr")
  System.out.println(" Hard Keyboard Hidden: $hardKeyboardHiddenStr")
  System.out.println(" Navigation: $navigationStr")
  System.out.println(" Navigation Hidden: $navigationHiddenStr")
  System.out.println(" UI Mode Type: $uiModeTypeStr")
  System.out.println(" UI Mode Night: $uiModeNightStr")
  if (localeStr.isNotEmpty()) {
    System.out.println(" Locale: $localeStr")
  }
  System.out.println(" Font Scale: ${config.fontScale}")
  if (config.grammaticalGender != ViewInspectorProtocol.GrammaticalGender.GRAMMATICAL_GENDER_UNDEFINED) {
    System.out.println(" Grammatical Gender: $grammaticalGenderStr")
  }
  System.out.println()
}
