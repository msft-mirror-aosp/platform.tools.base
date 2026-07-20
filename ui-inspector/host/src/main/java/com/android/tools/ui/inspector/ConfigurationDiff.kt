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

import java.lang.reflect.Modifier

/** Holds the set of modifications between two device configurations. */
internal data class ConfigurationDiff(val oldConfig: DeviceConfiguration, val newConfig: DeviceConfiguration) {
  val hasChanges: Boolean
    get() = oldConfig != newConfig

  /** Represents a difference in a single configuration field. */
  data class FieldDifference(val name: String, val oldValue: Any?, val newValue: Any?)

  val differences: List<FieldDifference> by lazy {
    DeviceConfiguration::class
      .java
      .declaredFields
      .filter { field -> !field.isSynthetic && !Modifier.isStatic(field.modifiers) }
      .sortedBy { it.name }
      .mapNotNull { field ->
        field.isAccessible = true
        val oldVal = field.get(oldConfig)
        val newVal = field.get(newConfig)
        if (oldVal != newVal) {
          FieldDifference(field.name, oldVal, newVal)
        } else {
          null
        }
      }
  }
}

/** Compares two configurations and returns a [ConfigurationDiff] representing the changes, or null if equal or one is missing. */
internal fun createConfigurationDiff(oldConfig: DeviceConfiguration?, newConfig: DeviceConfiguration?): ConfigurationDiff? {
  if (oldConfig == null || newConfig == null) return null
  if (oldConfig == newConfig) return null
  return ConfigurationDiff(oldConfig, newConfig)
}
