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

import com.android.adblib.AdbDeviceServices
import com.android.adblib.DeviceSelector
import com.android.adblib.ShellCommandOutput
import com.android.adblib.shellAsText

/**
 * Runs [command] on the device and returns its output, throwing when the command exits non-zero. The throwing variant of [shellAsText], for
 * callers where a failed command means the operation failed. Callers that interpret failures themselves (best-effort diagnostics, cleanup,
 * commands whose exit code carries meaning) use [shellAsText] directly.
 */
internal suspend fun AdbDeviceServices.shellAsTextOrThrow(device: DeviceSelector, command: String): ShellCommandOutput {
  val result = shellAsText(device, command)
  if (result.exitCode != 0) {
    throw IllegalStateException("Command '$command' failed with exit code ${result.exitCode}. Stderr: ${result.stderr}")
  }
  return result
}
