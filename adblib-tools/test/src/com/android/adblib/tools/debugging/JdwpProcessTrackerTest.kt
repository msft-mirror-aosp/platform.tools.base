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
package com.android.adblib.tools.debugging

import com.android.adblib.InstructionSet
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.tools.testutils.waitForOnlineConnectedDevice
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class JdwpProcessTrackerTest {

    @JvmField
    @Rule
    val fakeAdbRule = FakeAdbServerProviderRule {
        installDefaultCommandHandlers()
        setFeatures("push_sync")
    }

    private val fakeAdb get() = fakeAdbRule.fakeAdb
    private val hostServices get() = fakeAdbRule.adbSession.hostServices

    @Test
    fun testJdwpProcessTrackerWorks(): Unit = runBlockingWithTimeout {
        val deviceID = "1234"
        val fakeDevice =
            fakeAdb.connectDevice(
                deviceID,
                "test1",
                "test2",
                "model",
                AndroidApiLevel(30), // SDK >= 30 is required for abb_exec feature.
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        val connectedDevice =
            waitForOnlineConnectedDevice(hostServices.session, fakeDevice.deviceId)
        val pid10 = 10
        val pid11 = 11

        // Act
        val listOfProcessList = CopyOnWriteArrayList<List<JdwpProcess>>()
        launch {
            fakeDevice.startClient(pid10, 0, "a.b.c", false)
            assertNotNull(fakeDevice.getClient(pid10))
            yieldUntil {
                val size = listOfProcessList.size
                size == 1
            }

            fakeDevice.startClient(pid11, 0, "a.b.c.e", false)
            assertNotNull(fakeDevice.getClient(pid10))
            assertNotNull(fakeDevice.getClient(pid11))
            yieldUntil { listOfProcessList.size == 2 }

            // Note: Depending on how fast FakeAdbServer is, adblib may get one or two
            //       jdwp tracking event
            fakeDevice.stopClient(pid10)
            fakeDevice.stopClient(pid11)
            assertNull(fakeDevice.getClient(pid10))
            assertNull(fakeDevice.getClient(pid11))
        }

        val jdwpTracker = JdwpProcessTracker.create(connectedDevice)
        // Collecting the flow deterministically is a little tricky, as the list of events
        // in the flow depend on how fast FakeAdbServer emits events from the "track-jdwp"
        // event and how fast adblib collects and emits these events in the jdwp tracker
        // flow.
        jdwpTracker.processesFlow.takeWhile { processList ->
            // The goal here is to collect 3 list of processes in `listOfProcessList`
            // * One with a single process
            // * One with 2 processes
            // * One with no processes (after both processes are stopped)
            // The flow itself may emit a variable amount of "no process" lists in the flow,
            // then a variable amount of "1 process" lists in the flow, then a variable
            // amount of "2 processes" lists, then a variable amount of "no process" lists.
            when (listOfProcessList.size) {
                // When the list is empty, only add a non-empty process list, it should be a list
                // of 1 process.
                0 -> {
                    if (processList.isNotEmpty()) {
                        assert(processList.size == 1)
                        listOfProcessList.add(processList)
                    }
                    true
                }
                // When the list has one element, add an element only if the process list
                // contains 2 elements.
                1 -> {
                    // The flow may emit a list of 1 process multiple times, because
                    // FakeAdbServer sometimes emit the same list multiple times
                    if (processList.size == 2) {
                        listOfProcessList.add(processList)
                    }
                    true
                }
                // When the list has 2 elements, wait until we get an empty process list
                // stop collecting at that point.
                2 -> {
                    if (processList.isEmpty()) {
                        // There may be 1 or 2 lists depending on how fast the flow
                        // catches up with the 2 process terminations.
                        listOfProcessList.add(processList)
                        false
                    } else {
                        true
                    }
                }

                else -> {
                    fail("Should not reach")
                    false
                }
            }
        }.collect()

        // Assert: We should have 3 lists: 1 process, 2 processes, empty list.
        assertTrue(listOfProcessList.size == 3)

        // First list has one process
        assertEquals(1, listOfProcessList[0].size)
        assertEquals(listOf(pid10), listOfProcessList[0].map { it.pid }.toList())

        // Second list has 2 processes
        assertEquals(2, listOfProcessList[1].size)
        assertEquals(listOf(pid10, pid11), listOfProcessList[1].map { it.pid }.toList())

        // Last list is empty
        assertEquals(0, listOfProcessList[2].size)

        // Ensure JdwpProcess instances are re-used across flow changes
        assertSame(listOfProcessList[0].first { it.pid == pid10 },
                          listOfProcessList[1].first { it.pid == pid10 })

        val process10 = listOfProcessList[0].first { it.pid == pid10 }
        assertEquals(connectedDevice, process10.device)
        assertEquals(pid10, process10.pid)
        yieldUntil { !process10.scope.isActive }
        assertFalse(process10.scope.isActive)
    }

    @Test
    fun testJdwpProcessTrackerCollectsProcessProperties() = runBlockingWithTimeout {
        val deviceID = "1234"
        val fakeDevice =
            fakeAdb.connectDevice(
                deviceID,
                "test1",
                "test2",
                "model",
                AndroidApiLevel(30), // SDK >= 30 is required for abb_exec feature.
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        val connectedDevice =
            waitForOnlineConnectedDevice(hostServices.session, fakeDevice.deviceId)
        val pid10 = 10
        val pid11 = 11
        fakeDevice.startClient(pid10, 100, "a.b.c", false)
        fakeDevice.startClient(pid11, 101, "a.b.c.e", true)
        val jdwpTracker = JdwpProcessTracker.create(connectedDevice)

        // Act
        val processListFlow = jdwpTracker.processesFlow
        val (process10, process11) = processListFlow
            .mapNotNull { list ->
                if (list.size == 2) {
                    val process10 = list.first { it.pid == pid10 }
                    val process11 = list.first { it.pid == pid11 }
                    yieldUntil {
                        (process10.propertiesFlow.value.processName.getOrNull() != null) &&
                                (process11.propertiesFlow.value.processName.getOrNull() != null)
                    }
                    Pair(process10, process11)
                } else {
                    null
                }
            }.first()

        // Assert
        assertEquals(pid10, process10.pid)
        assertEquals("a.b.c", process10.propertiesFlow.value.processName.getOrNull())
        assertEquals(100, process10.propertiesFlow.value.userId.getOrNull())

        assertEquals(pid11, process11.pid)
        assertEquals("a.b.c.e", process11.propertiesFlow.value.processName.getOrNull())
        assertEquals(101, process11.propertiesFlow.value.userId.getOrNull())
    }

    @Test
    fun testJdwpProcessTrackerFlowStopsWhenDeviceDisconnects(): Unit = runBlockingWithTimeout {
        val deviceID = "1234"
        val fakeDevice =
            fakeAdb.connectDevice(
                deviceID,
                "test1",
                "test2",
                "model",
                AndroidApiLevel(30), // SDK >= 30 is required for abb_exec feature.
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        val connectedDevice =
            waitForOnlineConnectedDevice(hostServices.session, fakeDevice.deviceId)
        val pid10 = 10
        val pid11 = 11

        // Act
        val listOfProcessList = CopyOnWriteArrayList<List<JdwpProcess>>()
        val jdwpTracker = JdwpProcessTracker.create(connectedDevice)
        launch {
            fakeDevice.startClient(pid10, 0, "a.b.c", false)
            fakeDevice.startClient(pid11, 0, "a.b.c.e", false)
            yieldUntil { listOfProcessList.size >= 1 }

            fakeAdb.disconnectDevice(fakeDevice.deviceId)
        }

        jdwpTracker.scope.launch {
            jdwpTracker.processesFlow.collect {
                listOfProcessList.add(it)
            }
        }.join()

        // Assert
        // We don't assert anything, the fact we reached this point means the
        // flow was cancelled when the device was disconnected.
    }

    @Test
    fun testJdwpProcessTrackerFlowIsExceptionTransparent(): Unit = runBlockingWithTimeout {
        val deviceID = "1234"
        val fakeDevice =
            fakeAdb.connectDevice(
                deviceID,
                "test1",
                "test2",
                "model",
                AndroidApiLevel(30), // SDK >= 30 is required for abb_exec feature.
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        val connectedDevice =
            waitForOnlineConnectedDevice(hostServices.session, fakeDevice.deviceId)
        val pid10 = 10

        // Act
        val exception = assertThrows(Exception::class.java) {
            fakeDevice.startClient(pid10, 0, "a.b.c", false)
            val jdwpTracker = JdwpProcessTracker.create(connectedDevice)
            runBlocking {
                jdwpTracker.processesFlow.collect {
                    throw Exception("My Test Exception")
                }
            }
        }

        // Assert
        assertEquals(exception.message, "My Test Exception")
    }

    @Test
    fun testJdwpProcessTrackerFlowICanBeCancelled(): Unit = runBlockingWithTimeout {
        val deviceID = "1234"
        val fakeDevice =
            fakeAdb.connectDevice(
                deviceID,
                "test1",
                "test2",
                "model",
                AndroidApiLevel(30), // SDK >= 30 is required for abb_exec feature.
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        val connectedDevice =
            waitForOnlineConnectedDevice(hostServices.session, fakeDevice.deviceId)
        val pid10 = 10

        // Act
        val exception = assertThrows(CancellationException::class.java) {
            fakeDevice.startClient(pid10, 0, "a.b.c", false)
            val jdwpTracker = JdwpProcessTracker.create(connectedDevice)
            runBlocking {
                jdwpTracker.processesFlow.collect {
                    cancel("My Test Exception")
                }
            }
        }

        // Assert
        assertEquals(exception.message, "My Test Exception")
    }

    @Test
    fun testJdwpProcessTrackerExposesUnsupportedPropertiesForApi17AndBelow() =
        runBlockingWithTimeout {
            val deviceID = "1234"
            val fakeDevice =
                fakeAdb.connectDevice(
                    deviceID,
                    "test1",
                    "test2",
                    "model",
                    AndroidApiLevel(17),
                    DeviceState.HostConnectionType.USB
                )
            fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
            val connectedDevice =
                waitForOnlineConnectedDevice(hostServices.session, fakeDevice.deviceId)
            val pid10 = 10
            fakeDevice.startClient(pid10, 100, "a.b.c", false)
            val jdwpTracker = JdwpProcessTracker.create(connectedDevice)

            // Act
            val process = jdwpTracker.processesFlow
                .filter { it.isNotEmpty() }
                .map { it.first() }
                .first()
            process.propertiesFlow.takeWhile { it.processName.isEmpty }
                .collect {
                    // We didn't receive HELO packet with the `processName` yet
                    assertTrue(process.propertiesFlow.value.processName.isEmpty)
                    assertTrue(process.propertiesFlow.value.vmIdentifier.isEmpty)
                    assertTrue(process.propertiesFlow.value.instructionSet.isEmpty)
                    assertTrue(process.propertiesFlow.value.instructionSetDescription.isEmpty)
                    assertTrue(process.propertiesFlow.value.jvmFlags.isEmpty)
                    assertTrue(process.propertiesFlow.value.isNativeDebuggable.isEmpty)
                    assertTrue(process.propertiesFlow.value.packageName.isEmpty)
                    assertTrue(process.propertiesFlow.value.packageNames.isEmpty)
                }

            // Assert
            // Supported properties
            assertEquals(pid10, process.pid)
            assertEquals("a.b.c", process.propertiesFlow.value.processName.getOrNull())
            assertEquals("FakeVM", process.propertiesFlow.value.vmIdentifier.getOrNull())
            // Unsupported properties
            assertTrue(process.propertiesFlow.value.instructionSet.isError)
            assertTrue(process.propertiesFlow.value.instructionSetDescription.isError)
            assertTrue(process.propertiesFlow.value.jvmFlags.isError)
            assertTrue(process.propertiesFlow.value.isNativeDebuggable.isError)
            assertTrue(process.propertiesFlow.value.packageName.isError)
            assertTrue(process.propertiesFlow.value.packageNames.isError)
        }

    @Test
    fun testJdwpProcessTrackerExposesUnsupportedPropertiesForApi18To20() =
        runBlockingWithTimeout {
            val deviceID = "1234"
            val fakeDevice =
                fakeAdb.connectDevice(
                    deviceID,
                    "test1",
                    "test2",
                    "model",
                    AndroidApiLevel(18),
                    DeviceState.HostConnectionType.USB
                )
            fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
            val connectedDevice =
                waitForOnlineConnectedDevice(hostServices.session, fakeDevice.deviceId)
            val pid10 = 10
            fakeDevice.startClient(pid10, 100, "a.b.c", false)
            val jdwpTracker = JdwpProcessTracker.create(connectedDevice)

            // Act
            val process = jdwpTracker.processesFlow
                .filter { it.isNotEmpty() }
                .map { it.first() }
                .first()
            process.propertiesFlow.takeWhile { it.processName.isEmpty }
                .collect {
                    // We didn't receive HELO packet with the `processName` yet
                    assertTrue(process.propertiesFlow.value.processName.isEmpty)
                    assertTrue(process.propertiesFlow.value.vmIdentifier.isEmpty)
                    assertTrue(process.propertiesFlow.value.instructionSet.isEmpty)
                    assertTrue(process.propertiesFlow.value.instructionSetDescription.isEmpty)
                    assertTrue(process.propertiesFlow.value.jvmFlags.isEmpty)
                    assertTrue(process.propertiesFlow.value.isNativeDebuggable.isEmpty)
                    assertTrue(process.propertiesFlow.value.packageName.isEmpty)
                    assertTrue(process.propertiesFlow.value.packageNames.isEmpty)
                }

            // Assert
            // Supported properties
            assertEquals(pid10, process.pid)
            assertEquals("a.b.c", process.propertiesFlow.value.processName.getOrNull())
            assertEquals("FakeVM", process.propertiesFlow.value.vmIdentifier.getOrNull())
            // Unsupported properties
            assertTrue(process.propertiesFlow.value.instructionSet.isError)
            assertTrue(process.propertiesFlow.value.instructionSetDescription.isError)
            assertTrue(process.propertiesFlow.value.jvmFlags.isError)
            assertTrue(process.propertiesFlow.value.isNativeDebuggable.isError)
            assertTrue(process.propertiesFlow.value.packageName.isError)
            assertTrue(process.propertiesFlow.value.packageNames.isError)
        }

    @Test
    fun testJdwpProcessTrackerExposesUnsupportedPropertiesForApi21To23() =
        runBlockingWithTimeout {
            val deviceID = "1234"
            val fakeDevice =
                fakeAdb.connectDevice(
                    deviceID,
                    "test1",
                    "test2",
                    "model",
                    AndroidApiLevel(21),
                    DeviceState.HostConnectionType.USB
                )
            fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
            val connectedDevice =
                waitForOnlineConnectedDevice(hostServices.session, fakeDevice.deviceId)
            val pid10 = 10
            fakeDevice.startClient(pid10, 100, "a.b.c", false)
            val jdwpTracker = JdwpProcessTracker.create(connectedDevice)

            // Act
            val process = jdwpTracker.processesFlow
                .filter { it.isNotEmpty() }
                .map { it.first() }
                .first()
            process.propertiesFlow.takeWhile { it.processName.isEmpty }
                .collect {
                    // We didn't receive HELO packet with the `processName` yet
                    assertTrue(process.propertiesFlow.value.processName.isEmpty)
                    assertTrue(process.propertiesFlow.value.vmIdentifier.isEmpty)
                    assertTrue(process.propertiesFlow.value.instructionSet.isEmpty)
                    assertTrue(process.propertiesFlow.value.instructionSetDescription.isEmpty)
                    assertTrue(process.propertiesFlow.value.jvmFlags.isEmpty)
                    assertTrue(process.propertiesFlow.value.isNativeDebuggable.isEmpty)
                    assertTrue(process.propertiesFlow.value.packageName.isEmpty)
                    assertTrue(process.propertiesFlow.value.packageNames.isEmpty)
                }

            // Assert
            // Supported properties
            assertEquals(pid10, process.pid)
            assertEquals("a.b.c", process.propertiesFlow.value.processName.getOrNull())
            assertEquals("FakeVM", process.propertiesFlow.value.vmIdentifier.getOrNull())
            assertEquals(InstructionSet.X86_64, process.propertiesFlow.value.instructionSet.getOrNull())
            assertEquals("64-bit (x86_64)", process.propertiesFlow.value.instructionSetDescription.getOrNull())
            assertEquals("CheckJNI=true", process.propertiesFlow.value.jvmFlags.getOrNull())
            // Unsupported properties
            assertTrue(process.propertiesFlow.value.isNativeDebuggable.isError)
            assertTrue(process.propertiesFlow.value.packageName.isError)
            assertTrue(process.propertiesFlow.value.packageNames.isError)
        }

    @Test
    fun testJdwpProcessTrackerExposesUnsupportedPropertiesForApi24To29() =
        runBlockingWithTimeout {
            val deviceID = "1234"
            val fakeDevice =
                fakeAdb.connectDevice(
                    deviceID,
                    "test1",
                    "test2",
                    "model",
                    AndroidApiLevel(24),
                    DeviceState.HostConnectionType.USB
                )
            fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
            val connectedDevice =
                waitForOnlineConnectedDevice(hostServices.session, fakeDevice.deviceId)
            val pid10 = 10
            fakeDevice.startClient(pid10, 100, "a.b.c", false)
            val jdwpTracker = JdwpProcessTracker.create(connectedDevice)

            // Act
            val process = jdwpTracker.processesFlow
                .filter { it.isNotEmpty() }
                .map { it.first() }
                .first()
            process.propertiesFlow.takeWhile { it.processName.isEmpty }
                .collect {
                    // We didn't receive HELO packet with the `processName` yet
                    assertTrue(process.propertiesFlow.value.processName.isEmpty)
                    assertTrue(process.propertiesFlow.value.vmIdentifier.isEmpty)
                    assertTrue(process.propertiesFlow.value.instructionSet.isEmpty)
                    assertTrue(process.propertiesFlow.value.instructionSetDescription.isEmpty)
                    assertTrue(process.propertiesFlow.value.jvmFlags.isEmpty)
                    assertTrue(process.propertiesFlow.value.isNativeDebuggable.isEmpty)
                    assertTrue(process.propertiesFlow.value.packageName.isEmpty)
                    assertTrue(process.propertiesFlow.value.packageNames.isEmpty)
                }

            // Assert
            // Supported properties
            assertEquals(pid10, process.pid)
            assertEquals("a.b.c", process.propertiesFlow.value.processName.getOrNull())
            assertEquals("FakeVM", process.propertiesFlow.value.vmIdentifier.getOrNull())
            assertEquals(InstructionSet.X86_64, process.propertiesFlow.value.instructionSet.getOrNull())
            assertEquals("64-bit (x86_64)", process.propertiesFlow.value.instructionSetDescription.getOrNull())
            assertEquals("CheckJNI=true", process.propertiesFlow.value.jvmFlags.getOrNull())
            assertEquals(false, process.propertiesFlow.value.isNativeDebuggable.getOrNull())

            // Unsupported properties
            assertTrue(process.propertiesFlow.value.packageName.isError)
            assertTrue(process.propertiesFlow.value.packageNames.isError)
        }

    @Test
    fun testJdwpProcessTrackerExposesUnsupportedPropertiesForApi30AndAbove() =
        runBlockingWithTimeout {
            val deviceID = "1234"
            val fakeDevice =
                fakeAdb.connectDevice(
                    deviceID,
                    "test1",
                    "test2",
                    "model",
                    AndroidApiLevel(30),
                    DeviceState.HostConnectionType.USB
                )
            fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
            val connectedDevice =
                waitForOnlineConnectedDevice(hostServices.session, fakeDevice.deviceId)
            val pid10 = 10
            fakeDevice.startClient(pid10, 100, "a.b.c", false)
            val jdwpTracker = JdwpProcessTracker.create(connectedDevice)

            // Act
            val process = jdwpTracker.processesFlow
                .filter { it.isNotEmpty() }
                .map { it.first() }
                .first()
            process.propertiesFlow.takeWhile { it.processName.isEmpty }
                .collect {
                    // We didn't receive HELO packet with the `processName` yet
                    assertTrue(process.propertiesFlow.value.processName.isEmpty)
                    assertTrue(process.propertiesFlow.value.vmIdentifier.isEmpty)
                    assertTrue(process.propertiesFlow.value.instructionSet.isEmpty)
                    assertTrue(process.propertiesFlow.value.instructionSetDescription.isEmpty)
                    assertTrue(process.propertiesFlow.value.jvmFlags.isEmpty)
                    assertTrue(process.propertiesFlow.value.isNativeDebuggable.isEmpty)
                    assertTrue(process.propertiesFlow.value.packageName.isEmpty)
                    assertTrue(process.propertiesFlow.value.packageNames.isEmpty)
                }

            // Assert
            // Supported properties
            assertEquals(pid10, process.pid)
            assertEquals("a.b.c", process.propertiesFlow.value.processName.getOrNull())
            assertEquals("FakeVM", process.propertiesFlow.value.vmIdentifier.getOrNull())
            assertEquals("FakeVM", process.propertiesFlow.value.vmIdentifier.getOrNull())
            assertEquals(InstructionSet.X86_64, process.propertiesFlow.value.instructionSet.getOrNull())
            assertEquals("64-bit (x86_64)", process.propertiesFlow.value.instructionSetDescription.getOrNull())
            assertEquals("CheckJNI=true", process.propertiesFlow.value.jvmFlags.getOrNull())
            assertEquals(false, process.propertiesFlow.value.isNativeDebuggable.getOrNull())
            assertEquals("a.b.c", process.propertiesFlow.value.packageName.getOrNull())
            assertEquals(listOf("a.b.c"), process.propertiesFlow.value.packageNames.getOrNull())
        }
}
