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
package com.android.adblib.tools.debugging.processinventory.impl

import com.android.adblib.AdbSession
import com.android.adblib.ConnectedDevice
import com.android.adblib.InstructionSet
import com.android.adblib.serialNumber
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils
import com.android.adblib.testingutils.CoroutineTestUtils.waitNonNull
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.tools.debugging.JdwpProcessProperties
import com.android.adblib.tools.debugging.OptionalValue
import com.android.adblib.tools.debugging.getOrDefault
import com.android.adblib.tools.debugging.getOrNull
import com.android.adblib.tools.debugging.processinventory.AdbLibToolsProcessInventoryServerProperties
import com.android.adblib.tools.debugging.processinventory.ProcessInventoryServerConnection
import com.android.adblib.tools.debugging.processinventory.server.ProcessInventoryServerConfiguration
import com.android.adblib.tools.testutils.AdbLibToolsTestBase
import com.android.adblib.tools.testutils.areAllPropertiesInitialized
import com.android.adblib.tools.testutils.waitForOnlineConnectedDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFalse

class ProcessInventoryServerConnectionTest : AdbLibToolsTestBase() {


    @Test
    fun testProcessListIsEmptyWhenStarting(): Unit = CoroutineTestUtils.runBlockingWithTimeout {
        // Prepare
        setHostPropertyValue(
            session.host,
            AdbLibToolsProcessInventoryServerProperties.LOCAL_PORT_V1,
            findFreeTcpPort()
        )
        val serverConnection = createServerConnection(session)
        val deviceState = addFakeDevice(fakeAdb, api = 32)
        val device = waitForOnlineConnectedDevice(session, deviceState.deviceId)

        // Act
        val processList = serverConnection.withConnectionForDevice(device) {
            processListStateFlow.first()
        }

        // Assert
        assertTrue(processList.isEmpty())
    }

