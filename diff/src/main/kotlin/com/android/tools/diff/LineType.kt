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
package com.android.tools.diff

/**
 * The type of a line in a diff.
 *
 * @property prefix The character used to represent this line type in unified diff format.
 */
enum class LineType(val prefix: Char) {
  CONTEXT(' '),
  ADDED('+'),
  REMOVED('-');

  companion object {
    /**
     * Returns the [LineType] matching the given unified diff prefix character, or `null` if the character is not a recognized unified diff
     * prefix.
     */
    fun fromPrefix(prefix: Char): LineType? = entries.find { it.prefix == prefix }
  }
}
