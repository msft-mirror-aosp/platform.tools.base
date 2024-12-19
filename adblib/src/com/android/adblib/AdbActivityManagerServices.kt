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

interface AdbActivityManagerServices {
    /**
     * The session this [AdbActivityManagerServices] instance belongs to.
     */
    val session: AdbSession

    /**
     * Uses `adb shell am force-stop` to terminate an app.
     */
    suspend fun forceStop(device: DeviceSelector, packageName: String)

    /**
     * Uses `adb shell am crash` to crash an app.
     *
     * Note that `am crash` command is available on API level > 26.
     */
    suspend fun crash(device: DeviceSelector, packageName: String)

    /**
     * Uses `adb shell am capabilities` to return various device/run-time capabilities
     *
     * Note that `am capabilities` command is available on API level >= 34.
     *
     * Note: See [ActivityManager.capabilities] for a higher level version of this method
     * that supports retrying if the device is not ready.
     *
     * @throws [AdbFailResponseException] if the device is not [DeviceState.ONLINE]
     * @throws [AdbActivityManagerException] if the `am` command failed
     * @throws [IOException] if there was an issue communicating with the device
     * @see [AdbActivityManagerException.isServiceNotRunning]
     * @see [AdbActivityManagerException.isCommandNotSupported]
     */
    suspend fun capabilities(device: DeviceSelector): AmCapabilitiesResult
}

class AdbActivityManagerException(
    /**
     * The command argument to `am` (e.g. `start-activity`)
     */
    val command: String,
    /**
     * The output (`stderr`) of the `am` command
     *
     * Examples of failures:
     * * The operation not supported (API < 34): "Unknown command: capabilities"
     * * The "am" service is not yet started on device: "cmd: Can't find service: activity"
     */
    val stderr: String,
    cause: Throwable? = null
) : IOException("Error executing 'am $command' on device: $stderr", cause) {

    /**
     * The device does not support the specified `am` [command]
     */
    val isCommandNotSupported: Boolean
        get() = stderr.contains("Unknown command: ", ignoreCase = true)

    /**
     * The `activity` service is not running (probably because the device is still in the
     * boot process, where the device is already online, but not all services are started)
     */
    val isServiceNotRunning: Boolean
        get() = stderr.contains("Can't find service: activity", ignoreCase = true)
}

/**
 * The result of [AdbActivityManagerServices.capabilities]
 *
 * * See [VMInfo capability protobuf definition](https://cs.android.com/android/platform/superproject/main/+/1b409eb6cacc9508e6f415353ddcacdcb6bdaf26:frameworks/base/proto/src/am_capabilities.proto)
 * * See [ActivityManagerShellCommand.capabilities implementation](https://cs.android.com/android/platform/superproject/main/+/1b409eb6cacc9508e6f415353ddcacdcb6bdaf26:frameworks/base/services/core/java/com/android/server/am/ActivityManagerShellCommand.java;l=463)
 */
data class AmCapabilitiesResult(
    /**
     * Capabilities of the `am` shell command
     */
    val capabilities: List<String>,
    /**
     * Capabilities of the Android VM used for Java apps on the device
     */
    val vmCapabilities: List<String>,
    /**
     * Capabilities of the Android framework on the device
     */
    val frameworkCapabilities: List<String>,
    /**
     * Name and version of the Android VM for Java apps on the device
     */
    val vmInfo: VmInfo?
) {

    data class VmInfo(
        /**
         * The value of the "java.vm.name" system property on the Android device
         */
        val name: String,
        /**
         * The value of the "java.vm.version" system property on the Android device
         */
        val version: String)
}
