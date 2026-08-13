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

package com.android.tools.androidtest.testengine.adb

/**
 * A provider that retrieves and caches the API level of an Android device.
 *
 * This class prevents running the same adb command multiple times to get the API level.
 */
class DeviceApiLevelProvider(private val adbController: AdbController, private val deviceSerial: String) {
  /** The API level of the target device. */
  val deviceApiLevel: Int by lazy {
    val result = adbController.runAdbShellCommand(deviceSerial, listOf("getprop", "ro.build.version.sdk"))
    if (result.exitCode == 0) {
      result.output.trim().toIntOrNull() ?: throw RuntimeException("Failed to parse device API level for $deviceSerial: '${result.output}'")
    } else {
      throw RuntimeException(
        "Failed to get device API level for $deviceSerial via ADB (exit code: ${result.exitCode}). Stdout: '${result.output}'. Stderr: '${result.errorOutput}'"
      )
    }
  }
}
