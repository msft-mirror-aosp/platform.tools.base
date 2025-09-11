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
package com.android.fakeadbserver.services

import com.android.fakeadbserver.DeviceState
import com.android.server.adb.protos.AmCapabilitiesProto

class ActivityManager(private val deviceState: DeviceState) : Service {

    private val errorReporting = ErrorReporting(deviceState)

    companion object {
        const val SERVICE_NAME = "activity"
    }

    override fun process(args: List<String>, shellCommandOutput: ShellCommandOutput) {
        return when (val cmd = args[0]) {
            "force-stop" -> {
                if (args.size <= 1) {
                    return errorReporting.reportMissingArgument(
                        shellCommandOutput = shellCommandOutput,
                        message = "Argument expected after \"force-stop\"\n"
                    )
                }
                val packageName = args[1]
                deviceState.stopClients(packageName)
                shellCommandOutput.writeExitCode(0)
            }

            "crash" -> {
                if (args.size <= 1) {
                    return errorReporting.reportMissingArgument(
                        shellCommandOutput = shellCommandOutput,
                        message = "Argument expected after \"crash\"\n"
                    )
                }
                val packageName = args[1]
                deviceState.stopClients(packageName)
                shellCommandOutput.writeExitCode(0)
            }

            "capabilities" -> {
                // See Android platform implementation here:
                // https://cs.android.com/android/platform/superproject/main/+/1b409eb6cacc9508e6f415353ddcacdcb6bdaf26:frameworks/base/services/core/java/com/android/server/am/ActivityManagerShellCommand.java;l=480
                val deviceCapabilities = deviceState.deviceCapabilities ?: run {
                    // Report `capabilities` is not supported (if api <= 33)
                    return errorReporting.reportUnknownCommand(shellCommandOutput, cmd)
                }
                val protobufOutput = (args.size > 1) && (args[1] == "--protobuf")
                if (protobufOutput) {
                    val proto =
                        AmCapabilitiesProto.Capabilities.newBuilder().also { capabilitiesProto ->
                            capabilitiesProto.addAllValues(
                                deviceCapabilities.capabilities.map {
                                    AmCapabilitiesProto.Capability.newBuilder().setName(it).build()
                                })
                            capabilitiesProto.addAllVmCapabilities(
                                deviceCapabilities.vmCapabilities.map {
                                    AmCapabilitiesProto.VMCapability.newBuilder()
                                        .setName(it)
                                        .build()
                                })
                            capabilitiesProto.addAllFrameworkCapabilities(
                                deviceCapabilities.frameworkCapabilities.map {
                                    AmCapabilitiesProto.FrameworkCapability.newBuilder()
                                        .setName(it)
                                        .build()
                                })
                            capabilitiesProto.setVmInfo(
                                AmCapabilitiesProto.VMInfo.newBuilder().also { vmInfoProto ->
                                    deviceCapabilities.vmInfo?.name?.also {
                                        vmInfoProto.setName(it)
                                    }
                                    deviceCapabilities.vmInfo?.version?.also {
                                        vmInfoProto.setVersion(it)
                                    }
                                }
                            )
                        }.build().toByteArray()
                    shellCommandOutput.writeStdout(proto)
                    shellCommandOutput.writeExitCode(0)
                } else {
                    shellCommandOutput.writeStdout("Format: 2")
                    deviceCapabilities.capabilities.forEach {
                        shellCommandOutput.writeStdout(it)
                    }
                    deviceCapabilities.vmCapabilities.forEach {
                        shellCommandOutput.writeStdout("vm:$it")
                    }
                    deviceCapabilities.frameworkCapabilities.forEach {
                        shellCommandOutput.writeStdout("framework:$it")
                    }
                    deviceCapabilities.vmInfo?.also {
                        shellCommandOutput.writeStdout("vm_name:${it.name}")
                        shellCommandOutput.writeStdout("vm_version:${it.version}")
                    }
                    shellCommandOutput.writeExitCode(0)
                }
            }

            else -> {
                errorReporting.reportUnknownCommand(shellCommandOutput, cmd)
            }
        }
    }

