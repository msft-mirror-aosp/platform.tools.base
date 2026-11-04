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

package com.android.tools.ui.inspector.device

import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.shellAsText
import com.android.tools.ui.inspector.common.ProtocolConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Splits a /proc/net/unix or logcat line into its whitespace-separated fields. */
private val WHITESPACE_REGEX = Regex("\\s+")

/** Log tag prefix used by the ART Tooling native agent and bootstrap loader. */
private const val AGENT_LOG_TAG_PREFIX = "studio.arttooling"

/** The logcat priorities of the agent's logs that count as errors: error and fatal. */
private val ERROR_PRIORITIES = setOf("E", "F")

/**
 * How ART logs an agent it fails to attach, e.g. a library truncated to 0 bytes: "Agent attach failed (result=1) : Unable to dlopen <path>:
 * dlopen failed: file offset for the library "<path>" >= file size: 0 >= 0". ART logs it as a warning, tagged with a truncated process
 * name, not with the agent's tags.
 */
private const val ART_AGENT_ATTACH_FAILED = "Agent attach failed"

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
    val output = adbSession.deviceServices.shellAsTextOrThrow(deviceSelector, "cat /proc/net/unix").stdout
    return output.lineSequence().any { line ->
      val path = line.trim().split(WHITESPACE_REGEX).lastOrNull()
      path == socketName || path == "@$socketName"
    }
  }

  /**
   * Waits for the abstract socket to appear in /proc/net/unix. Since the agent acts as the server, we must wait for it to create the socket
   * before the host can connect to it. [attachStartTime] is the device time captured just before `attach-agent` ran; the logcat search for
   * agent errors is limited to lines after it, or, when null, unbounded and without ART's attach failures. [agentName] is the file name of
   * the attached agent, which tells its attach failures apart from those of other agents attached to the same process.
   */
  suspend fun waitUntilPresent(socketName: String, pid: String, attachStartTime: String?, agentName: String) {
    val maxAttempts = 10
    var attempts = 0
    var delayMs = 100L
    while (attempts < maxAttempts) {
      if (isPresent(socketName)) {
        return
      }

      if (attempts >= 2) {
        // If the agent already logged a bootstrap error, throw it immediately, to avoid exponential backoff.
        val agentError = readAgentErrorFromLogcat(pid, attachStartTime, agentName)
        if (agentError != null) {
          throw IllegalStateException("Failed to attach UI Inspector agent. Agent error in logcat:\n$agentError")
        }
      }

      attempts++
      delay(delayMs)
      delayMs = (delayMs * 2).coerceAtMost(1000L)
    }
    throw IllegalStateException(
      "Timed out waiting for the UI Inspector agent to start (socket $socketName). The agent starts on the app's main thread, so it" +
        " cannot start while that thread is blocked or the app is paused in a debugger. Otherwise, check logcat for errors."
    )
  }

  /**
   * Queries logcat for the errors of the UI Inspector agent: the errors of the target app's PID with the agent's logging tags, and ART's
   * report that it failed to attach [agentName], which ART logs as a warning.
   *
   * ART's reports are only read when the search starts at [attachStartTime]. Without it, the search also finds the reports of earlier
   * attaches to the same process, and the agent keeps its name across runs: the report of a run that found the agent truncated would fail
   * every later attach to that process, even after a run repaired the agent.
   */
  private suspend fun readAgentErrorFromLogcat(pid: String, attachStartTime: String?, agentName: String): String? {
    try {
      // Query warning and error logs for this process ID, optionally filtering since the start of this injection attempt
      val timeFilter = if (attachStartTime != null) " -t '$attachStartTime'" else ""
      val cmd = "logcat -d -v threadtime$timeFilter --pid=$pid *:W"
      val output = adbSession.deviceServices.shellAsText(deviceSelector, cmd).stdout.trim()
      if (output.isEmpty()) return null

      val relevantAgentLogs =
        output.lines().filter { line -> isAgentError(line) || (attachStartTime != null && isAttachFailure(line, agentName)) }

      return if (relevantAgentLogs.isNotEmpty()) relevantAgentLogs.joinToString("\n") else null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return null
    }
  }

  /** Whether [line], a logcat line in the `threadtime` format (date, time, PID, TID, priority, tag: message), is an agent's error. */
  private fun isAgentError(line: String): Boolean {
    val priority = line.trim().split(WHITESPACE_REGEX).getOrNull(4)
    return priority in ERROR_PRIORITIES && (line.contains(ProtocolConstants.LOG_TAG_PREFIX) || line.contains(AGENT_LOG_TAG_PREFIX))
  }

  /** Whether [line] is ART's report that it failed to attach [agentName]. ART names the agent by its path in all of these reports. */
  private fun isAttachFailure(line: String, agentName: String): Boolean =
    line.contains(ART_AGENT_ATTACH_FAILED) && line.contains("/$agentName")
}
