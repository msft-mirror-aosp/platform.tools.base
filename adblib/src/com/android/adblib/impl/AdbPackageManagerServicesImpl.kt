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
package com.android.adblib.impl

import com.android.adblib.AdbDeviceServices
import com.android.adblib.AdbPackageManagerException
import com.android.adblib.AdbPackageManagerServices
import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.adbLogger
import com.android.adblib.deviceProperties
import com.android.adblib.shellCommand
import com.android.adblib.utils.AdbProtocolUtils
import com.android.adblib.utils.ByteArrayShellCollector
import kotlinx.coroutines.flow.first

/** Implementation of [AdbPackageManagerServices] */
class AdbPackageManagerServicesImpl(override val session: AdbSession) : AdbPackageManagerServices {

  private val logger = adbLogger(session.host)

  private val deviceServices: AdbDeviceServices
    get() = session.deviceServices

  override suspend fun uninstall(device: DeviceSelector, packageName: String) {
    validatePackageName(packageName)
    val pmCommand = "pm uninstall $packageName"
    val result = runPmCommand(device, pmCommand)
    val output = result.stdout.toString(AdbProtocolUtils.ADB_CHARSET)
    // On newer devices, `runPmCommand` above throws an exception based on the adb shell error code. But on older devices, we must infer
    // failure from stdout since both the error code and stderr are missing.
    if (output.trim() != "Success") {
      throwPmCommandError(device = device, pmCommand = pmCommand, result = result, errorOutput = output)
    }
  }

  override suspend fun clear(device: DeviceSelector, packageName: String) {
    validatePackageName(packageName)
    val pmCommand = "pm clear $packageName"
    val result = runPmCommand(device, pmCommand)
    val output = result.stdout.toString(AdbProtocolUtils.ADB_CHARSET)
    // On newer devices, `runPmCommand` above throws an exception based on the adb shell error code. But on older devices, we must infer
    // failure from stdout since both the error code and stderr are missing.
    if (output.trim() != "Success") {
      throwPmCommandError(device = device, pmCommand = pmCommand, result = result, errorOutput = output)
    }
  }

  private suspend fun runPmCommand(device: DeviceSelector, pmCommand: String): ByteArrayShellCollector.CommandResult {
    return deviceServices.shellCommand(device, pmCommand).withCollector(ByteArrayShellCollector()).execute().first().also {
      commandResult: ByteArrayShellCollector.CommandResult ->
      checkOutputForError(device, pmCommand, commandResult)
    }
  }

  private suspend fun checkOutputForError(device: DeviceSelector, pmCommand: String, result: ByteArrayShellCollector.CommandResult) {
    val api = deviceServices.deviceProperties(device).api(Int.MAX_VALUE)
    when (api) {
      in 28..Int.MAX_VALUE -> {
        // API 28+: Look at exit code as signal of error, error is in stdout
        if (result.exitCode != 0) {
          throwPmCommandError(
            device = device,
            pmCommand = pmCommand,
            result = result,
            errorOutput = result.stdout.toString(AdbProtocolUtils.ADB_CHARSET),
          )
        }
      }

      in 24..27 -> {
        // API 24-27: Exit code is less reliable for these API levels. Certain failures like a failure when uninstalling an unknown package
        // return a `0` exit code, but may still have the failure in `stderr`
        if (result.exitCode != 0) {
          throwPmCommandError(
            device = device,
            pmCommand = pmCommand,
            result = result,
            errorOutput = "${result.stdout.toString(AdbProtocolUtils.ADB_CHARSET)} ${result.stderr}",
          )
        }
        if (result.stderr.isNotEmpty()) {
          throwPmCommandError(device = device, pmCommand = pmCommand, result = result, errorOutput = result.stderr)
        }
      }

      else -> {
        // API <= 23: Since we have neither "exit code" nor `stderr`, we look at
        // `stdout` to see if we have a line with the "Error:" prefix.
        // Note: pm errors are usually at the top of the output.
        val stdout = result.stdout.toString(AdbProtocolUtils.ADB_CHARSET)
        val hasError = stdout.split(AdbProtocolUtils.ADB_NEW_LINE).any { line -> line.startsWith("Error:", ignoreCase = true) }
        if (hasError) {
          throwPmCommandError(device = device, pmCommand = pmCommand, result = result, errorOutput = stdout)
        }
      }
    }
  }

  private fun throwPmCommandError(
    device: DeviceSelector,
    pmCommand: String,
    result: ByteArrayShellCollector.CommandResult,
    errorOutput: String,
  ): Nothing {
    logger.debug { "$pmCommand failed with exit code ${result.exitCode}" }
    logger.verbose { "stderr: ${result.stderr}" }
    logger.verbose { "stdout: ${result.stdout.toString(AdbProtocolUtils.ADB_CHARSET)}" }

    // Take only lines before the "usage:" text to avoid too much noise,
    // as pm errors are at the top and followed by a long usage text.
    val lines = errorOutput.split(AdbProtocolUtils.ADB_NEW_LINE)
    val errorMessage =
      lines
        .take(10)
        .takeWhile { !it.trim().startsWith("usage:", ignoreCase = true) }
        .ifEmpty { listOf(lines.firstOrNull() ?: "") }
        .joinToString(AdbProtocolUtils.ADB_NEW_LINE)
        .trim()

    throw AdbPackageManagerException(device = device, command = pmCommand, errorOutput = errorMessage)
  }

  /** Ensures a package name contains only valid characters. */
  private fun validatePackageName(packageName: String) {
    if (!packageName.matches(PACKAGE_NAME_REGEX)) {
      throw IllegalArgumentException("packageName `$packageName` contains illegal characters")
    }
  }
}

private val PACKAGE_NAME_REGEX: Regex = Regex("[a-zA-Z0-9._]+")
