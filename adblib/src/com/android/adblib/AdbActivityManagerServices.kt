/*
 * Copyright (C) 2024 The Android Open Source Project
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

/** Support for execution of `am` commands on a device. */
interface AdbActivityManagerServices {
  /** The session this [AdbActivityManagerServices] instance belongs to. */
  val session: AdbSession

  /** Uses `adb shell am force-stop` to terminate an app. */
  suspend fun forceStop(device: DeviceSelector, packageName: String)

  /**
   * Uses `adb shell am crash` to crash an app.
   *
   * Note that `am crash` command is available on API level > 26.
   */
  suspend fun crash(device: DeviceSelector, packageName: String)

  /**
   * Uses `adb shell am gc` to trigger garbage collection on a process.
   *
   * Note: You can use the [capabilities] method to check if the `gc` command is supported by the `am` implementation on the device. This
   * method will throw an [AdbActivityManagerException] if the `gc` command is not supported.
   */
  suspend fun gc(device: DeviceSelector, pid: Int)

  /**
   * Uses `adb shell am capabilities` to return various device/run-time capabilities
   *
   * Note that `am capabilities` command is available on API level >= 34.
   *
   * Note: See [ActivityManager.capabilities] for a higher level version of this method that supports retrying if the device is not ready.
   *
   * @throws [AdbFailResponseException] if the device is not [DeviceState.ONLINE]
   * @throws [AdbActivityManagerException] if the `am` command failed
   * @throws [IOException] if there was an issue communicating with the device
   * @see [AdbActivityManagerException.isCommandNotSupported]
   */
  suspend fun capabilities(device: DeviceSelector): AmCapabilitiesResult
}

/** Exception thrown by functions of [AdbActivityManagerServices] */
class AdbActivityManagerException(
  /** The [DeviceSelector] the `am` command was executed on */
  val device: DeviceSelector,

  /** The `am` command and argument(s) (e.g. `am capabilities --protobuf`) */
  val command: String,

  /**
   * The error output of the `am` command. This is the contents (or a subset of) of either `stderr` or `stdout`, depending on the API level.
   *
   * Examples of failures:
   * * The operation not supported (API < 34): "Unknown command: capabilities"
   * * The "am" service is not yet started on device: "cmd: Can't find service: activity"
   */
  val errorOutput: String,
  cause: Throwable? = null,
) : IOException("Error executing '$command' on ${device.shortDescription}: $errorOutput", cause) {

  /**
   * The device does not support the specified `am` [command]
   *
   * ## API 16-25
   *
   * ```
   * (... lots of output with usage info ...)
   * Error: unknown command <command>
   * ```
   *
   * ## API 26-36+
   *
   * ```
   * Unknown command: <command>
   * ```
   */
  val isCommandNotSupported: Boolean
    get() = errorOutput.contains("unknown command: ", ignoreCase = true) || errorOutput.contains("unknown command ", ignoreCase = true)
}

/**
 * The result of [AdbActivityManagerServices.capabilities]
 * * See [VMInfo capability protobuf definition](https://cs.android.com/android/platform/superproject/main/+/1b409eb6cacc9508e6f415353ddcacdcb6bdaf26:frameworks/base/proto/src/am_capabilities.proto)
 * * See [ActivityManagerShellCommand.capabilities implementation](https://cs.android.com/android/platform/superproject/main/+/1b409eb6cacc9508e6f415353ddcacdcb6bdaf26:frameworks/base/services/core/java/com/android/server/am/ActivityManagerShellCommand.java;l=463)
 */
data class AmCapabilitiesResult(
  /** Capabilities of the `am` shell command */
  val capabilities: List<String>,
  /** Capabilities of the Android VM used for Java apps on the device */
  val vmCapabilities: List<String>,
  /** Capabilities of the Android framework on the device */
  val frameworkCapabilities: List<String>,
  /** Name and version of the Android VM for Java apps on the device */
  val vmInfo: VmInfo?,
) {

  data class VmInfo(
    /** The value of the "java.vm.name" system property on the Android device */
    val name: String,
    /** The value of the "java.vm.version" system property on the Android device */
    val version: String,
  )
}
