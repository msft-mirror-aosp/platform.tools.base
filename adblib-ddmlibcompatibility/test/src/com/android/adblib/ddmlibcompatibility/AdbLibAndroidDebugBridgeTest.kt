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
package com.android.adblib.ddmlibcompatibility

import com.android.adblib.AdbChannel
import com.android.adblib.AdbServerChannelProvider
import com.android.adblib.AdbServerConfiguration
import com.android.adblib.AdbServerController
import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceList
import com.android.adblib.DeviceState
import com.android.adblib.ProcessRunner.ProcessResult
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.ddmlib.AdbInitOptions
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.IDevice.DeviceState.ONLINE
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class AdbLibAndroidDebugBridgeTest {

    val config = MutableStateFlow(
        AdbServerConfiguration(
            adbPath = null,
            serverPort = null,
            isUserManaged = false,
            isUnitTest = false,
            envVars = emptyMap()
        )
    )

    @Test
    fun init_cannotBeCalledTwice() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController(startDelayMs = 200)
        val bridge = AdbLibAndroidDebugBridge(session, adbServerController, config)

        // Act
        bridge.init(AdbInitOptions.DEFAULT)
        try {
            bridge.init(AdbInitOptions.DEFAULT)
            fail("Should have thrown")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun startReturnsFalse_whenItTimesOut() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController(startDelayMs = 200)
        val bridge = AdbLibAndroidDebugBridge(session, adbServerController, config)

        // Act
        val result = bridge.startAdb(50, TimeUnit.MILLISECONDS)

        // Assert
        assertFalse(result)

    }

    @Test
    fun startAdbReturnsFalse_whenAdbServerControllerThrows() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController()
        adbServerController.throwOnStart = IOException("my exception")
        val bridge = AdbLibAndroidDebugBridge(session, adbServerController, config)

        // Act
        val result = bridge.startAdb(50, TimeUnit.MILLISECONDS)

        // Assert
        assertFalse(result)
    }

    @Test
    fun startAdbReturnsFalse_whenAdbServerControllerThrowsCancellation() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController()
        adbServerController.throwOnStart = CancellationException("my cancellation exception")
        val bridge = AdbLibAndroidDebugBridge(session, adbServerController, config)

        // Act
        val result = bridge.startAdb(50, TimeUnit.MILLISECONDS)

        // Assert
        assertFalse(result)
    }

    @Test
    fun stopAdbReturnsFalse_whenAdbServerControllerThrows() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController()
        adbServerController.throwOnStop = IOException("my exception")
        val bridge = AdbLibAndroidDebugBridge(session, adbServerController, config)

        // Act
        val result = bridge.stopAdb(50, TimeUnit.MILLISECONDS)

        // Assert
        assertFalse(result)
    }

    @Test
    fun stopAdbReturnsFalse_whenAdbServerControllerThrowsCancellation() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController()
        adbServerController.throwOnStop = CancellationException("my cancellation exception")
        val bridge = AdbLibAndroidDebugBridge(session, adbServerController, config)

        // Act
        val result = bridge.stopAdb(50, TimeUnit.MILLISECONDS)

        // Assert
        assertFalse(result)
    }

    @Test
    fun getRawDeviceList_returnsEmptyListWhenAdbLocationIsNotSet() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController(startDelayMs = 200)
        val bridge = AdbLibAndroidDebugBridge(session, adbServerController, config)

        // Act
        val result = bridge.getRawDeviceList()

        // Assert
        assertTrue(result.isDone)
        assertTrue(result.get().isEmpty())
    }

    @Test
    fun getRawDeviceList_canParseProcessBuilderOutput() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController(startDelayMs = 200)
        config.update { it.copy(adbPath = Paths.get("dir1", "adb")) }
        val bridge =
            AdbLibAndroidDebugBridge(
                session,
                adbServerController,
                config
            )
        session.host.processRunner.resultToReturn =
            ProcessResult(
                listOf(
                    "List of devices attached",
                    "012345678         device usb:0-2 product:sunfish"
                ), listOf("Empty"), 0
            )

        // Act
        val result = bridge.getRawDeviceList()

        // Assert
        val adbDevices = result.get()
        assertEquals(1, adbDevices.size)
        assertEquals("012345678", adbDevices.first().serial)
        assertEquals(ONLINE, adbDevices.first().state)
    }

    @Test
    fun getRawDeviceList_transparentToExceptions() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController(startDelayMs = 200)
        config.update { it.copy(adbPath = Paths.get("dir1", "adb")) }
        val bridge =
            AdbLibAndroidDebugBridge(
                session,
                adbServerController,
                config
            )
        val exception = RuntimeException("abc")
        session.host.processRunner.throwOnNextCommand = exception

        // Act
        val result = bridge.getRawDeviceList()

        // Assert
        try {
            result.get()
            fail("Should not reach")
        } catch (e: ExecutionException) {
            val cause = e.cause
            assertEquals(exception, cause)
        }
    }

    @Test
    fun getAdbVersion_canParseProcessBuilderOutput() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController(startDelayMs = 200)
        val bridge =
            AdbLibAndroidDebugBridge(
                session,
                adbServerController,
                config
            )
        session.host.processRunner.resultToReturn =
            ProcessResult(
                listOf(
                    "Android Debug Bridge version 1.0.41",
                    "Version 35.0.2-12147458",
                    "Installed as /usr/local/bin/adb",
                    "Running on Darwin 24.2.0 (arm64)"
                ), emptyList(), 0
            )

        // Act
        val result = bridge.getAdbVersionForTesting(Paths.get("dir1", "adb"))

        // Assert
        val adbVersion = result.get()
        assertEquals("1.0.41", adbVersion.toString())
    }

    @Test
    fun getAdbVersion_throws_whenRunCommandEncounteredError() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController(startDelayMs = 200)
        val bridge =
            AdbLibAndroidDebugBridge(
                session,
                adbServerController,
                config
            )
        session.host.processRunner.resultToReturn =
            ProcessResult(
                listOf("sample out"), listOf("sample err"), 0xa23
            )

        // Act
        val result = bridge.getAdbVersionForTesting(Paths.get("dir1", "adb"))

        // Assert
        try {
            result.get()
            fail("Should not reach")
        } catch (e: ExecutionException) {
            val cause = e.cause
            assert(cause is RuntimeException)
            assertEquals(
                "Unable to detect adb version, exit value: 0xa23, adb stdout: sample out, adb stderr: sample err",
                cause?.message
            )
        }
    }

    @Test
    fun getAdbVersion_transparentToExceptions() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController(startDelayMs = 200)
        val bridge =
            AdbLibAndroidDebugBridge(
                session,
                adbServerController,
                config
            )
        val exception = RuntimeException("abc")
        session.host.processRunner.throwOnNextCommand = exception

        // Act
        val result = bridge.getAdbVersionForTesting(Paths.get("dir1", "adb"))

        // Assert
        try {
            result.get()
            fail("Should not reach")
        } catch (e: ExecutionException) {
            val cause = e.cause
            assertEquals(exception, cause)
        }
    }

    @Test
    fun getSocketAddress_inUnitTestMode_returnDefaultInetSocketAddress() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController()
        val serverPort = 123
        val defaultRemoteAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort)
        val bridge =
            AdbLibAndroidDebugBridge(
                session,
                adbServerController,
                config
            )

        // Act / Assert
        bridge.enableFakeAdbServerMode(serverPort)
        assertEquals(defaultRemoteAddress, bridge.socketAddress)
    }

    @Test
    fun getSocketAddress_returnDefaultInetSocketAddress_whenAdbServerControllerThrows() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController()
        val defaultRemoteAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
        val bridge =
            AdbLibAndroidDebugBridge(
                session,
                adbServerController,
                config
            )
        adbServerController.channelProvider.exceptionToThrow = Exception("Test exception")

        // Act / Assert
        assertEquals(defaultRemoteAddress, bridge.socketAddress)
    }

    @Test
    fun getSocketAddress_getsAddressFromAdbServerController() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController()
        val defaultRemoteAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
        val bridge =
            AdbLibAndroidDebugBridge(
                session,
                adbServerController,
                config
            )

        // Act / Assert: `socketAddress` set to default when AdbServerController's
        // `lastKnownRemoteAddress` is not set. Assert a call to `createChannel` is happening.
        assertEquals(defaultRemoteAddress, bridge.socketAddress)
        assertEquals(1, adbServerController.channelProvider.createChannelCallCount)

        // Act / Assert: Try again and assert that a call to `createChannel` happens again.
        assertEquals(defaultRemoteAddress, bridge.socketAddress)
        assertEquals(2, adbServerController.channelProvider.createChannelCallCount)

        // Act / Assert: Have `AdbServerController`'s `lastKnownRemoteAddress` set to a non-null
        // value and try getting `socketAddress` again.
        val controllerConfiguredLastKnownRemoteAddress = InetSocketAddress(157)
        adbServerController.lastKnownRemoteAddress = controllerConfiguredLastKnownRemoteAddress
        assertEquals(controllerConfiguredLastKnownRemoteAddress, bridge.socketAddress)
        assertEquals(2, adbServerController.channelProvider.createChannelCallCount)
    }

    @Test
    fun getSocketAddress_returnsDefault_whenControllerIsNotStarted() = runBlocking {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController(startedByDefault = false)
        val defaultRemoteAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
        val bridge =
            AdbLibAndroidDebugBridge(
                session,
                adbServerController,
                config
            )

        // Act / Assert: `socketAddress` set to default when AdbServerController's
        // `lastKnownRemoteAddress` is not set. Assert a call to `createChannel` is not happening
        // as controller has not been started
        assertEquals(defaultRemoteAddress, bridge.socketAddress)
        assertEquals(0, adbServerController.channelProvider.createChannelCallCount)

        // Act: Start the controller and ensure that the call to `createChannel` is now happening
        adbServerController.start()
        assertEquals(defaultRemoteAddress, bridge.socketAddress)
        assertEquals(1, adbServerController.channelProvider.createChannelCallCount)
    }

    @Test
    fun hasInitialDeviceList_isSetToTrueAfterCreateBridge() = runBlockingWithTimeout {
        // Setup
        val session = FakeAdbSession()

        val adbServerController = FakeAdbServerController()
        val bridge = AdbLibAndroidDebugBridge(session, adbServerController, config)

        // Act
        val debugBridge = bridge.createBridge()

        // Assert
        assertNotNull(debugBridge)
        yieldUntil { bridge.hasInitialDeviceList() }

        // Cleanup
        bridge.disconnectBridge()
        bridge.terminate()
    }

    @Test
    fun createBridge_triggersNotifyBridgeChangeEvents() = runBlockingWithTimeout {
        // Setup
        val session = FakeAdbSession()

        val adbServerController = FakeAdbServerController()
        val bridge = AdbLibAndroidDebugBridge(session, adbServerController, config)
        val debugBridgeChangeListener = TestDebugBridgeChangeListener()
        bridge.addDebugBridgeChangeListener(debugBridgeChangeListener)

        // Act / Assert
        val debugBridge = bridge.createBridge()

        assertEquals(1, debugBridgeChangeListener.bridgeChangedCallCount)
        assertEquals(debugBridge, debugBridgeChangeListener.lastBridge)

        // Act / Assert
        bridge.disconnectBridge()
        bridge.terminate()

        assertEquals(2, debugBridgeChangeListener.bridgeChangedCallCount)
        assertEquals(null, debugBridgeChangeListener.lastBridge)

        // Cleanup
        bridge.removeDebugBridgeChangeListener(debugBridgeChangeListener)
    }

    @Test
    fun createBridgeWithOsLocationParam_triggersNotifyBridgeChangeEvents() =
        runBlockingWithTimeout {
            // Setup
            val session = FakeAdbSession()

            val adbServerController = FakeAdbServerController()
            val bridge = AdbLibAndroidDebugBridge(session, adbServerController, config)
            val debugBridgeChangeListener = TestDebugBridgeChangeListener()
            bridge.addDebugBridgeChangeListener(debugBridgeChangeListener)

            // Act / Assert
            val debugBridge = bridge.createBridge("/path/to/adb", forceNewBridge = false)

            assertEquals(1, debugBridgeChangeListener.bridgeChangedCallCount)
            assertEquals(debugBridge, debugBridgeChangeListener.lastBridge)

            // Act / Assert
            bridge.disconnectBridge()
            bridge.terminate()

            assertEquals(2, debugBridgeChangeListener.bridgeChangedCallCount)
            assertEquals(null, debugBridgeChangeListener.lastBridge)

            // Cleanup
            bridge.removeDebugBridgeChangeListener(debugBridgeChangeListener)
        }

    @Test
    fun notifyDeviceConnectedAndDisconnected_areTriggeredOnEveryCreateAndDestroy() =
        runBlockingWithTimeout {
            // Setup
            val session = FakeAdbSession()
            val adbServerController = FakeAdbServerController()
            val adbLibAndroidDebugBridge =
                AdbLibAndroidDebugBridge(session, adbServerController, config)

            // This test relies on device change events, dispatched from `IDeviceManager`.
            // We use the `withPreInitAndroidDebugBridge` helper to
            // register our test `bridge` as `AndroidDebugBridge.delegate`.
            // This is necessary because the underlying `IDeviceManager`
            // will ignore events if the global bridge does not match the bridge instance
            // that was used to create it.
            withPreInitAndroidDebugBridge(adbLibAndroidDebugBridge) {
                session.hostServices.devices =
                    DeviceList(listOf(DeviceInfo("device1", DeviceState.ONLINE)), emptyList())

                val deviceConnectionListener = TestDeviceChangeListener()

                adbLibAndroidDebugBridge.addDeviceChangeListener(deviceConnectionListener)

                // Act / Assert
                adbLibAndroidDebugBridge.createBridge()

                yieldUntil { deviceConnectionListener.connectedDeviceEventCount == 1 }
                assertEquals(
                    adbLibAndroidDebugBridge.devices.single(),
                    deviceConnectionListener.lastConnectedDevice
                )

                // Act / Assert
                adbLibAndroidDebugBridge.disconnectBridge()
                adbLibAndroidDebugBridge.terminate()

                yieldUntil { deviceConnectionListener.disconnectedDeviceEventCount == 1 }
                assertEquals(
                    deviceConnectionListener.lastDisconnectedDevice,
                    deviceConnectionListener.lastConnectedDevice
                )

                // Act / Assert
                adbLibAndroidDebugBridge.createBridge()

                yieldUntil { deviceConnectionListener.connectedDeviceEventCount == 2 }
                assertEquals(
                    adbLibAndroidDebugBridge.devices.single(),
                    deviceConnectionListener.lastConnectedDevice
                )

                // Act / Assert
                adbLibAndroidDebugBridge.disconnectBridge()
                adbLibAndroidDebugBridge.terminate()

                yieldUntil { deviceConnectionListener.disconnectedDeviceEventCount == 2 }
                assertEquals(
                    deviceConnectionListener.lastDisconnectedDevice,
                    deviceConnectionListener.lastConnectedDevice
                )

                // Cleanup
                adbLibAndroidDebugBridge.removeDeviceChangeListener(deviceConnectionListener)
            }
        }

    // TODO: Add many more tests

    /**
     * This helper is needed when test code relies on calls to static
     * AndroidDebugBridge methods.
     */
    private suspend fun withPreInitAndroidDebugBridge(
        adbLibAndroidDebugBridge: AdbLibAndroidDebugBridge,
        block: suspend AdbLibAndroidDebugBridge.() -> Unit
    ) {
        AndroidDebugBridge.preInit(adbLibAndroidDebugBridge)
        try {
            adbLibAndroidDebugBridge.block()
        } finally {
            AndroidDebugBridge.disconnectBridge(10, TimeUnit.SECONDS)
            AndroidDebugBridge.terminate()
            AndroidDebugBridge.resetForTests()
        }
    }

    private class FakeAdbServerController(
        private val startDelayMs: Long = 0, startedByDefault: Boolean = true
    ) : AdbServerController {

        var throwOnStart: Throwable? = null
        var throwOnStop: Throwable? = null

        override var isStarted: Boolean = startedByDefault
            private set

        override var lastKnownRemoteAddress: InetSocketAddress? = null

        override suspend fun start() {
            throwOnStart?.let { throw it }
            delay(startDelayMs)
            isStarted = true
        }

        override suspend fun stop() {
            throwOnStop?.let { throw it }
            isStarted = false
        }

        override suspend fun waitIsStarted() {
            throw UnsupportedOperationException("Not yet implemented")
        }

        override fun close() {
            throw UnsupportedOperationException("Not yet implemented")
        }

        override val channelProvider = FakeAdbServerChannelProvider()

        inner class FakeAdbServerChannelProvider : AdbServerChannelProvider {
            var exceptionToThrow: Throwable? = null
            var createChannelCallCount = 0

            override suspend fun createChannel(
                timeout: Long,
                unit: TimeUnit
            ): AdbChannel {
                ++createChannelCallCount
                exceptionToThrow?.let { throw it }

                // Matches the implementation of `AdbServerControllerImpl` where we wait for
                // the controller to start up before attempting to create a channel.
                yieldUntil { isStarted }

                return object : AdbChannel {
                    override suspend fun shutdownInput() {
                        throw UnsupportedOperationException("Not yet implemented")
                    }

                    override suspend fun shutdownOutput() {
                        throw UnsupportedOperationException("Not yet implemented")
                    }

                    override suspend fun readBuffer(
                        buffer: ByteBuffer,
                        timeout: Long,
                        unit: TimeUnit
                    ) {
                        throw UnsupportedOperationException("Not yet implemented")
                    }

                    override fun close() {
                        throw UnsupportedOperationException("Not yet implemented")
                    }

                    override suspend fun writeBuffer(
                        buffer: ByteBuffer,
                        timeout: Long,
                        unit: TimeUnit
                    ) {
                        throw UnsupportedOperationException("Not yet implemented")
                    }
                }
            }
        }
    }

    private class TestDebugBridgeChangeListener : AndroidDebugBridge.IDebugBridgeChangeListener {

        var bridgeChangedCallCount = 0
            private set

        var lastBridge: AndroidDebugBridge? = null
            private set

        override fun bridgeChanged(bridge: AndroidDebugBridge?) {
            ++bridgeChangedCallCount
            lastBridge = bridge
        }
    }

    private class TestDeviceChangeListener : AndroidDebugBridge.IDeviceChangeListener {

        var connectedDeviceEventCount: Int = 0
            private set
        var lastConnectedDevice: IDevice? = null
            private set

        var disconnectedDeviceEventCount: Int = 0
            private set
        var lastDisconnectedDevice: IDevice? = null
            private set

        var deviceChangedEventCount: Int = 0
            private set
        var lastDeviceChangedDevice: IDevice? = null
            private set
        var lastDeviceChangedMask: Int? = null
            private set

        override fun deviceConnected(device: IDevice) {
            ++connectedDeviceEventCount
            lastConnectedDevice = device
        }

        override fun deviceDisconnected(device: IDevice) {
            ++disconnectedDeviceEventCount
            lastDisconnectedDevice = device
        }

        override fun deviceChanged(device: IDevice, changeMask: Int) {
            ++deviceChangedEventCount
            lastDeviceChangedDevice = device
            lastDeviceChangedMask = changeMask
        }
    }
}
