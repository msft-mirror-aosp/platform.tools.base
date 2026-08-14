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

import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.ShellCommandOutput
import com.android.adblib.shellAsText
import com.android.tools.ui.inspector.common.ProtocolConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Splits a /proc/net/unix line into its whitespace-separated fields. */
private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * Checks for the agent's server socket on the device. The socket is how an attached agent announces itself: [isPresent] answers whether a
 * server is already running (which is how a run decides it can reconnect instead of injecting), and [waitUntilPresent] waits for the socket
 * to appear after an attach, reporting agent errors found in logcat when it does not.
 */
internal class AgentSocketChecker(private val adbSession: AdbSession, private val deviceSelector: DeviceSelector) {

  /**
   * Returns whether an abstract socket named exactly [socketName] is currently bound on the device, by matching the path field of
   * /proc/net/unix entries (abstract sockets are listed with a leading `@`). Exact matching prevents a socket whose name merely starts with
   * [socketName] from counting as present.
   */
  suspend fun isPresent(socketName: String): Boolean {
    // The whole file is read and matched host-side: a device-side filter would fold a failing read and a missing socket into the same
    // empty output, and a read failure is not authoritative absence — runShellCommand throws on it instead.
    val output = runShellCommand("cat /proc/net/unix").stdout
    return output.lineSequence().any { line ->
      val path = line.trim().split(WHITESPACE_REGEX).lastOrNull()
      path == socketName || path == "@$socketName"
    }
  }

  /**
   * Waits for the abstract socket to appear in /proc/net/unix. Since the agent acts as the server, we must wait for it to create the socket
   * before the host can connect to it. [attachStartTime] is the device time captured just before `attach-agent` ran; the logcat search for
   * agent errors is limited to lines after it, or unbounded when null.
   */
  suspend fun waitUntilPresent(socketName: String, pid: String, attachStartTime: String?) {
    val maxAttempts = 10
    var attempts = 0
    var delayMs = 100L
    while (attempts < maxAttempts) {
      if (isPresent(socketName)) {
        return
      }

      if (attempts >= 2) {
        // If the agent already logged a bootstrap error, throw it immediately, to avoid exponential backoff.
        val agentError = readAgentErrorFromLogcat(pid, attachStartTime)
        if (agentError != null) {
          throw IllegalStateException("Failed to attach UI Inspector agent. Agent error in logcat:\n$agentError")
        }
      }

      attempts++
      delay(delayMs)
      delayMs = (delayMs * 2).coerceAtMost(1000L)
    }
    throw IllegalStateException("Timed out waiting for agent socket $socketName")
  }

  /**
   * Queries logcat for error logs produced by the UI Inspector agent. Filters logs by the target app's PID and the agent's known logging
   * tags.
   */
  private suspend fun readAgentErrorFromLogcat(pid: String, attachStartTime: String?): String? {
    try {
      // Query error logs for this process ID, optionally filtering since the start of this injection attempt
      val timeFilter = if (attachStartTime != null) " -t '$attachStartTime'" else ""
      val cmd = "logcat -d$timeFilter --pid=$pid *:E"
      val output = adbSession.deviceServices.shellAsText(deviceSelector, cmd).stdout.trim()
      if (output.isEmpty()) return null

      val uiInspectorLogs = output.lines().filter { line -> line.contains(ProtocolConstants.LOG_TAG_PREFIX) }

      return if (uiInspectorLogs.isNotEmpty()) uiInspectorLogs.joinToString("\n") else null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return null
    }
  }

  /** Runs a shell command and throws an exception if it fails (exit code != 0). */
  private suspend fun runShellCommand(command: String): ShellCommandOutput {
    val result = adbSession.deviceServices.shellAsText(deviceSelector, command)
    if (result.exitCode != 0) {
      throw IllegalStateException("Command '$command' failed with exit code ${result.exitCode}. Stderr: ${result.stderr}")
    }
    return result
  }
}
