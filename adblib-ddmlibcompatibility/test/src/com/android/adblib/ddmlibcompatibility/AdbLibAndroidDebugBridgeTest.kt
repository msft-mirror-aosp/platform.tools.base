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

import com.android.adblib.AdbServerChannelProvider
import com.android.adblib.AdbServerConfiguration
import com.android.adblib.AdbServerController
import com.android.adblib.ProcessRunner.ProcessResult
import com.android.adblib.testing.FakeAdbSession
import com.android.ddmlib.IDevice.DeviceState.ONLINE
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class AdbLibAndroidDebugBridgeTest {

    val config = MutableStateFlow<AdbServerConfiguration>(
        AdbServerConfiguration(
            null, null, false, false, emptyMap()
        )
    )

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
        config.update { it -> it.copy(adbPath = Paths.get("dir1", "adb")) }
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
        config.update { it -> it.copy(adbPath = Paths.get("dir1", "adb")) }
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
        val result = bridge.getAdbVersion(Paths.get("dir1", "adb"))

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
        val result = bridge.getAdbVersion(Paths.get("dir1", "adb"))

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
        val result = bridge.getAdbVersion(Paths.get("dir1", "adb"))

        // Assert
        try {
            result.get()
            fail("Should not reach")
        } catch (e: ExecutionException) {
            val cause = e.cause
            assertEquals(exception, cause)
        }
    }

    // TODO: Add many more tests

    private class FakeAdbServerController(private val startDelayMs: Long = 0) : AdbServerController {

        var throwOnStart: Throwable? = null
        var throwOnStop: Throwable? = null

        override val channelProvider: AdbServerChannelProvider
            get() {
                throw UnsupportedOperationException("Not yet implemented")
            }

        override var isStarted: Boolean = false
            private set

        override suspend fun start() {
            throwOnStart?.let { throw it }
            delay(startDelayMs)
            isStarted = true
        }

        override suspend fun stop() {
            throwOnStop?.let { throw it }
            isStarted = false
        }

        override fun close() {
            throw UnsupportedOperationException("Not yet implemented")
        }
    }
}