    /**
     * Error reporting support for `am` service
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
    private class ErrorReporting(private val deviceState: DeviceState) {

        fun reportMissingArgument(shellCommandOutput: ShellCommandOutput, message: String) {
            when (deviceState.apiLevel) {
                in 1..25 -> {
                    // API <= 25: Usage info, stderr message and exit code=0
                    printUsage(shellCommandOutput)
                    shellCommandOutput.writeStderr("Error: $message")
                    shellCommandOutput.writeExitCode(0)
                }
                else -> {
                    // API >= 26: **No** usage info, **stdout** message and exit code=255
                    shellCommandOutput.writeStdout("""
Exception occurred while executing:
java.lang.IllegalArgumentException: $message
	at android.os.ShellCommand.getNextArgRequired(ShellCommand.java:321)
	at com.android.server.am.ActivityManagerShellCommand.runForceStop(ActivityManagerShellCommand.java:967)
	at com.android.server.am.ActivityManagerShellCommand.onCommand(ActivityManagerShellCommand.java:197)
	at android.os.ShellCommand.exec(ShellCommand.java:103)
	at com.android.server.am.ActivityManagerService.onShellCommand(ActivityManagerService.java:16012)
	at android.os.Binder.shellCommand(Binder.java:634)
	at android.os.Binder.onTransact(Binder.java:532)
	at android.app.IActivityManager${'$'}Stub.onTransact(IActivityManager.java:3592)
	at com.android.server.am.ActivityManagerService.onTransact(ActivityManagerService.java:3291)
	at android.os.Binder.execTransact(Binder.java:731)
                    """.trimIndent())
                    shellCommandOutput.writeExitCode(255)
                }
            }
        }

        fun reportUnknownCommand(shellCommandOutput: ShellCommandOutput, command: String) {
            when (deviceState.apiLevel) {
                in 1..25 -> {
                    // API <= 25: Usage info, stderr message and exit code=0
                    printUsage(shellCommandOutput)
                    shellCommandOutput.writeStderr("Error: unknown command '$command'")
                    shellCommandOutput.writeExitCode(0)
                }
                else -> {
                    // API >= 26: **No** usage info, **stdout** message and exit code=255
                    shellCommandOutput.writeStdout("Unknown command: $command")
                    shellCommandOutput.writeExitCode(255)
                }
            }
        }

        private fun printUsage(shellCommandOutput: ShellCommandOutput) {
            shellCommandOutput.writeStderr("""
usage: am [subcommand] [options]
usage: am start [-D] [-W] [-P <FILE>] [--start-profiler <FILE>]
               [--R COUNT] [-S] [--opengl-trace] <INTENT>
       am startservice <INTENT>
       am force-stop <PACKAGE>
       am kill <PACKAGE>
       am kill-all
       am broadcast <INTENT>
       am instrument [-r] [-e <NAME> <VALUE>] [-p <FILE>] [-w]
               [--no-window-animation] <COMPONENT>
       am profile start <PROCESS> <FILE>
       am profile stop [<PROCESS>]
       am dumpheap [flags] <PROCESS> <FILE>
       am set-debug-app [-w] [--persistent] <PACKAGE>
       am clear-debug-app
       am monitor [--gdb <port>]
       am screen-compat [on|off] <PACKAGE>
       am display-size [reset|MxN]
       am to-uri [INTENT]
       am to-intent-uri [INTENT]

am start: start an Activity.  Options are:
    -D: enable debugging
    -W: wait for launch to complete
    --start-profiler <FILE>: start profiler and send results to <FILE>
    -P <FILE>: like above, but profiling stops when app goes idle
    -R: repeat the activity launch <COUNT> times.  Prior to each repeat,
        the top activity will be finished.
    -S: force stop the target app before starting the activity
    --opengl-trace: enable tracing of OpenGL functions

am startservice: start a Service.

am force-stop: force stop everything associated with <PACKAGE>.

am kill: Kill all processes associated with <PACKAGE>.  Only kills.
  processes that are safe to kill -- that is, will not impact the user
  experience.

am kill-all: Kill all background processes.

am broadcast: send a broadcast Intent.

am instrument: start an Instrumentation.  Typically this target <COMPONENT>
  is the form <TEST_PACKAGE>/<RUNNER_CLASS>.  Options are:
    -r: print raw results (otherwise decode REPORT_KEY_STREAMRESULT).  Use with
        [-e perf true] to generate raw output for performance measurements.
    -e <NAME> <VALUE>: set argument <NAME> to <VALUE>.  For test runners a
        common form is [-e <testrunner_flag> <value>[,<value>...]].
    -p <FILE>: write profiling data to <FILE>
    -w: wait for instrumentation to finish before returning.  Required for
        test runners.
    --no-window-animation: turn off window animations will running.

am profile: start and stop profiler on a process.

am dumpheap: dump the heap of a process.  Options are:
    -n: dump native heap instead of managed heap

am set-debug-app: set application <PACKAGE> to debug.  Options are:
    -w: wait for debugger when application starts
    --persistent: retain this value

am clear-debug-app: clear the previously set-debug-app.

am monitor: start monitoring for crashes or ANRs.
    --gdb: start gdbserv on the given port at crash/ANR

am screen-compat: control screen compatibility mode of <PACKAGE>.

am display-size: override display size.

am to-uri: print the given Intent specification as a URI.

am to-intent-uri: print the given Intent specification as an intent: URI.

<INTENT> specifications include these flags and arguments:
    [-a <ACTION>] [-d <DATA_URI>] [-t <MIME_TYPE>]
    [-c <CATEGORY> [-c <CATEGORY>] ...]
    [-e|--es <EXTRA_KEY> <EXTRA_STRING_VALUE> ...]
    [--esn <EXTRA_KEY> ...]
    [--ez <EXTRA_KEY> <EXTRA_BOOLEAN_VALUE> ...]
    [--ei <EXTRA_KEY> <EXTRA_INT_VALUE> ...]
    [--el <EXTRA_KEY> <EXTRA_LONG_VALUE> ...]
    [--ef <EXTRA_KEY> <EXTRA_FLOAT_VALUE> ...]
    [--eu <EXTRA_KEY> <EXTRA_URI_VALUE> ...]
    [--ecn <EXTRA_KEY> <EXTRA_COMPONENT_NAME_VALUE>]
    [--eia <EXTRA_KEY> <EXTRA_INT_VALUE>[,<EXTRA_INT_VALUE...]]
    [--ela <EXTRA_KEY> <EXTRA_LONG_VALUE>[,<EXTRA_LONG_VALUE...]]
    [--efa <EXTRA_KEY> <EXTRA_FLOAT_VALUE>[,<EXTRA_FLOAT_VALUE...]]
    [-n <COMPONENT>] [-f <FLAGS>]
    [--grant-read-uri-permission] [--grant-write-uri-permission]
    [--debug-log-resolution] [--exclude-stopped-packages]
    [--include-stopped-packages]
    [--activity-brought-to-front] [--activity-clear-top]
    [--activity-clear-when-task-reset] [--activity-exclude-from-recents]
    [--activity-launched-from-history] [--activity-multiple-task]
    [--activity-no-animation] [--activity-no-history]
    [--activity-no-user-action] [--activity-previous-is-top]
    [--activity-reorder-to-front] [--activity-reset-task-if-needed]
    [--activity-single-top] [--activity-clear-task]
    [--activity-task-on-home]
    [--receiver-registered-only] [--receiver-replace-pending]
    [--selector]
    [<URI> | <PACKAGE> | <COMPONENT>]
            """.trimIndent())
            shellCommandOutput.writeStderr("\n")
        }
    }
}
