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

package com.android.tools.uicheck.config

/** Preset controlling which UI Check variations [UiCheckConfigurations.plan] adds to each preview. */
enum class UiCheckPreset(val value: String) {
  /** Base preview plus Medium Tablet and 200% font scale, each skipped when the preview sets its own device or font scale. */
  DEFAULT("default"),

  /** Base preview plus all UI Check variations (window sizes, font scales, light/dark theme, and system UI). */
  FULL("full"),

  /** Base preview only, with no extra variation renders. */
  NONE("none");

  companion object {
    private val BY_VALUE = entries.associateBy { it.value }

    /**
     * Parses a preset [value] from a CLI flag, `lint.xml` option, or Gradle configuration (case-insensitive). A blank value returns
     * [DEFAULT].
     *
     * @throws IllegalArgumentException if [value] does not match a known preset.
     */
    fun parse(value: String): UiCheckPreset {
      val normalized = value.trim().lowercase()
      if (normalized.isEmpty()) return DEFAULT
      return BY_VALUE[normalized] ?: throw IllegalArgumentException("Unknown UI Check preset: '$value'")
    }
  }
}
