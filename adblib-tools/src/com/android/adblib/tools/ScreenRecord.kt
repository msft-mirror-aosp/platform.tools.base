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
package com.android.adblib.tools

import com.android.adblib.AdbDeviceServices
import com.android.adblib.DeviceSelector
import com.android.adblib.tools.impl.ScreenRecordImpl
import com.android.adblib.tools.impl.ScreenRecordImpl.ConstantsUsedForKdocReferenceOnly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import java.io.IOException

/**
 * Records the device's display to a .mp4 file on the device located at [remotePath]
 * using the `screenrecord` command.
 *
 * The function runs as long as the recording is active, and stops when [stopRecordingSignal]
 * is [Deferred.getCompleted].
 *
 * When the function exits successfully, the video file at [remotePath] is
 * guaranteed to be a valid `mp4` file.
 *
 * Note: The caller is responsible for deleting [remotePath]
 *
 * Note: If [stopRecordingSignal] completes exceptionally, this function rethrows the exception
 * (e.g. cancellation) immediately.
 *
 * Note: Below is the list of parameters documented from `screenrecord --help`
 * for various API levels:
 *
 * * API 36+: see [screenrecord v1.4][ConstantsUsedForKdocReferenceOnly.screenrecord_v1_4_help]
 * * API 30 to 35:see [screenrecord v1.3][ConstantsUsedForKdocReferenceOnly.screenrecord_v1_3_help]
 * * API 21 to 29: see [screenrecord v1.2][ConstantsUsedForKdocReferenceOnly.screenrecord_v1_2_help]
 * * API 19 to 20: see [screenrecord v1.0][ConstantsUsedForKdocReferenceOnly.screenrecord_v1_0_help]
 * * API 18 and lower: `screenrecord` is not supported
 *
 * @throws AdbScreenRecordException if the `screenrecord` command fails on the device
 * @throws IOException on any I/O error
 * @throws CancellationException if either the coroutine or [stopRecordingSignal] is cancelled
 * @throws Throwable if [stopRecordingSignal] is completed exceptionally
 */
suspend fun AdbDeviceServices.screenRecord(
    device: DeviceSelector,
    remotePath: String,
    options: ScreenRecordOptions,
    stopRecordingSignal: Deferred<Unit>
) = ScreenRecordImpl.screenRecord(
    deviceServices = this,
    device = device,
    remotePath = remotePath,
    options = options,
    stopRecordingSignal = stopRecordingSignal
)

/**
 * Contains options for recording device screen.
 */
data class ScreenRecordOptions(
    /**
     * Set the video size, e.g. "1280x720".  Default is the device's main
     * display resolution (if supported), 1280x720 if not.  For best results,
     * use a size supported by the AVC encoder.
     */
    val videoSize: VideoSize? = null,

    /**
     * Bit rate in Mbps. Default 20Mbps.
     */
    val bitRateMbps: Int? = null,

    /**
     * The physical ID of the display to record. Default is the primary display.
     *
     * Note: This is supported only on API 30 and later.
     */
    val physicalDisplayId: Long? = null,

    /**
     * Display interesting information on stdout.
     */
    val verbose: Boolean = false,

    /**
     * Add additional information, such as a timestamp overlay, that is helpful in videos
     * captured to illustrate bugs.
     *
     * Note: This is supported only on API 21 and later.
     */
    val bugreport: Boolean = false,

    /**
     * Max recording duration in seconds. Default is 180. Set to 0 to remove the time limit.
     */
    val timeLimitSec: Int? = null,
) {
    /**
     * Set the video size, e.g. "1280x720".  Default is the device's main
     * display resolution (if supported), 1280x720 if not.  For best results,
     * use a size supported by the AVC encoder.
     */
    data class VideoSize(
        val width: Int,
        val height: Int,
    )
}

class AdbScreenRecordException(
    override val message: String,

    /**
     * The full `screenrecord` command as executed by the device shell session
     */
    val command: String,

    /**
     * (Optional) The error message from the `screenrecord` command
     */
    val commandError: String?,

    /**
     * The exit code of the `screenrecord` command
     */
    val exitCode: Int,

    cause: Throwable? = null
) : IOException(message, cause)
