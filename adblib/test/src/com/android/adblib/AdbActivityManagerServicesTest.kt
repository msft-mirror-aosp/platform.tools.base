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
package com.android.adblib

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProvider
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException

class AdbActivityManagerServicesTest {
    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @JvmField
    @Rule
    val fakeAdbRule = FakeAdbServerProviderRule()

    private val fakeAdb get() = fakeAdbRule.fakeAdb
    private val activityManagerServices get() = fakeAdbRule.adbSession.activityManagerServices

    @Test
    fun testForceStop(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(device.deviceId)
        val pid = 101
        device.startClient(pid, 1000, "package1", false)
        yieldUntil { device.getClient(pid) != null }

        // Act
        activityManagerServices.forceStop(deviceSelector, "package1")

        // Assert
        yieldUntil { device.getClient(pid) == null }
    }

    @Test
    fun testForceStopThrows_whenPackageContainsInvalidCharacters(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(device.deviceId)

        // Act
        exceptionRule.expect(IllegalArgumentException::class.java)
        activityManagerServices.forceStop(deviceSelector, "package name with spaces")

        // Assert
        Assert.fail("Test should have thrown an exception")
    }

    @Test
    fun testCrash(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(device.deviceId)
        val pid = 101
        device.startClient(pid, 1000, "package1", false)
        yieldUntil { device.getClient(pid) != null }

        // Act
        activityManagerServices.crash(deviceSelector, "package1")

        // Assert
        yieldUntil { device.getClient(pid) == null }
    }

    @Test
    fun testCrashThrows_whenPackageContainsInvalidCharacters(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeDevice(fakeAdb)
        val deviceSelector = DeviceSelector.fromSerialNumber(device.deviceId)

        // Act
        exceptionRule.expect(IllegalArgumentException::class.java)
        activityManagerServices.crash(deviceSelector, "package&ampersand")

        // Assert
        Assert.fail("Test should have thrown an exception")
    }

    @Test
    fun testCapabilitiesApi35(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeDevice(fakeAdb, sdk = 35)
        val deviceSelector = DeviceSelector.fromSerialNumber(device.deviceId)

        // Act
        val result = activityManagerServices.capabilities(deviceSelector)

        // Assert
        Assert.assertNotNull(result)
        Assert.assertEquals(listOf("start.suspend"), result.capabilities)
        Assert.assertEquals(listOf("method-trace-profiling",
                                   "method-trace-profiling-streaming",
                                   "method-sample-profiling",
                                   "hprof-heap-dump",
                                   "hprof-heap-dump-streaming",
                                   "app_info",
                                   ), result.vmCapabilities)
        Assert.assertEquals(listOf("opengl-tracing",
                                   "view-hierarchy",
                                   "support_boot_stages",
                                   ), result.frameworkCapabilities)
        Assert.assertEquals("Dalvik", result.vmInfo?.name)
        Assert.assertEquals("2.1.0", result.vmInfo?.version)
    }

    @Test
    fun testCapabilitiesApi36(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeDevice(fakeAdb, sdk = 36)
        val deviceSelector = DeviceSelector.fromSerialNumber(device.deviceId)

        // Act
        val result = activityManagerServices.capabilities(deviceSelector)

        // Assert
        Assert.assertNotNull(result)
        Assert.assertEquals(listOf("start.suspend"), result.capabilities)
        Assert.assertEquals(listOf("method-trace-profiling",
                                   "method-trace-profiling-streaming",
                                   "method-sample-profiling",
                                   "hprof-heap-dump",
                                   "hprof-heap-dump-streaming",
                                   "app_info",
        ), result.vmCapabilities)
        Assert.assertEquals(listOf("opengl-tracing",
                                   "view-hierarchy",
                                   "support_boot_stages",
                                   "app_info",
        ), result.frameworkCapabilities)
        Assert.assertEquals("Dalvik", result.vmInfo?.name)
        Assert.assertEquals("2.1.0", result.vmInfo?.version)
    }

    @Test
    fun testCapabilitiesThrows_whenCapabilitiesIsNotSupported(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeDevice(fakeAdb, sdk = 30)
        val deviceSelector = DeviceSelector.fromSerialNumber(device.deviceId)

        // Act
        val result = runCatching { activityManagerServices.capabilities(deviceSelector) }

        // Assert
        result.onFailure { throwable ->
            Assert.assertTrue(throwable is AdbActivityManagerException)
            Assert.assertTrue((throwable as AdbActivityManagerException).isCommandNotSupported)
            Assert.assertFalse(throwable.isServiceNotRunning)
        }.onSuccess {
            Assert.fail("Command should have failed")
        }
    }

    @Test
    fun testCapabilitiesThrows_whenCapabilitiesIsNotSupportedOnDeviceWithOlderAmImplementation(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeDevice(fakeAdb, sdk = 24)
        val deviceSelector = DeviceSelector.fromSerialNumber(device.deviceId)

        // Act
        val result = runCatching { activityManagerServices.capabilities(deviceSelector) }

        // Assert
        result.onFailure { throwable ->
            Assert.assertTrue(throwable is AdbActivityManagerException)
            Assert.assertTrue(throwable.message!!.contains(
                "Error executing 'am capabilities --protobuf' on device serial #1234"))
            Assert.assertTrue((throwable as AdbActivityManagerException).isCommandNotSupported)
            Assert.assertFalse(throwable.isServiceNotRunning)
        }.onSuccess {
            Assert.fail("Command should have failed")
        }
    }

    @Test
    fun testCapabilitiesThrows__whenCapabilitiesIsNotSupportedOnDeviceWithOlderAmImplementationWithoutShellV2Support(): Unit = runBlockingWithTimeout {
        // Prepare
        val device = addFakeDevice(fakeAdb, sdk = 22)
        val deviceSelector = DeviceSelector.fromSerialNumber(device.deviceId)

        // Act
        val result = runCatching { activityManagerServices.capabilities(deviceSelector) }

        // Assert
        result.onFailure { throwable ->
            Assert.assertTrue(throwable is AdbActivityManagerException)
            Assert.assertTrue(throwable.message!!.contains(
                "Error executing 'am capabilities --protobuf' on device serial #1234"))
            Assert.assertTrue((throwable as AdbActivityManagerException).isCommandNotSupported)
            Assert.assertFalse(throwable.isServiceNotRunning)
        }.onSuccess {
            Assert.fail("Command should have failed")
        }
    }

    private fun addFakeDevice(fakeAdb: FakeAdbServerProvider, sdk: Int = 30): DeviceState {
        val fakeDevice =
            fakeAdb.connectDevice(
                "1234",
                "test1",
                "test2",
                "model",
                AndroidApiLevel(sdk),
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        return fakeDevice
    }
}
