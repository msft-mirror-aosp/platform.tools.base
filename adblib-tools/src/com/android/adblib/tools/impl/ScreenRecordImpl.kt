/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.adblib.tools.impl

import com.android.adblib.AdbDeviceServices
import com.android.adblib.DeviceSelector
import com.android.adblib.ShellCommand
import com.android.adblib.ShellOptions.ShellTerminalType
import com.android.adblib.shellTerminalSession
import com.android.adblib.tools.AdbScreenRecordException
import com.android.adblib.tools.ScreenRecordOptions
import com.android.adblib.tools.screenRecord
import com.android.adblib.withTextCollector
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

internal object ScreenRecordImpl {

    /**
     * Implementation of [AdbDeviceServices.screenRecord]
     *
     * Note: Below is the list of parameters documented from `screenrecord --help`
     * for various API levels:
     *
     * * API 36+: see [screenrecord v1.4][ConstantsUsedForKdocReferenceOnly.screenrecord_v1_4_help]
     * * API 30 to 35:see [screenrecord v1.3][ConstantsUsedForKdocReferenceOnly.screenrecord_v1_3_help]
     * * API 21 to 29: see [screenrecord v1.2][ConstantsUsedForKdocReferenceOnly.screenrecord_v1_2_help]
     * * API 19 to 20: see [screenrecord v1.0][ConstantsUsedForKdocReferenceOnly.screenrecord_v1_0_help]
     * * API 18 and lower: `screenrecord` is not supported
     */
    internal suspend fun screenRecord(
        deviceServices: AdbDeviceServices,
        device: DeviceSelector,
        remotePath: String,
        options: ScreenRecordOptions,
        stopRecordingSignal: Deferred<Unit>
    ) {
        // We use a piped channel to interact with the terminal used for running `screenrecord`
        //
        // Note: We create the piped channel outside the local coroutine scope block below to
        // prevent a race condition/non-determinism on cancellation: if the coroutine scope
        // is cancelled, we want to make sure this function rethrows the cancellation
        // exception "as-is".
        // However, if the pipe channel was inside the local coroutine scope, any exception
        // would force `AutoCloseable.close` method to run in the `finally` block. This could
        // in turn make the shell terminal session coroutine throw an EOFException because it
        // is reading from that pipe. That may cause 2 exceptions to be in flight
        // "at the same time": the CancellationException and the EOFException.
        // In those conditions, the coroutine runtime (sometimes) make the EOFException "win"
        // and be thrown to the caller, whereas we expect the CancellationException to always win.
        // By moving the "use block" (i.e. the "finally") outside the coroutineScope, we ensure
        // `CancellationException` always wins.
        // This behavior is covered by unit tests.
        deviceServices.session.channelFactory.createPipedChannel().use { terminalSessionPipe ->
            // Use a local coroutine scope to synchronize various async/await calls inside
            // this block.
            coroutineScope {
                // Build the screen record command and send it to the terminal session for execution
                val command = getScreenRecordCommand(options, remotePath)

                // Starts a coroutine that opens terminal session using `terminalSessionPipe`
                // as `stdin`
                // Note: We use the `Pty` terminal type option so that the `Ctrl-C` we send
                // on stop is processed correctly as a signal to `screenrecord`.
                // Note: This coroutine is where the `screenrecord` command runs, which is
                // expected to end when `Ctrl-C` is received from `stdin`.
                // This job throws if the `screenrecord` command fails for some reason
                val terminalSessionJob = async {
                    val commandOutput = deviceServices
                        .shellTerminalSession(device, ShellTerminalType.Pty)
                        .withTextCollector()
                        .withStdin(terminalSessionPipe)
                        .execute()
                        .first()

                    if (commandOutput.exitCode != 0) {
                        // Note: `screenrecord` runs inside a terminal session, so all
                        // output is redirected to `stdout`, even errors.
                        // However, we currently search both `stdout` and `stderr` until
                        // b/409832218 is fixed.
                        val commandError = extractErrorMessageOrNull(commandOutput.stderr) ?:
                                extractErrorMessageOrNull(commandOutput.stdout)
                        val messageTitle = if (commandError == null) {
                            "Screen recording terminated with exit code ${commandOutput.exitCode}"
                        } else {
                            "Screen recording terminated with the error \"$commandError\" " +
                                    "(exit code ${commandOutput.exitCode})"
                        }
                        throw AdbScreenRecordException(
                            message = "$messageTitle. Try to reduce video resolution or unlock the device.",
                            command = command,
                            commandError = commandError,
                            exitCode = commandOutput.exitCode
                        )
                    }
                }

                val terminalInput =
                    deviceServices.session.channelFactory.createOutputChannelWriter(terminalSessionPipe.pipeSource)

                // Build the screen record command and send it to the terminal session for execution
                terminalInput.writeString("$command || exit\r")

                // Coroutine that waits for the caller to tell us to stop the recording
                // This job throws an exception if `stopSignal` is completed exceptionally
                val stopRecordingJob = async {
                    // Wait on the external signal to stop the recording.
                    // Rethrow exception, including cancellation, immediately so this coroutine
                    // fails fast if needed.
                    stopRecordingSignal.await()

                    // Send Ctrl-C to terminal session
                    terminalInput.writeChar('\u0003') // Ctrl-C
                    terminalInput.writeString("exit\r")

                    // Wait for terminal session to end, ensuring the recording file is
                    // guaranteed to be correctly terminated.
                    terminalSessionJob.await()
                }

                // Wait for the `stopRecordingJob` (and the recording command) to end
                // Note we wait on both coroutines jobs so that any exception from either
                // is re-thrown immediately (and cancels this function immediately)
                awaitAll(terminalSessionJob, stopRecordingJob)

                assert(terminalSessionJob.isCompleted) {
                    "stopRecordingJob should have waited for the recording command to end"
                }
            }
        }
    }

