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

import com.android.utils.GrabProcessOutput
import java.io.File
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
    val outputLines = mutableListOf<String>()
    val errorLines = mutableListOf<String>()

    val handler =
      object : GrabProcessOutput.IProcessOutput {
        override fun out(line: String?) {
          line?.let { outputLines.add(it) }
        }

        override fun err(line: String?) {
          line?.let { errorLines.add(it) }
        }
      }

    GrabProcessOutput.grabProcessOutput(
      process,
      GrabProcessOutput.Wait.WAIT_FOR_READERS,
      handler,
      timeout?.toMillis(),
      TimeUnit.MILLISECONDS,
    )

    return CommandResult(process.exitValue(), outputLines.joinToString("\n"), errorLines.joinToString("\n"))
  }

  /** Constructs and runs an adb command targeting the specified device. */
  fun runAdbCommand(deviceSerial: String, args: List<String>, timeout: Duration? = null): CommandResult {
    val command = listOf(adb.absolutePath, "-s", deviceSerial) + args
    return runCommand(command, timeout)
  }

  /** Constructs and runs an `adb shell` command targeting the specified device. */
  fun runAdbShellCommand(deviceSerial: String, args: List<String>, timeout: Duration? = null): CommandResult {
    return runAdbCommand(deviceSerial, listOf("shell") + args, timeout)
  }

  /** Encapsulates the result of a command execution. */
  data class CommandResult(val exitCode: Int, val output: String, val errorOutput: String)
}