    @Test
    fun testBlockIsCancelledWhenDeviceDisconnects(): Unit = CoroutineTestUtils.runBlockingWithTimeout {
        // Prepare
        setHostPropertyValue(
            session.host,
            AdbLibToolsProcessInventoryServerProperties.LOCAL_PORT_V1,
            findFreeTcpPort()
        )
        val serverConnection = createServerConnection(session)
        val deviceState = addFakeDevice(fakeAdb, api = 32)
        val device = waitForOnlineConnectedDevice(session, deviceState.deviceId)

        // Act
        val started = CompletableDeferred<Unit>()
        val job = async {
            serverConnection.withConnectionForDevice(device) {
                processListStateFlow.collect {
                    started.complete(Unit)
                }
            }
        }
        started.await()
        fakeAdb.disconnectDevice(device.serialNumber)
        val result  = runCatching { job.await() }

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun testProcessUpdateUpdatesAllProperties(): Unit = CoroutineTestUtils.runBlockingWithTimeout {
        // Prepare
        setHostPropertyValue(
            session.host,
            AdbLibToolsProcessInventoryServerProperties.LOCAL_PORT_V1,
            findFreeTcpPort()
        )
        val serverConnection = createServerConnection(session)
        val deviceState = addFakeDevice(fakeAdb, api = 32)
        val device = waitForOnlineConnectedDevice(session, deviceState.deviceId)

        // Act
        val processListSnapshots = CopyOnWriteArrayList<List<JdwpProcessProperties>>()
        val job = async {
            serverConnection.withConnectionForDevice(device) {
                processListStateFlow.collect {
                    processListSnapshots.add(it)
                }
            }
        }

        val localProperties = JdwpProcessProperties(
            pid = 10,
            processName = OptionalValue.of("Foo"),
            packageName = OptionalValue.of("Bar"),
            userId = OptionalValue.of(5),
            vmIdentifier = OptionalValue.of("vm"),
            instructionSet = OptionalValue.of(InstructionSet.X86),
            jvmFlags = OptionalValue.of("flags"),
            isNativeDebuggable = OptionalValue.of(true),
            isWaitingForDebugger = OptionalValue.of(true),
            features = OptionalValue.of(listOf("feat1", "feat2")),
        )
        serverConnection.withConnectionForDevice(device) {
            sendProcessProperties(localProperties)
        }

        yieldUntil {
            processListSnapshots.run {
                isNotEmpty() &&
                        last().size == 1 &&
                        last().first().areAllPropertiesInitialized() &&
                        last().first().isWaitingForDebugger.getOrDefault(false)
            }
        }
        job.cancel()

        // Assert
        val lastList = processListSnapshots.last()
        assertEquals(1, lastList.size)
        assertEquals(localProperties, lastList[0])
    }

    @Test
    fun testProcessUpdatesAreAccumulatedToStateFlow(): Unit = CoroutineTestUtils.runBlockingWithTimeout {
        // Prepare
        setHostPropertyValue(
            session.host,
            AdbLibToolsProcessInventoryServerProperties.LOCAL_PORT_V1,
            findFreeTcpPort()
        )
        val serverConnection = createServerConnection(session)
        val deviceState = addFakeDevice(fakeAdb, api = 32)
        val device = waitForOnlineConnectedDevice(session, deviceState.deviceId)

        // Act
        val processListSnapshots = CopyOnWriteArrayList<List<JdwpProcessProperties>>()
        val job = async {
            serverConnection.withConnectionForDevice(device) {
                processListStateFlow.collect {
                    processListSnapshots.add(it)
                }
            }
        }
        serverConnection.withConnectionForDevice(device) {
            sendProcessProperties(JdwpProcessProperties(10, processName = OptionalValue.of("Foo")))
            sendProcessProperties(JdwpProcessProperties(11, processName = OptionalValue.of("Foo")))
            sendProcessProperties(JdwpProcessProperties(12, processName = OptionalValue.of("Foo")))
        }

        yieldUntil {
            processListSnapshots.isNotEmpty() && processListSnapshots.last().size == 3
        }
        job.cancel()

        // Assert
        val lastList = processListSnapshots.last()
        assertEquals(3, lastList.size)
        assertNotNull(lastList.firstOrNull { it.pid == 10 })
        assertNotNull(lastList.firstOrNull { it.pid == 11 })
        assertNotNull(lastList.firstOrNull { it.pid == 12 })
    }

    @Test
    fun testWithConnectionForDeviceIsTransparentToExceptions(): Unit = CoroutineTestUtils.runBlockingWithTimeout {
        // Prepare
        setHostPropertyValue(
            session.host,
            AdbLibToolsProcessInventoryServerProperties.LOCAL_PORT_V1,
            findFreeTcpPort()
        )
        val serverConnection = createServerConnection(session)
        val deviceState = addFakeDevice(fakeAdb, api = 32)
        val device = waitForOnlineConnectedDevice(session, deviceState.deviceId)

        // Act
        exceptionRule.expect(Exception::class.java)
        exceptionRule.expectMessage("Foo")
        serverConnection.withConnectionForDevice(device) {
            processListStateFlow.collect {
                throw Exception("Foo")
            }
        }

        // Assert
        @Suppress("UNREACHABLE_CODE")
        Assert.fail("Should not reach")
    }

    @Test
    fun testWithConnectionForDeviceIsTransparentToCancellation(): Unit = CoroutineTestUtils.runBlockingWithTimeout {
        // Prepare
        setHostPropertyValue(
            session.host,
            AdbLibToolsProcessInventoryServerProperties.LOCAL_PORT_V1,
            findFreeTcpPort()
        )
        val serverConnection = createServerConnection(session)
        val deviceState = addFakeDevice(fakeAdb, api = 32)
        val device = waitForOnlineConnectedDevice(session, deviceState.deviceId)

        // Act
        exceptionRule.expect(CancellationException::class.java)
        exceptionRule.expectMessage("Foo")
        serverConnection.withConnectionForDevice(device) {
            processListStateFlow.collect {
                cancel("Foo")
            }
        }

        // Assert
        @Suppress("UNREACHABLE_CODE")
        Assert.fail("Should not reach")
    }

    @Test
    fun testProcessUpdatesAreForwardedToAllClients(): Unit = CoroutineTestUtils.runBlockingWithTimeout {
        // Prepare
        setHostPropertyValue(
            session.host,
            AdbLibToolsProcessInventoryServerProperties.LOCAL_PORT_V1,
            findFreeTcpPort()
        )
        val clientCount = 10
        val sessions = listOf(session) + (2..clientCount).map {
            registerCloseable(fakeAdbRule.createTestAdbSession(session.host))
        }
        val serverConnections = sessions.map { session ->
            createServerConnection(session)
        }
        val deviceState = addFakeDevice(fakeAdb, api = 32)
        val devices = sessions.map { session ->
            async {
                waitForOnlineConnectedDevice(session, deviceState.deviceId)
            }
        }.awaitAll()

        // Act: One connection sends updates, all connections should receive them
        val processListFlows = sessions.map {
            MutableStateFlow<List<JdwpProcessProperties>>(emptyList())
        }
        val collectorJobs = List(sessions.size) { index ->
            async {
                serverConnections[index].withConnectionForDevice(devices[index]) {
                    this.processListStateFlow.collect {
                        processListFlows[index].value = it
                    }
                }
            }
        }

        // Send incremental updates, to make sure the server incrementally set fields
        var localProperties = JdwpProcessProperties(pid = 10)
        sendAndWaitForUpdate(serverConnections.first(), devices.first(), processListFlows, localProperties) {
            it.pid == 10
        }.also {
            assertEquals(10, it.pid)
            assertNull(it.processName.getOrNull())
            assertNull(it.packageName.getOrNull())
            assertNull(it.userId.getOrNull())
            assertNull(it.vmIdentifier.getOrNull())
            assertNull(it.instructionSetDescription.getOrNull())
            assertNull(it.instructionSet.getOrNull())
            assertNull(it.jvmFlags.getOrNull())
            @Suppress("DEPRECATION")
            assertFalse(it.isNativeDebuggable.getOrDefault(false))
            assertFalse(it.isWaitingForDebugger.getOrDefault(false))
            assertTrue(it.features.getOrDefault(emptyList()).isEmpty())
            assertFalse(it.areAllPropertiesInitialized())
        }

        localProperties = localProperties.copy(processName = OptionalValue.of("Foo"))
        sendAndWaitForUpdate(serverConnections.first(), devices.first(), processListFlows, localProperties) {
            it.pid == 10 && it.processName.getOrNull() == "Foo"
        }.also {
            assertEquals(10, it.pid)
            assertEquals("Foo", it.processName.getOrNull())
            assertNull(it.packageName.getOrNull())
            assertNull(it.userId.getOrNull())
            assertNull(it.vmIdentifier.getOrNull())
            assertNull(it.instructionSetDescription.getOrNull())
            assertNull(it.instructionSet.getOrNull())
            assertNull(it.jvmFlags.getOrNull())
            @Suppress("DEPRECATION")
            assertFalse(it.isNativeDebuggable.getOrDefault(false))
            assertFalse(it.isWaitingForDebugger.getOrDefault(false))
            assertTrue(it.features.getOrDefault(emptyList()).isEmpty())
            assertFalse(it.areAllPropertiesInitialized())
        }

        localProperties = localProperties.copy(packageName = OptionalValue.of("Bar"))
        sendAndWaitForUpdate(serverConnections.first(), devices.first(), processListFlows, localProperties) {
            it.pid == 10 && it.packageName.getOrNull() == "Bar"
        }.also {
            assertEquals(10, it.pid)
            assertEquals("Foo", it.processName.getOrNull())
            assertEquals("Bar", it.packageName.getOrNull())
            assertNull(it.userId.getOrNull())
            assertNull(it.vmIdentifier.getOrNull())
            assertNull(it.instructionSetDescription.getOrNull())
            assertNull(it.instructionSet.getOrNull())
            assertNull(it.jvmFlags.getOrNull())
            @Suppress("DEPRECATION")
            assertFalse(it.isNativeDebuggable.getOrDefault(false))
            assertFalse(it.isWaitingForDebugger.getOrDefault(false))
            assertTrue(it.features.getOrDefault(emptyList()).isEmpty())
            assertFalse(it.areAllPropertiesInitialized())
        }

        localProperties = localProperties.copy(userId = OptionalValue.of(12))
        sendAndWaitForUpdate(serverConnections.first(), devices.first(), processListFlows, localProperties) {
            it.pid == 10 && it.userId.getOrNull() == 12
        }.also {
            assertEquals(10, it.pid)
            assertEquals("Foo", it.processName.getOrNull())
            assertEquals("Bar", it.packageName.getOrNull())
            assertEquals(12, it.userId.getOrNull())
            assertNull(it.vmIdentifier.getOrNull())
            assertNull(it.instructionSetDescription.getOrNull())
            assertNull(it.instructionSet.getOrNull())
            assertNull(it.jvmFlags.getOrNull())
            @Suppress("DEPRECATION")
            assertFalse(it.isNativeDebuggable.getOrDefault(false))
            assertFalse(it.isWaitingForDebugger.getOrDefault(false))
            assertTrue(it.features.getOrDefault(emptyList()).isEmpty())
            assertFalse(it.areAllPropertiesInitialized())
        }

        localProperties = localProperties.copy(vmIdentifier = OptionalValue.of("vm"))
        sendAndWaitForUpdate(serverConnections.first(), devices.first(), processListFlows, localProperties) {
            it.pid == 10 && it.vmIdentifier.getOrNull() == "vm"
        }.also {
            assertEquals(10, it.pid)
            assertEquals("Foo", it.processName.getOrNull())
            assertEquals("Bar", it.packageName.getOrNull())
            assertEquals(12, it.userId.getOrNull())
            assertEquals("vm", it.vmIdentifier.getOrNull())
            assertNull(it.instructionSetDescription.getOrNull())
            assertNull(it.instructionSet.getOrNull())
            assertNull(it.jvmFlags.getOrNull())
            @Suppress("DEPRECATION")
            assertFalse(it.isNativeDebuggable.getOrDefault(false))
            assertFalse(it.isWaitingForDebugger.getOrDefault(false))
            assertTrue(it.features.getOrDefault(emptyList()).isEmpty())
            assertFalse(it.areAllPropertiesInitialized())
        }

        localProperties = localProperties.copy(instructionSet = OptionalValue.of(InstructionSet.X86))
        sendAndWaitForUpdate(serverConnections.first(), devices.first(), processListFlows, localProperties) {
            it.pid == 10 && it.instructionSetDescription.getOrNull() == "32-bit (x86)"
        }.also {
            assertEquals(10, it.pid)
            assertEquals("Foo", it.processName.getOrNull())
            assertEquals("Bar", it.packageName.getOrNull())
            assertEquals(12, it.userId.getOrNull())
            assertEquals("vm", it.vmIdentifier.getOrNull())
            assertEquals("32-bit (x86)", it.instructionSetDescription.getOrNull())
            assertEquals(InstructionSet.X86, it.instructionSet.getOrNull())
            assertNull(it.jvmFlags.getOrNull())
            @Suppress("DEPRECATION")
            assertFalse(it.isNativeDebuggable.getOrDefault(false))
            assertFalse(it.isWaitingForDebugger.getOrDefault(false))
            assertTrue(it.features.getOrDefault(emptyList()).isEmpty())
            assertFalse(it.areAllPropertiesInitialized())
        }

        localProperties = localProperties.copy(jvmFlags = OptionalValue.of("FooBar"))
        sendAndWaitForUpdate(serverConnections.first(), devices.first(), processListFlows, localProperties) {
            it.pid == 10 && it.jvmFlags.getOrNull() == "FooBar"
        }.also {
            assertEquals(10, it.pid)
            assertEquals("Foo", it.processName.getOrNull())
            assertEquals("Bar", it.packageName.getOrNull())
            assertEquals(12, it.userId.getOrNull())
            assertEquals("vm", it.vmIdentifier.getOrNull())
            assertEquals("32-bit (x86)", it.instructionSetDescription.getOrNull())
            assertEquals(InstructionSet.X86, it.instructionSet.getOrNull())
            assertEquals("FooBar", it.jvmFlags.getOrNull())
            @Suppress("DEPRECATION")
            assertFalse(it.isNativeDebuggable.getOrDefault(false))
            assertFalse(it.isWaitingForDebugger.getOrDefault(false))
            assertTrue(it.features.getOrDefault(emptyList()).isEmpty())
            assertFalse(it.areAllPropertiesInitialized())
        }

        localProperties = localProperties.copy(isNativeDebuggable = OptionalValue.of(true))
        sendAndWaitForUpdate(serverConnections.first(), devices.first(), processListFlows, localProperties) {
            @Suppress("DEPRECATION")
            it.pid == 10 && it.isNativeDebuggable.getOrDefault(false)
        }.also {
            assertEquals(10, it.pid)
            assertEquals("Foo", it.processName.getOrNull())
            assertEquals("Bar", it.packageName.getOrNull())
            assertEquals(12, it.userId.getOrNull())
            assertEquals("vm", it.vmIdentifier.getOrNull())
            assertEquals("32-bit (x86)", it.instructionSetDescription.getOrNull())
            assertEquals(InstructionSet.X86, it.instructionSet.getOrNull())
            assertEquals("FooBar", it.jvmFlags.getOrNull())
            @Suppress("DEPRECATION")
            assertTrue(it.isNativeDebuggable.getOrDefault(false))
            assertFalse(it.isWaitingForDebugger.getOrDefault(false))
            assertTrue(it.features.getOrDefault(emptyList()).isEmpty())
            assertFalse(it.areAllPropertiesInitialized())
        }

        localProperties = localProperties.copy(isWaitingForDebugger = OptionalValue.of(true))
        sendAndWaitForUpdate(serverConnections.first(), devices.first(), processListFlows, localProperties) {
            it.pid == 10 && it.isWaitingForDebugger.getOrDefault(false)
        }.also {
            assertEquals(10, it.pid)
            assertEquals("Foo", it.processName.getOrNull())
            assertEquals("Bar", it.packageName.getOrNull())
            assertEquals(12, it.userId.getOrNull())
            assertEquals("vm", it.vmIdentifier.getOrNull())
            assertEquals("32-bit (x86)", it.instructionSetDescription.getOrNull())
            assertEquals(InstructionSet.X86, it.instructionSet.getOrNull())
            assertEquals("FooBar", it.jvmFlags.getOrNull())
            @Suppress("DEPRECATION")
            assertTrue(it.isNativeDebuggable.getOrDefault(false))
            assertTrue(it.isWaitingForDebugger.getOrDefault(false))
            assertTrue(it.features.getOrDefault(emptyList()).isEmpty())
            assertFalse(it.areAllPropertiesInitialized())
        }

        localProperties = localProperties.copy(features = OptionalValue.of(listOf("f1", "f2", "f3")))
        sendAndWaitForUpdate(serverConnections.first(), devices.first(), processListFlows, localProperties) {
            it.pid == 10 && it.features.getOrDefault(emptyList()) == listOf("f1", "f2", "f3")
        }.also {
            assertEquals(10, it.pid)
            assertEquals("Foo", it.processName.getOrNull())
            assertEquals("Bar", it.packageName.getOrNull())
            assertEquals(12, it.userId.getOrNull())
            assertEquals("vm", it.vmIdentifier.getOrNull())
            assertEquals("32-bit (x86)", it.instructionSetDescription.getOrNull())
            assertEquals(InstructionSet.X86, it.instructionSet.getOrNull())
            assertEquals("FooBar", it.jvmFlags.getOrNull())
            @Suppress("DEPRECATION")
            assertTrue(it.isNativeDebuggable.getOrDefault(false))
            assertTrue(it.isWaitingForDebugger.getOrDefault(false))
            assertEquals(listOf("f1", "f2", "f3"), it.features.getOrDefault(emptyList()))
            assertTrue(it.areAllPropertiesInitialized())
        }

        collectorJobs.forEach {
            it.cancel("Cancellation from test")
            it.join()
        }

        // Assert
        processListFlows.map { it.value }.forEach { list ->
            assertEquals(1, list.size)
            assertEquals(10, list.first().pid)
            assertEquals("Foo", list.first().processName.getOrNull())
            assertEquals("Bar", list.first().packageName.getOrNull())
        }
    }

    private suspend fun sendAndWaitForUpdate(
        serverConnection: ProcessInventoryServerConnection,
        device: ConnectedDevice,
        processListFlows: List<MutableStateFlow<List<JdwpProcessProperties>>>,
        localProperties: JdwpProcessProperties,
        predicate: (JdwpProcessProperties) -> Boolean
    ): JdwpProcessProperties {
        serverConnection.withConnectionForDevice(device) {
            sendProcessProperties(localProperties)
        }

        return waitNonNull {
            coroutineScope {
                // Wait for all flows of lists to have any element in their list
                // matching `predicate`
                val asyncLists = processListFlows.map { flow ->
                    async {
                        flow.first { list ->
                            list.any {
                                predicate(it)
                            }
                        }
                    }
                }
                val lists = asyncLists.awaitAll()

                // Return the first one (this should always succeed given `allDone` is `true`)
                lists.first().first { predicate(it) }
            }
        }
    }

    private fun createServerConnection(session: AdbSession): ProcessInventoryServerConnection {
        val config = object : ProcessInventoryServerConfiguration {
            override val clientDescription: String = "foo"
            override val serverDescription: String = "bar"
        }
        return registerCloseable(ProcessInventoryServerConnection.create(session, config))
    }

    private suspend fun findFreeTcpPort(): Int {
        val session = registerCloseable(FakeAdbSession())
        val freePort = session.channelFactory.createServerSocket().use {
            it.bind(InetSocketAddress(0)).port
        }
        return freePort
    }
}
