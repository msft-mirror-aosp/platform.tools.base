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
package com.android.adblib.impl

import com.android.adblib.AdbActivityManagerException
import com.android.adblib.AdbActivityManagerServices
import com.android.adblib.AdbDeviceServices
import com.android.adblib.AdbSession
import com.android.adblib.AmCapabilitiesResult
import com.android.adblib.DeviceSelector
import com.android.adblib.adbLogger
import com.android.adblib.deviceProperties
import com.android.adblib.shellCommand
import com.android.adblib.utils.AdbProtocolUtils
import com.android.adblib.utils.ByteArrayShellCollector
import com.android.server.adb.protos.AmCapabilitiesProto
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.first

/**
 * Implementation of [AdbActivityManagerServices]
 */
class AdbActivityManagerServicesImpl(
    override val session: AdbSession
) : AdbActivityManagerServices {

    private val logger = adbLogger(session.host)

    private val deviceServices: AdbDeviceServices
        get() = session.deviceServices

    override suspend fun forceStop(device: DeviceSelector, packageName: String) {
        validatePackageName(packageName)
        runAmCommand(device, "am force-stop $packageName")
    }

    override suspend fun crash(device: DeviceSelector, packageName: String) {
        validatePackageName(packageName)
        runAmCommand(device, "am crash $packageName")
    }

    override suspend fun capabilities(device: DeviceSelector): AmCapabilitiesResult {
        // See Android platform implementation here:
        // https://cs.android.com/android/platform/superproject/main/+/1b409eb6cacc9508e6f415353ddcacdcb6bdaf26:frameworks/base/services/core/java/com/android/server/am/ActivityManagerShellCommand.java;l=480
        val amCommand = "am capabilities --protobuf"
        val result = runAmCommand(device, amCommand)
        val protoCapabilities = try {
            AmCapabilitiesProto.Capabilities.parseFrom(result.stdout)
        } catch(e: InvalidProtocolBufferException) {
            throw AdbActivityManagerException(
                device = device,
                command = amCommand,
                errorOutput = "Error processing protobuf: ${e.message ?: e.javaClass.simpleName}",
                cause = e
            )
        }
        return AmCapabilitiesResult(
            capabilities = protoCapabilities.valuesList.map { it.name },
            vmCapabilities = protoCapabilities.vmCapabilitiesList.map { it.name },
            frameworkCapabilities = protoCapabilities.frameworkCapabilitiesList.map { it.name },
            vmInfo = if (protoCapabilities.vmInfo == AmCapabilitiesProto.VMInfo.getDefaultInstance()) {
                null
            } else {
                AmCapabilitiesResult.VmInfo(
                    name = protoCapabilities.vmInfo.name, version = protoCapabilities.vmInfo.version
                )
            },
        )
    }

    /**
     * Invokes the [amCommand] and check for error in the output
     */
    private suspend fun runAmCommand(
        device: DeviceSelector,
        amCommand: String
    ): ByteArrayShellCollector.CommandResult {
        return deviceServices
            .shellCommand(device, amCommand)
            .withCollector(ByteArrayShellCollector())
            .execute()
            .first()
            .also { commandResult: ByteArrayShellCollector.CommandResult    ->
                checkOutputForError(device, amCommand, commandResult)
            }
    }

    /**
     * Throw an [AdbActivityManagerException] if the command [result] indicates an execution error.
     *
     * ## Note
     *
     * Error reporting from 'am' commands has changed over API levels:
     *
     * * In API 16-25, `exitcode` is always 0, `stdout` is empty and `stderr` contains `am` usage
     * info (multiple lines) followed by an error message line starting with `"Error:"`
     *
     * * In API 26-36+, `exitcode` is set to a non-zero value, `stdout` contains the error message
     * (without the `"Error:"` prefix), and `stderr` is always empty.
     *
     * * In addition to that, for API 16-23, `adblib` does not have access to `stderr` as a
     * separate stream since there is no support for [com.android.adblib.AdbDeviceServices.shellV2]
     *
     * In summary:
     * ```
     * |          | API 16-25                        | API 26-36+    |
     * |----------|----------------------------------|---------------|
     * | exitcode | 0                                | non zero      |
     * | stdout   | <empty>                          | error message |
     * | stderr   | usage followed by error message  | <empty>       |
     * |          | with "Error:" prefix             |               |
     * ```
     *
     * Example: When executing an unknown as command such as `am foobar`
     * ```
     * |          | API 16-25                         | API 26-36+                |
     * |----------|-----------------------------------|---------------------------|
     * | exitcode | 0                                 | 255                       |
     * | stdout   | <empty>                           | "Unknown command: foobar" |
     * | stderr   | "am usage line 1\n"               | <empty>                   |
     * |          | "..."                             |                           |
     * |          | "am usage line <x>\n"             |                           |
     * |          | "Error: unknown command 'foobar'" |                           |
     * ```
     */
    private suspend fun checkOutputForError(
        device: DeviceSelector,
        amCommand: String,
        result: ByteArrayShellCollector.CommandResult
    ) {
        val api = deviceServices.deviceProperties(device).api(Int.MAX_VALUE)
        when (api) {
            in 26..Int.MAX_VALUE -> {
                // API 26->36+: Look at exit code as signal of error
                if (result.exitCode != 0) {
                    throwAmCommandError(
                        device = device,
                        amCommand = amCommand,
                        result = result,
                        errorOutput = result.stdout.toString(AdbProtocolUtils.ADB_CHARSET)
                    )
                }
            }

            in 24..25 -> {
                // API 24-25: Look at contents of `stderr` as signal for error
                if (result.stderr.isNotEmpty()) {
                    throwAmCommandError(
                        device = device,
                        amCommand = amCommand,
                        result = result,
                        errorOutput = result.stderr
                    )
                }
            }

            else -> {
                // API <= 23: Since we have neither "exit code" nor `stderr`, we look at last
                // lines of `stdout` to see if we have line with the "Error:" prefix
                val errorMessage = result.stdout
                    .toString(AdbProtocolUtils.ADB_CHARSET)
                    .split(AdbProtocolUtils.ADB_NEW_LINE)
                    .lastOrNull { line -> line.startsWith("Error:", ignoreCase = true) }
                if (errorMessage != null) {
                    throwAmCommandError(
                        device = device,
                        amCommand = amCommand,
                        result = result,
                        errorOutput = errorMessage
                    )
                }
            }
        }
    }

    private fun throwAmCommandError(
        device: DeviceSelector,
        amCommand: String,
        result: ByteArrayShellCollector.CommandResult,
        errorOutput: String,
    ): Nothing {
        logger.debug { "$amCommand failed with exit code ${result.exitCode}" }
        logger.verbose { "stderr: ${result.stderr}" }
        logger.verbose { "stdout: ${result.stdout.toString(AdbProtocolUtils.ADB_CHARSET)}" }

        // Take only last 10 lines to avoid too much noise
        val errorMessage = errorOutput
            .split(AdbProtocolUtils.ADB_NEW_LINE)
            .takeLast(10)
            .joinToString(AdbProtocolUtils.ADB_NEW_LINE)

        throw AdbActivityManagerException(
            device = device,
            command = amCommand,
            errorOutput = errorMessage
        )
    }

    /**
     * Ensures a package name contains only valid characters.
     */
    private fun validatePackageName(packageName: String) {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            throw IllegalArgumentException("packageName `$packageName` contains illegal characters")
        }
    }
}

private val PACKAGE_NAME_REGEX: Regex = Regex("[a-zA-Z0-9._]+")
