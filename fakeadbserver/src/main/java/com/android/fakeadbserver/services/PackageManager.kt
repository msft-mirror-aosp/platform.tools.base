/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.fakeadbserver.services

import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.shellcommandhandlers.ShellConstants

// TODO: Add all package management app (create,write,commit,abandon) and list here.
class PackageManager(private val deviceState: DeviceState) : Service {

  private val errorReporting = ErrorReporting(deviceState)

  companion object {

    const val BAD_FLAG = "-BAD_FLAG"

    const val FAIL_ME_SESSION = "FAIL_ME_SESSION"

    const val FAIL_ME_SESSION_TEST_ONLY = "FAIL_ME_TEST_ONLY"

    const val SESSION_TEST_ONLY_CODE = "INSTALL_FAILED_TEST_ONLY"
    const val SESSION_TEST_ONLY_MSG = "installPackageLI"

    val BAD_SESSIONS: Map<String, String> =
      mapOf(
        FAIL_ME_SESSION to "Failure [REQUESTED_FAILURE_VIA_SESSION]",
        FAIL_ME_SESSION_TEST_ONLY to "Failure [$SESSION_TEST_ONLY_CODE: $SESSION_TEST_ONLY_MSG]",
      )

    const val SERVICE_NAME = "package"
  }

  private var sessionIDCounter = 1234L

  fun getNextSessionID() = sessionIDCounter++

  // Map of sessionID to Session
  val sessions: MutableMap<String, PackageManagerSession> = mutableMapOf()

  override fun process(args: List<String>, shellCommandOutput: ShellCommandOutput) {
    processLocked(args, shellCommandOutput)
  }

  @Synchronized
  fun processLocked(args: List<String>, shellCommandOutput: ShellCommandOutput) {
    val cmd = args[0]

    when {
      cmd == "list users" -> {
        shellCommandOutput.writeStdout("Users:\n\tUserInfo{0:Owner:13} running\n")
        shellCommandOutput.writeExitCode(0)
      }
      cmd.startsWith("uninstall") -> {
        if (args.size == 1) {
          errorReporting.reportError(shellCommandOutput, "package name not specified")
          return
        }
        val applicationId = args.last()
        if (applicationId == ShellConstants.NON_INSTALLED_APP_ID) {
          // Note: General error reporting by PackageManager is covered by `errorReporting.reportError` helper method,
          // but for APIs 24-27, the behavior is different that when uninstalling a non-existent package shell command returns a `0` exit
          // code.
          when (deviceState.apiLevel) {
            24 -> {
              shellCommandOutput.writeStdout("Failure [DELETE_FAILED_INTERNAL_ERROR]\n")
              shellCommandOutput.writeExitCode(0)
            }
            in 25..<28 -> {
              shellCommandOutput.writeStderr(
                "Exception occurred while executing:\njava.lang.IllegalArgumentException: Unknown package: $applicationId\n"
              )
              shellCommandOutput.writeExitCode(0)
            }
            else -> {
              errorReporting.reportError(shellCommandOutput, "Failure [DELETE_FAILED_INTERNAL_ERROR]")
            }
          }
        } else {
          shellCommandOutput.writeStdout("Success")
          shellCommandOutput.writeExitCode(0)
        }
      }
      cmd == "path" -> {
        val appId = args[1]
        shellCommandOutput.writeStdout("/data/app/$appId/base.apk")
        shellCommandOutput.writeExitCode(0)
      }

      cmd.startsWith("install-create") -> {
        if (args.contains(BAD_FLAG)) {
          errorReporting.reportError(shellCommandOutput, "requested to fail via flag")
          return
        }

        val sessionID = getNextSessionID().toString()
        sessions[sessionID] = PackageManagerSession(sessionID)
        shellCommandOutput.writeStdout("Success: created install session [$sessionID]")
        shellCommandOutput.writeExitCode(0)
      }

      cmd.startsWith("install-write") -> {
        installWrite(args.joinToString(" "), shellCommandOutput)
      }

      cmd.startsWith("install-commit") -> {
        val sessionID = args[1]
        if (BAD_SESSIONS.containsKey(sessionID)) {
          BAD_SESSIONS[sessionID]?.let { errorReporting.reportError(shellCommandOutput, it) }
        } else {
          commit(args.drop(1), shellCommandOutput)
        }
      }
      cmd.startsWith("install-abandon") -> {
        val sessionID = args[1]
        sessions.remove(sessionID)

        if (!sessions.containsKey(sessionID)) {
          failUnknownSession(shellCommandOutput, sessionID)
          return
        }

        shellCommandOutput.writeStdout("Success\n")
        shellCommandOutput.writeExitCode(0)
      }
      cmd.startsWith("install") -> {
        install(args.drop(1), shellCommandOutput)
      }
      cmd.startsWith("-l") -> {
        listOf("package:one", "package:two", "package:three").forEach { shellCommandOutput.writeStdout("$it\n") }
        shellCommandOutput.writeExitCode(0)
      }

      else -> {
        errorReporting.reportUnknownCommand(shellCommandOutput, cmd)
      }
    }
  }

  private fun failUnknownSession(shellCommandOutput: ShellCommandOutput, sessionID: String) {
    errorReporting.reportError(shellCommandOutput, "java.lang.SecurityException: Caller has no access to session $sessionID")
  }

  private fun install(slice: List<String>, shellCommandOutput: ShellCommandOutput) {
    shellCommandOutput.writeStdout("Success\n")
    shellCommandOutput.writeExitCode(0)
  }

