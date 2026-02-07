/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.wear.wff

import java.net.URL

/**
 * Represents a Watch Face Format version.
 *
 * @see <a href="https://developer.android.com/training/wearables/wff">Watch Face Format</a>
 */
enum class WFFVersion(val version: String) {
  WFFVersion1("1"),
  WFFVersion2("2"),
  WFFVersion3("3"),
  WFFVersion4("4");

  val schemaUrl: URL = checkNotNull(this::class.java.getResource("/specification/documents/$version/watchface.xsd"))

  companion object {
    fun fromString(version: String?): WFFVersion? = entries.firstOrNull { it.version == version }
  }
}