    private fun extractErrorMessageOrNull(output: String): String? {
        val lines = output.split('\n')

        // 1) try to find a line starting with "ERROR:"
        // The `screenrecord` source code shows than many errors are prefixed with "ERROR:"
        // See https://cs.android.com/android/platform/superproject/main/+/75351138ec610ab0e085108d409fa64d877d4d0b:frameworks/av/cmds/screenrecord/screenrecord.cpp;l=218;bpv=1;bpt=0
        lines.lastOrNull { line -> line.startsWith("ERROR:") }?.also { line ->
            return@extractErrorMessageOrNull line.substring(6).trim().trimEnd('.')
        }

        // 2) Look for the first non-empty from the end
        lines.lastOrNull { line -> line.isNotEmpty() }?.also { line ->
            return@extractErrorMessageOrNull line.trim().trimEnd('.')
        }

        return null
    }

    internal fun getScreenRecordCommand(options: ScreenRecordOptions, path: String): String {
        val buf = StringBuilder("screenrecord")
        options.physicalDisplayId?.also {
            buf.append(" --display-id ").append(options.physicalDisplayId)
        }
        if (options.videoSize != null) {
            buf.append(" --size ").append(options.videoSize.width).append('x').append(options.videoSize.height)
        }
        if (options.bitRateMbps != null) {
            buf.append(" --bit-rate ").append(options.bitRateMbps * 1000000)
        }
        if (options.timeLimitSec != null) {
            buf.append(" --time-limit ").append(options.timeLimitSec)
        }
        if (options.verbose) {
            buf.append(" --verbose")
        }
        if (options.bugreport) {
            buf.append(" --bugreport")
        }
        if (path.isNotEmpty()) {
            buf.append(' ').append(ShellCommand.escapeDevicePath(path))
        }
        return buf.toString()
    }

    object ConstantsUsedForKdocReferenceOnly {

        /**
         * # screenrecord v1.0: API 19 to API 20
         *
         * ```
         * Usage: screenrecord [options] <filename>
         *
         * Records the device's display to a .mp4 file.
         *
         * Options:
         * --size WIDTHxHEIGHT
         *     Set the video size, e.g. "1280x720".  Default is the device's main
         *     display resolution (if supported), 1280x720 if not.  For best results,
         *     use a size supported by the AVC encoder.
         * --bit-rate RATE
         *     Set the video bit rate, in megabits per second.  Default 4Mbps.
         * --time-limit TIME
         *     Set the maximum recording time, in seconds.  Default / maximum is 180.
         * --rotate
         *     Rotate the output 90 degrees.
         * --verbose
         *     Display interesting information on stdout.
         * --help
         *     Show this message.
         *
         * Recording continues until Ctrl-C is hit or the time limit is reached.
         * ```
         */
        internal val screenrecord_v1_0_help = 1

