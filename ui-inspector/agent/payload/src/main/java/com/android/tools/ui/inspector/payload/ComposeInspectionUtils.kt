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

package com.android.tools.ui.inspector.payload

/** AndroidX Jetpack libraries bundle their version string in this resource file. */
private const val COMPOSE_UI_VERSION_RESOURCE_PATH = "META-INF/androidx.compose.ui_ui.version"

/** Discovers the Jetpack Compose version programmatically on the classpath of the given [classLoader]. */
internal fun detectComposeVersion(classLoader: ClassLoader): String? {
  try {
    // If the anchor Modifier class is missing, we know Compose is not on the classpath.
    classLoader.loadClass("androidx.compose.ui.Modifier")
  } catch (e: ClassNotFoundException) {
    return null
  }
  return try {
    classLoader.getResourceAsStream(COMPOSE_UI_VERSION_RESOURCE_PATH)?.use { it.bufferedReader().readText().trim() }
  } catch (e: Exception) {
    null
  }
}
