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
import com.android.adblib.ConnectedDevice
import com.android.adblib.DeviceSelector
import com.android.adblib.selector
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.tools.impl.ScreenRecordImpl
import com.android.adblib.tools.testutils.assertSuspendingThrows
import com.android.adblib.tools.testutils.asyncNoThrow
import com.android.adblib.tools.testutils.waitForOnlineConnectedDevice
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.shellcommandhandlers.ScreenRecordCommandHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScreenRecordTest {

    @JvmField
    @Rule
    val fakeAdbRule = FakeAdbServerProviderRule {
        installDefaultCommandHandlers()
    }

    private val fakeAdb get() = fakeAdbRule.fakeAdb
    private val deviceServices get() = fakeAdbRule.adbSession.deviceServices

    @Test
    fun test_screenRecord_worksWithStopSignal(): Unit = runBlockingWithTimeout {
        // Prepare
        val (fakeDevice, connectedDevice) = createTestDevice()

        // Act
        val stopSignal = CompletableDeferred<Unit>()
        val screenRecorderJob = async {
            val options = ScreenRecordOptions()
            deviceServices.screenRecord(
                connectedDevice.selector,
                remotePath = "/sdcard/foo/bar.mp4",
                options = options,
                stopRecordingSignal = stopSignal
            )
        }

        // Wait until file is created in the device
        yieldUntil {
            ScreenRecordCommandHandler.UNFINISHED_RECORDING_CONTENTS
                .contentEquals(fakeDevice.getFile("/sdcard/foo/bar.mp4")?.bytes)
        }
        // Stop the screen recording operation and wait for it to finish
        stopSignal.complete(Unit)
        screenRecorderJob.await()

        // Assert
        val file = fakeDevice.getFile("/sdcard/foo/bar.mp4")
        Assert.assertNotNull(file)
        assertEquals(ScreenRecordCommandHandler.FINISHED_RECORDING_CONTENTS, file?.bytes)
    }

    @Test
    fun test_screenRecord_allowsCancellationThroughSignal(): Unit = runBlockingWithTimeout {
        // Prepare
        val (fakeDevice, connectedDevice) = createTestDevice()

        // Act
        val stopSignal = CompletableDeferred<Unit>()
        // Note: We use `asyncNoThrow` here because we expect the job to fail (and
        //   we don't want the test to fail)
        val screenRecorderJob = asyncNoThrow {
            val options = ScreenRecordOptions()
            deviceServices.screenRecord(
                connectedDevice.selector,
                remotePath = "/sdcard/foo/bar.mp4",
                options = options,
                stopRecordingSignal = stopSignal
            )
        }

        // Wait until file is created in the device
        yieldUntil {
            ScreenRecordCommandHandler.UNFINISHED_RECORDING_CONTENTS
                .contentEquals(fakeDevice.getFile("/sdcard/foo/bar.mp4")?.bytes)
        }
        // Stop the screen recording operation and wait for it to finish
        stopSignal.cancel("Cancellation from test")

        // Assert
        assertSuspendingThrows(
            expectedException = CancellationException::class.java,
            additionalAssertions = { e ->
                assertEquals("Cancellation from test", e.message)
            }
        ) {
            screenRecorderJob.await()
        }
        val file = fakeDevice.getFile("/sdcard/foo/bar.mp4")
        Assert.assertNotNull(file)
        assertEquals(ScreenRecordCommandHandler.UNFINISHED_RECORDING_CONTENTS, file?.bytes)
    }

    @Test
    fun test_screenRecord_allowsExceptionThroughSignal(): Unit = runBlockingWithTimeout {
        // Prepare
        val (fakeDevice, connectedDevice) = createTestDevice()

        // Act
        val stopSignal = CompletableDeferred<Unit>()
        // Note: We use `asyncNoThrow` here because we expect the job to fail (and
        //   we don't want the test to fail)
        val screenRecorderJob = asyncNoThrow {
            val options = ScreenRecordOptions()
            deviceServices.screenRecord(
                connectedDevice.selector,
                remotePath = "/sdcard/foo/bar.mp4",
                options = options,
                stopRecordingSignal = stopSignal
            )
        }

        // Wait until file is created in the device
        yieldUntil {
            ScreenRecordCommandHandler.UNFINISHED_RECORDING_CONTENTS
                .contentEquals(fakeDevice.getFile("/sdcard/foo/bar.mp4")?.bytes)
        }
        // Stop the screen recording operation and wait for it to finish
        stopSignal.completeExceptionally(MyTestException("Exception from test"))

        // Assert
        assertSuspendingThrows(
            expectedException = MyTestException::class.java,
            additionalAssertions = { e ->
                assertEquals("Exception from test", e.message)
            }
        ) {
            screenRecorderJob.await()
        }
        val file = fakeDevice.getFile("/sdcard/foo/bar.mp4")
        Assert.assertNotNull(file)
        assertEquals(ScreenRecordCommandHandler.UNFINISHED_RECORDING_CONTENTS, file?.bytes)
    }

    @Test
    fun test_screenRecord_ThrowsOnInvalidDevice(): Unit = runBlockingWithTimeout {
        // Prepare
        val (_, _) = createTestDevice()

        // Act
        val stopSignal = CompletableDeferred<Unit>()
        val options = ScreenRecordOptions()

        // Assert
        assertSuspendingThrows(
            expectedException = AdbDeviceFailResponseException::class.java,
            additionalAssertions = { e ->
                assertEquals(
                    "'No device with serial: 'invalid-id' is connected.'" +
                            " error on device serial #invalid-id executing" +
                            " service 'host-serial:invalid-id:features'", e.message
                )
            }
        ) {
            deviceServices.screenRecord(
                DeviceSelector.fromSerialNumber("invalid-id"),
                remotePath = "/sdcard/foo/bar.mp4",
                options = options,
                stopRecordingSignal = stopSignal
            )
        }
    }

    @Test
    fun test_screenRecord_ThrowsOnMissingOutputFileName(): Unit = runBlockingWithTimeout {
        // Prepare
        val (_, connectedDevice) = createTestDevice()

        // Act
        val stopSignal = CompletableDeferred<Unit>()
        val options = ScreenRecordOptions()

        // Assert
        assertSuspendingThrows(
            expectedException = AdbScreenRecordException::class.java,
            additionalAssertions = { e ->
                assertEquals("Screen recording terminated with the error " +
                                     "\"Must specify output file (see --help)\" (exit code 2). " +
                                     "Try to reduce video resolution or unlock the device.",
                             e.message)
                assertEquals("screenrecord", e.command)
                assertEquals("Must specify output file (see --help)", e.commandError)
                assertEquals(2, e.exitCode)
            }
        ) {
            deviceServices.screenRecord(
                connectedDevice.selector,
                remotePath = "",
                options = options,
                stopRecordingSignal = stopSignal
            )
        }
    }

    @Test
    fun test_getScreenRecordCommand_worksWithDefaultOptions() {
        // Prepare
        val options = ScreenRecordOptions()

        // Act
        val command = ScreenRecordImpl.getScreenRecordCommand(options, "foobar.mp4")

        // Assert
        assertEquals("screenrecord foobar.mp4", command)
    }

    @Test
    fun test_getScreenRecordCommand_escapesPathIfNeeded() {
        // Prepare
        val options = ScreenRecordOptions()

        // Act
        val command = ScreenRecordImpl.getScreenRecordCommand(options, "/sdcard/blah&/foo bar.mp4")

        // Assert
        assertEquals("screenrecord /sdcard/blah\\&/foo\\ bar.mp4", command)
    }

    @Test
    fun test_getScreenRecordCommand_allowsSettingWithAndHeight() {
        // Prepare
        val options = ScreenRecordOptions(ScreenRecordOptions.VideoSize(width = 1000, height = 700))

        // Act
        val command = ScreenRecordImpl.getScreenRecordCommand(options, "foobar.mp4")

        // Assert
        assertEquals("screenrecord --size 1000x700 foobar.mp4", command)
    }

    @Test
    fun test_getScreenRecordCommand_allowsSettingVerbose() {
        // Prepare
        val options = ScreenRecordOptions(verbose = true)

        // Act
        val command = ScreenRecordImpl.getScreenRecordCommand(options, "foobar.mp4")

        // Assert
        assertEquals("screenrecord --verbose foobar.mp4", command)
    }

    @Test
    fun test_getScreenRecordCommand_allowsSettingBugreport() {
        // Prepare
        val options = ScreenRecordOptions(bugreport = true)

        // Act
        val command = ScreenRecordImpl.getScreenRecordCommand(options, "foobar.mp4")

        // Assert
        assertEquals("screenrecord --bugreport foobar.mp4", command)
    }

    @Test
    fun test_getScreenRecordCommand_allowsSettingTimeLimitSec() {
        // Prepare
        val options = ScreenRecordOptions(timeLimitSec = 290)

        // Act
        val command = ScreenRecordImpl.getScreenRecordCommand(options, "foobar.mp4")

        // Assert
        assertEquals("screenrecord --time-limit 290 foobar.mp4", command)
    }

    @Test
    fun test_getScreenRecordCommand_allowsSettingBitRate() {
        // Prepare
        val options = ScreenRecordOptions(bitRateMbps = 3)

        // Act
        val command = ScreenRecordImpl.getScreenRecordCommand(options, "foobar.mp4")

        // Assert
        assertEquals("screenrecord --bit-rate 3000000 foobar.mp4", command)
    }

    @Test
    fun test_getScreenRecordCommand_allowsSettingPhysicalDisplayId() {
        // Prepare
        val options = ScreenRecordOptions(physicalDisplayId = 200)

        // Act
        val command = ScreenRecordImpl.getScreenRecordCommand(options, "foobar.mp4")

        // Assert
        assertEquals("screenrecord --display-id 200 foobar.mp4", command)
    }

    private class MyTestException(message: String) : Exception(message)

    private suspend fun createTestDevice(sdkApi: Int = 31): Pair<DeviceState, ConnectedDevice> {
        val deviceID = "1234"
        val fakeDevice =
            fakeAdb.connectDevice(
                deviceID,
                "test1",
                "test2",
                "model",
                sdkApi.toString(),
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        val connectedDevice =
            waitForOnlineConnectedDevice(deviceServices.session, fakeDevice.deviceId)
        return Pair(fakeDevice, connectedDevice)
    }
}