  private fun commit(slice: List<String>, shellCommandOutput: ShellCommandOutput) {
    val sessionID = slice[0]

    if (!sessions.containsKey(sessionID)) {
      failUnknownSession(shellCommandOutput, sessionID)
      return
    }

    val session = sessions[sessionID]!!
    sessions.remove(sessionID)

    // Check if we have had duplicate filename. They should all be unique, otherwise pm will have
    // trouble verifying certificates
    if (session.splits.groupingBy { it }.eachCount().filter { it.value > 1 }.isNotEmpty()) {
      errorReporting.reportError(shellCommandOutput, "The application could not be installed: INSTALL_PARSE_FAILED_NO_CERTIFICATES")
      return
    }

    if (sessionID == "FAIL_ME") {
      errorReporting.reportError(shellCommandOutput, "requested a FAIL_ME session")
    } else {
      shellCommandOutput.writeStdout("Success\n")
      shellCommandOutput.writeExitCode(0)
    }
  }

  private fun installWrite(args: String, shellCommandOutput: ShellCommandOutput) {
    val parameters = args.split(" ")
    if (parameters.isEmpty()) {
      errorReporting.reportError(shellCommandOutput, "Not install-write parameters after split($args,' ')")
      return
    }

    val sessionID: String
    if (parameters.last() != "-") {
      sessionID = parameters[1]
      if (BAD_SESSIONS.containsKey(sessionID)) {
        BAD_SESSIONS.get(sessionID)?.let { errorReporting.reportError(shellCommandOutput, it) }
        return
      }
      // This is a remote apk write (the apk is somewhere on the device, likely /data/local"..)
      // Use a random value
      shellCommandOutput.writeStdout("Success: streamed 123456789 bytes\n")
      shellCommandOutput.writeExitCode(0)
      return
    }

    // This is a streamed install
    val sizeIndex = parameters.indexOf("-S") + 1
    sessionID = parameters[sizeIndex + 1]
    if (!sessions.containsKey(sessionID)) {
      failUnknownSession(shellCommandOutput, sessionID)
      return
    }

    val splitName = parameters[sizeIndex + 2]
    val expectedBytesLength = parameters[sizeIndex].toInt()
    val buffer = ByteArray(1024)
    var totalBytesRead = 0
    while (totalBytesRead < expectedBytesLength) {
      val length = Integer.min(buffer.size, expectedBytesLength - totalBytesRead)
      val numRead = shellCommandOutput.readStdin(buffer, 0, length)
      if (numRead < 0) {
        break
      }
      totalBytesRead += numRead
    }

    sessions[sessionID]!!.addSplit(splitName)

    shellCommandOutput.writeStdout("Success: streamed $totalBytesRead bytes\n")
    shellCommandOutput.writeExitCode(0)
  }

  private class ErrorReporting(private val deviceState: DeviceState) {

    fun reportError(shellCommandOutput: ShellCommandOutput, message: String) {
      // We add "Error: " if the `message` doesn't already start with "Failure " or "Error: "
      val errorPrefix =
        if (message.startsWith("Error:", ignoreCase = true) || message.startsWith("Failure", ignoreCase = true)) "" else "Error: "
      when (deviceState.apiLevel) {
        in 1..23 -> {
          // API <= 23: Error message in STDOUT, usage info, exit code 0
          shellCommandOutput.writeStdout("$errorPrefix$message\n")
          printUsage(shellCommandOutput, useStdout = true)
          shellCommandOutput.writeExitCode(0)
        }
        in 24..27 -> {
          // API 24-27: Error message in STDERR, usage info, exit code 1
          shellCommandOutput.writeStderr("$errorPrefix$message\n")
          printUsage(shellCommandOutput, useStdout = false)
          shellCommandOutput.writeExitCode(1)
        }
        else -> {
          // API >= 28: Error message in STDOUT, no usage info, exit code 1 (typically)
          shellCommandOutput.writeStdout("$errorPrefix$message\n")
          shellCommandOutput.writeExitCode(1)
        }
      }
    }

    fun reportUnknownCommand(shellCommandOutput: ShellCommandOutput, command: String) {
      when (deviceState.apiLevel) {
        in 1..27 -> {
          reportError(shellCommandOutput, "unknown command '$command'")
        }
        else -> {
          // API >= 28: "Unknown command: <cmd>" in STDOUT, no usage info, exit code 255
          shellCommandOutput.writeStdout("Unknown command: $command\n")
          shellCommandOutput.writeExitCode(255)
        }
      }
    }

    private fun printUsage(shellCommandOutput: ShellCommandOutput, useStdout: Boolean) {
      val usage =
        """
        usage: pm list packages [-f] [-d] [-e] [-s] [-3] [-i] [-u] [--user USER_ID] [FILTER]
               pm list permission-groups
               pm list permissions [-g] [-f] [-d] [-u] [GROUP]
               pm list instrumentation [-f] [TARGET-PACKAGE]
               pm list features
               pm list libraries
               pm list users
               pm path PACKAGE
               pm dump PACKAGE
               pm install [-lrtsfd] [-i PACKAGE] [PATH]
               pm install-create [-lrtsfdp] [-i PACKAGE] [-S BYTES]
               pm install-write [-S BYTES] SESSION_ID SPLIT_NAME [PATH]
               pm install-commit SESSION_ID
               pm install-abandon SESSION_ID
               pm uninstall [-k] [--user USER_ID] PACKAGE
               <ETC>
        """
          .trimIndent()
      if (useStdout) {
        shellCommandOutput.writeStdout(usage)
      } else {
        shellCommandOutput.writeStderr(usage)
      }
    }
  }
}
