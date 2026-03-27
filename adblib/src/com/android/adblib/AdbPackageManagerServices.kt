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
package com.android.adblib

import java.io.IOException

/** Support for execution of `pm` commands on a device. */
interface AdbPackageManagerServices {
  /** The session this [AdbPackageManagerServices] instance belongs to. */
  val session: AdbSession

  /**
   * Uses `adb shell pm uninstall` to uninstall an app.
   *
   * @throws AdbPackageManagerException if the `pm` command failed
   * @throws IOException if there was an issue communicating with the device
   */
  suspend fun uninstall(device: DeviceSelector, packageName: String)
}

/** Exception thrown by functions of [AdbPackageManagerServices] */
class AdbPackageManagerException(
  /** The [DeviceSelector] the `pm` command was executed on */
  val device: DeviceSelector,

  /** The `pm` command and argument(s) (e.g. `pm uninstall <packageName>`) */
  val command: String,

  /**
   * The error output of the `pm` command. This is the contents (or a subset of) of either `stderr` or `stdout`, depending on the API level.
   */
  val errorOutput: String,
  cause: Throwable? = null,
) : IOException("Error executing '$command' on ${device.shortDescription}: $errorOutput", cause) {

  /** The device does not support the specified `pm` [command] */
  val isCommandNotSupported: Boolean
    get() = errorOutput.contains("unknown command: ", ignoreCase = true) || errorOutput.contains("unknown command ", ignoreCase = true)
}
