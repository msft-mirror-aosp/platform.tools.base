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

package com.android.tools.ui.inspector.device

import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector

/** The per-app setting that makes the platform expose attribute resolution stacks for a single package. */
private const val DEBUG_VIEW_ATTRIBUTES_PACKAGE_SETTING = "debug_view_attributes_application_package"

/** Marker separating the two `settings get` outputs when both settings are read in a single shell invocation. */
private const val SETTINGS_OUTPUT_SEPARATOR = "__UI_INSPECTOR_SETTINGS_SEPARATOR__"

/** Reads the global and per-app debug-view-attributes settings in one shell invocation. */
private const val READ_DEBUG_VIEW_ATTRIBUTES_CMD =
  "settings get global debug_view_attributes ; echo $SETTINGS_OUTPUT_SEPARATOR ; settings get global $DEBUG_VIEW_ATTRIBUTES_PACKAGE_SETTING"

/** Manages the device setting that makes the platform expose attribute resolution stacks for [packageName]. */
internal class DebugViewAttributes(
  private val adbSession: AdbSession,
  private val deviceSelector: DeviceSelector,
  private val packageName: String,
) {

  /**
   * Enables the per-app [DEBUG_VIEW_ATTRIBUTES_PACKAGE_SETTING] setting for [packageName].
   *
   * The setting is read before writing: if the global `debug_view_attributes` is already enabled (developer options, or residue from older
   * builds of this tool that set it globally) or the per-app setting already names [packageName], no device mutation happens. When the
   * setting is actually flipped it is left set. Changing it in either direction restarts the app's activities, so clearing it per run would
   * pay two restarts. Set-and-leave confines the restart to the first resolution-stack request per app.
   */
  suspend fun enable() {
    val output = adbSession.deviceServices.shellAsTextOrThrow(deviceSelector, READ_DEBUG_VIEW_ATTRIBUTES_CMD).stdout
    val values = output.split(SETTINGS_OUTPUT_SEPARATOR)
    if (values.size != 2) {
      throw IllegalStateException("Unexpected output while reading debug-view-attributes settings: $output")
    }
    // `settings get` prints the literal "null" for an unset key; exact comparisons below treat it as any other non-matching value.
    val global = values[0].trim()
    val perApp = values[1].trim()
    if (global == "1" || perApp == packageName) {
      return
    }
    adbSession.deviceServices.shellAsTextOrThrow(deviceSelector, "settings put global $DEBUG_VIEW_ATTRIBUTES_PACKAGE_SETTING $packageName")
    System.err.println(
      "Enabled view-attribute debugging for $packageName: its activities will restart now, and the setting stays enabled for this app. " +
        "Clear it with: adb shell settings delete global $DEBUG_VIEW_ATTRIBUTES_PACKAGE_SETTING"
    )
  }
}
