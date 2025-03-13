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
package com.android.adblib.tools.tests

import com.android.adblib.AdbDeviceFailResponseException
import com.android.adblib.DeviceSelector
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.tools.screenCapAsBufferedImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO

class TestCaptureScreenshot {

    private val adbSession = FakeAdbSession()
    private val deviceServices = adbSession.deviceServices
    private val serialNumber = "123"
    private val device = DeviceSelector.fromSerialNumber(serialNumber)
    private val screenshot = BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY)
    private val screenshotBytes = screenshot.toBytes()
    private val emptyByteBuffer = ByteBuffer.allocate(0)
    private val displayId = 123L

    @Test
    fun testScreenCapAsBufferedImageSuccess() {
        deviceServices.configureShellV2Command(
            device,
            "screencap -p",
            ByteBuffer.wrap(screenshotBytes),
            emptyByteBuffer,
            0
        )

        runBlocking {
            val resultScreenshot = deviceServices.screenCapAsBufferedImage(device)
            assertArrayEquals(screenshotBytes, resultScreenshot.toBytes())
        }
    }

    @Test
    fun testScreenCapAsBufferedImage_correctPhysicalDisplayIdInCommand() {
        deviceServices.configureShellV2Command(
            device,
            "screencap -p -d $displayId",
            ByteBuffer.wrap(screenshotBytes),
            emptyByteBuffer,
            0
        )

        runBlocking {
            val resultScreenshot =
                deviceServices.screenCapAsBufferedImage(device, physicalDisplayId = displayId)
            assertArrayEquals(screenshotBytes, resultScreenshot.toBytes())
        }
    }

    @Test
    fun testScreenCapAsBufferedImageFailure() {
        deviceServices.configureShellV2Command(
            device,
            "screencap -p",
            ByteBuffer.wrap(screenshotBytes),
            emptyByteBuffer,
            1
        )

        try {
            runBlocking {
                deviceServices.screenCapAsBufferedImage(device)
                fail("Capturing screenshot should've failed")
            }
        } catch (_: AdbDeviceFailResponseException) {
        }
    }

    private fun BufferedImage.toBytes(): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        ImageIO.write(this, "png", byteArrayOutputStream)
        return byteArrayOutputStream.toByteArray()
    }
}
