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

package com.android.tools.androidtest.testengine

import java.io.File
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A controller for executing Android Debug Bridge (adb) commands.
 *
 * @property adb The [File] pointing to the ADB executable.
 * @property processBuilder A factory for creating [ProcessBuilder] instances.
 */
class AdbController(private val adb: File, private val processBuilder: (command: List<String>) -> ProcessBuilder = { ProcessBuilder(it) }) {

  /** Executes an external command and captures its output. */
  fun runCommand(command: List<String>, timeout: Duration? = null): CommandResult {
    val process = processBuilder(command).start()

    var stdout = ""
    var stderr = ""
    val stdoutThread = Thread({ stdout = process.inputStream.bufferedReader().use { it.readText() } }, "adb-stdout-reader")
    val stderrThread = Thread({ stderr = process.errorStream.bufferedReader().use { it.readText() } }, "adb-stderr-reader")

    stdoutThread.start()
    stderrThread.start()

    val finished =
      if (timeout == null) {
        process.waitFor()
        true
      } else {
        process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
      }

    if (!finished) {
      process.destroyForcibly()
      process.waitFor()
    }

    // Wait for the threads to finish reading the remaining output.
    // If the process is dead, this should be very fast.
    stdoutThread.join(1000)
    stderrThread.join(1000)

    return CommandResult(process.exitValue(), stdout, stderr)
  }

  /** Executes an adb command for a specific device and captures its output. */
  fun runAdbCommand(deviceSerial: String, args: List<String>, timeout: Duration? = null): CommandResult {
    val command = listOf(adb.absolutePath, "-s", deviceSerial) + args
    return runCommand(command, timeout)
  }

  /** Executes an `adb shell` command and captures its output. */
  fun runAdbShellCommand(deviceSerial: String, args: List<String>, timeout: Duration? = null): CommandResult {
    val command = listOf("shell") + args
    return runAdbCommand(deviceSerial, command, timeout)
  }

  /** Constructs and runs an `adb shell` command and pipes its stdout to the given [outputStream]. */
  fun runAdbShellCommandToOutputStream(
    deviceSerial: String,
    args: List<String>,
    outputStream: OutputStream,
    timeout: Duration? = null,
  ): Int {
    val command = listOf(adb.absolutePath, "-s", deviceSerial, "shell") + args
    val process = processBuilder(command).start()

    val errorLines = java.util.Collections.synchronizedList(mutableListOf<String>())
    val stderrThread =
      Thread({ process.errorStream.bufferedReader().use { it.lines().forEach { line -> errorLines.add(line) } } }, "adb-stderr-reader")
    val stdoutThread = Thread({ process.inputStream.use { it.transferTo(outputStream) } }, "adb-stdout-reader")

    stderrThread.start()
    stdoutThread.start()

    val finished =
      if (timeout == null) {
        process.waitFor()
        true
      } else {
        process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
      }

    if (!finished) {
      process.destroyForcibly()
      process.waitFor()
    }

    // Wait for the threads to finish reading the remaining output.
    // If the process is dead, this should be very fast.
    stderrThread.join(1000)
    stdoutThread.join(1000)

    if (process.exitValue() != 0 || errorLines.isNotEmpty()) {
      val errors = synchronized(errorLines) { errorLines.joinToString("\n") }
      java.util.logging.Logger.getLogger(AdbController::class.java.name)
        .warning("adb shell ${args.joinToString(" ")} failed with exit code ${process.exitValue()}. Error: $errors")
    }

    return process.exitValue()
  }

  /** Pulls a file or directory from the device to the host. */
  fun pull(deviceSerial: String, devicePath: String, hostPath: String, timeout: Duration? = null): CommandResult {
    return runAdbCommand(deviceSerial, listOf("pull", devicePath, hostPath), timeout)
  }

  /** Pushes a file or directory from the host to the device. */
  fun push(deviceSerial: String, hostPath: String, devicePath: String, timeout: Duration? = null): CommandResult {
    return runAdbCommand(deviceSerial, listOf("push", hostPath, devicePath), timeout)
  }

  /** Encapsulates the result of a command execution. */
  data class CommandResult(val exitCode: Int, val output: String, val errorOutput: String)
}