        /**
         * # screenrecord v1.2: API 21 to API 29
         *
         * ```
         * Usage: screenrecord [options] <filename>
         *
         * Android screenrecord v1.2.  Records the device's display to a .mp4 file.
         *
         * Options:
         * --size WIDTHxHEIGHT
         *     Set the video size, e.g. "1280x720".  Default is the device's main
         *     display resolution (if supported), 1280x720 if not.  For best results,
         *     use a size supported by the AVC encoder.
         * --bit-rate RATE
         *     Set the video bit rate, in bits per second.  Value may be specified as
         *     bits or megabits, e.g. '4000000' is equivalent to '4M'.  Default 4Mbps.
         * --bugreport
         *     Add additional information, such as a timestamp overlay, that is helpful
         *     in videos captured to illustrate bugs.
         * --time-limit TIME
         *     Set the maximum recording time, in seconds.  Default / maximum is 180.
         * --verbose
         *     Display interesting information on stdout.
         * --help
         *     Show this message.
         *
         * Recording continues until Ctrl-C is hit or the time limit is reached.
         * ```
         */
        internal val screenrecord_v1_2_help = 1

        /**
         * # screenrecord v1.3: API 30 to API 35
         *
         * ```
         * Usage: screenrecord [options] <filename>
         *
         * Android screenrecord v1.3.  Records the device's display to a .mp4 file.
         *
         * Options:
         * --size WIDTHxHEIGHT
         *     Set the video size, e.g. "1280x720".  Default is the device's main
         *     display resolution (if supported), 1280x720 if not.  For best results,
         *     use a size supported by the AVC encoder.
         * --bit-rate RATE
         *     Set the video bit rate, in bits per second.  Value may be specified as
         *     bits or megabits, e.g. '4000000' is equivalent to '4M'.  Default 20Mbps.
         * --bugreport
         *     Add additional information, such as a timestamp overlay, that is helpful
         *     in videos captured to illustrate bugs.
         * --time-limit TIME
         *     Set the maximum recording time, in seconds.  Default is 180. Set to 0
         *     to remove the time limit.
         * --display-id ID
         *     specify the physical display ID to record. Default is the primary display.
         *     see "dumpsys SurfaceFlinger --display-id" for valid display IDs.
         * --verbose
         *     Display interesting information on stdout.
         * --help
         *     Show this message.
         *
         * Recording continues until Ctrl-C is hit or the time limit is reached.
         * ```
         */
        internal val screenrecord_v1_3_help = 1

        /**
         * # screenrecord v1.4: API 36 and later
         *
         * ```
         * Usage: screenrecord [options] <filename>
         *
         * Android screenrecord v1.4.  Records the device's display to a .mp4 file.
         *
         * Options:
         * --size WIDTHxHEIGHT
         *     Set the video size, e.g. "1280x720".  Default is the device's main
         *     display resolution (if supported), 1280x720 if not.  For best results,
         *     use a size supported by the AVC encoder.
         * --bit-rate RATE
         *     Set the video bit rate, in bits per second.  Value may be specified as
         *     bits or megabits, e.g. '4000000' is equivalent to '4M'.  Default 20Mbps.
         * --bugreport
         *     Add additional information, such as a timestamp overlay, that is helpful
         *     in videos captured to illustrate bugs.
         * --time-limit TIME
         *     Set the maximum recording time, in seconds.  Default is 180. Set to 0
         *     to remove the time limit.
         * --display-id ID
         *     specify the physical display ID to record. Default is the primary display.
         *     see "dumpsys SurfaceFlinger --display-id" for valid display IDs.
         * --verbose
         *     Display interesting information on stdout.
         * --version
         *     Show Android screenrecord version.
         * --help
         *     Show this message.
         *
         * Recording continues until Ctrl-C is hit or the time limit is reached.
         * ```
         */
        internal val screenrecord_v1_4_help = 1
    }
}
