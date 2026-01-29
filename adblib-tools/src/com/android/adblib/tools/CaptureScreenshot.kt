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

import com.android.adblib.AdbDeviceFailResponseException
import com.android.adblib.AdbDeviceServices
import com.android.adblib.AdbInputChannel
import com.android.adblib.DeviceSelector
import com.android.adblib.InputChannelShellCollector
import com.android.adblib.InputChannelShellOutput
import com.android.adblib.impl.channels.DEFAULT_CHANNEL_BUFFER_SIZE
import com.android.adblib.readText
import com.android.adblib.shellCommand
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible

/**
 * Uses the `screencap` command to capture a screenshot of the device display, then uses ImageIO.read to decode the image data as a
 * [BufferedImage].
 *
 * This method is tested on API level >= 15. When tested on lower API levels, e.g. API level 10, it outputs `adb: unknown command
 * screencap`.
 *
 * @param [device] the [DeviceSelector] corresponding to the target device
 * @param [physicalDisplayId] The physical display ID to capture. Default is the primary display (i.e. display with logical ID 0).
 * @param [bufferSize] The buffer size to use for reading the screenshot data. Defaults to [DEFAULT_CHANNEL_BUFFER_SIZE].
 * @return the captured image as [BufferedImage]
 * @throws AdbDeviceFailResponseException If there was an error reported while capturing the screenshot.
 * @throws IOException if unable to decode the image, probably due to unrecognized image format.
 */
suspend fun AdbDeviceServices.screenCapAsBufferedImage(
  device: DeviceSelector,
  physicalDisplayId: Long? = null,
  bufferSize: Int = DEFAULT_CHANNEL_BUFFER_SIZE,
): BufferedImage {
  return screenCap(device, pngEncoding = true, physicalDisplayId, bufferSize) { inputChannel ->
    AdbInputChannelInputStream(inputChannel, bufferSize).let { inputStream ->
      // Note: This assumes that `ImageIO` can decode the output format used by `screencap`,
      // for example, `png` format.
      // ImageIO.read() operates on an [InputStream], hence it's a blocking IO operation.
      runInterruptible(session.blockingIoDispatcher) {
        ImageIO.read(inputStream) ?: throw IOException("Unable to decode the image, probably due to unrecognized image format")
      }
    }
  }
}

private suspend fun <R> AdbDeviceServices.screenCap(
  device: DeviceSelector,
  pngEncoding: Boolean,
  physicalDisplayId: Long? = null,
  bufferSize: Int = DEFAULT_CHANNEL_BUFFER_SIZE,
  imageReader: suspend (AdbInputChannel) -> R,
): R {
  val command = buildString {
    append("screencap")
    if (pngEncoding) {
      append(" -p")
    }
    physicalDisplayId?.let { append(" -d $it") }
  }

  suspend fun processOutput(output: InputChannelShellOutput): R = coroutineScope {
    val stderrDeferred = async { output.stderr.readText() }
    val exitCodeDeferred = async { output.exitCode.filterNotNull().first() }
    val imageReaderResultDeferred = async { imageReader(output.stdout) }

    val exitCode = exitCodeDeferred.await()
    if (exitCode != 0) {
      val stderr = stderrDeferred.await()
      val message = "screencap exited with code: $exitCode" + if (stderr.isNotEmpty()) ", $stderr" else ""
      throw AdbDeviceFailResponseException(device, command, message)
    }
    return@coroutineScope imageReaderResultDeferred.await()
  }

  return session.deviceServices
    .shellCommand(device, command)
    .withCollector(InputChannelShellCollector(session, bufferSize))
    .executeAsSingleOutput { output -> processOutput(output) }
}
