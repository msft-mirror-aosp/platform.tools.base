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
import com.google.protobuf.Descriptors

/** Holds the set of modifications between two device configurations. */
internal data class ConfigurationDiff(
  val oldConfig: ViewInspectorProtocol.Configuration,
  val newConfig: ViewInspectorProtocol.Configuration,
  val oldStrings: Map<Int, String>,
  val newStrings: Map<Int, String>,
) {
  val hasChanges: Boolean
    get() = oldConfig != newConfig

  /** Represents a difference in a single configuration field. */
  class FieldDifference(val field: Descriptors.FieldDescriptor, val oldValue: Any, val newValue: Any)

  val differences: List<FieldDifference> by lazy {
    val diffs = mutableListOf<FieldDifference>()
    val descriptor = ViewInspectorProtocol.Configuration.getDescriptor()
    for (fieldDescriptor in descriptor.fields) {
      val oldVal = oldConfig.getField(fieldDescriptor)
      val newVal = newConfig.getField(fieldDescriptor)
      if (oldVal != newVal) {
        diffs.add(FieldDifference(fieldDescriptor, oldVal, newVal))
      }
    }
    diffs
  }
}

/** Compares two configurations and returns a [ConfigurationDiff] representing the changes, or null if equal or one is missing. */
internal fun createConfigurationDiff(
  oldConfig: ViewInspectorProtocol.Configuration?,
  newConfig: ViewInspectorProtocol.Configuration?,
  oldStrings: Map<Int, String>,
  newStrings: Map<Int, String>,
): ConfigurationDiff? {
  if (oldConfig == null || newConfig == null) return null
  if (oldConfig == newConfig) return null
  return ConfigurationDiff(oldConfig, newConfig, oldStrings, newStrings)
}
