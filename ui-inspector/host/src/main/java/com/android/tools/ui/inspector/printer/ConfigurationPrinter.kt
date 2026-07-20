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

package com.android.tools.ui.inspector.printer

import com.android.tools.ui.inspector.AppContext
import com.android.tools.ui.inspector.DeviceConfiguration
import com.android.tools.ui.inspector.DeviceLocale
import com.android.tools.ui.inspector.Dimension
import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal fun DeviceLocale.format(): String = listOfNotNull(language, country, variant, script).filter { it.isNotEmpty() }.joinToString("-")

/** Prints the device configuration to the console in a human-readable format. */
internal fun printDeviceConfiguration(config: DeviceConfiguration) {
  System.out.println("Device Configuration:")
  DeviceConfiguration::class
    .java
    .declaredFields
    .filter { field -> !field.isSynthetic && !Modifier.isStatic(field.modifiers) }
    .sortedBy { it.name }
    .forEach { field ->
      field.isAccessible = true
      val name = formatPropertyName(field.name)
      val rawValue = field.get(config)
      val formattedValue = formatValueForPrinting(field, rawValue)
      if (formattedValue != null) {
        System.out.println(" $name: $formattedValue")
      }
    }
  System.out.println()
}

private fun formatValueForPrinting(field: Field, value: Any?): String? {
  return when {
    value is DeviceLocale -> {
      val str = value.format()
      if (str.isNotEmpty()) str else null
    }
    // TODO this is brittle
    field.name == "grammaticalGender" -> {
      (value as? Enum<*>)?.let { getEnumDisplayValue(it) }
    }
    Enum::class.java.isAssignableFrom(field.type) -> {
      if (value is Enum<*>) {
        getEnumDisplayValue(value)
      } else {
        "undefined"
      }
    }
    value is Dimension.Dp -> "${value.value} dp"
    value is Dimension.Dpi -> "${value.value} dpi"
    // Fallback default values for null/unset fields in minimal configuration dumps
    field.type == Dimension.Dp::class.java -> "0 dp"
    field.type == Dimension.Dpi::class.java -> "0 dpi"
    field.type == Float::class.javaObjectType || field.type == Float::class.java -> "${value ?: 0.0}"
    value != null -> value.toString()
    else -> null
  }
}

private fun getEnumDisplayValue(enumValue: Enum<*>): String {
  return enumValue.name.lowercase()
}

/** Prints the application context (theme and display info) to the console. */
internal fun printAppContext(appContext: AppContext) {
  System.out.println("App Context:")
  System.out.println(" Theme: ${appContext.theme ?: "undefined"}")
  if (appContext.displays.isNotEmpty()) {
    System.out.println(" Displays:")
    appContext.displays.forEach { display ->
      System.out.println("  - Display ${display.id}: ${display.widthPx}x${display.heightPx} px, rotation ${display.orientation}°")
    }
  }
  System.out.println()
}
