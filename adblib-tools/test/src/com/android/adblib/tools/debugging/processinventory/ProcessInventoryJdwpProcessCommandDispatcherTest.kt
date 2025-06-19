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
package com.android.adblib.tools.debugging.processinventory

import com.android.adblib.AdbSession
import com.android.adblib.SOCKET_CONNECT_TIMEOUT_MS
import com.android.adblib.serialNumber
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CloseablesRule
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.FakeAdbServerProvider
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.tools.AdbLibToolsProperties
import com.android.adblib.tools.debugging.ExternalJdwpProcessCommandDispatcher
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.externalJdwpProcessCommandDispatcherFactoryList
import com.android.adblib.tools.debugging.externalJdwpProcessCommandDispatcherList
import com.android.adblib.tools.debugging.jdwpProcessTracker
import com.android.adblib.tools.debugging.processinventory.server.ProcessInventoryServerConfiguration
import com.android.adblib.tools.testutils.waitForOnlineConnectedDevice
import com.android.fakeadbserver.ClientState
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.assertEquals

class ProcessInventoryJdwpProcessCommandDispatcherTest {

    @JvmField
    @Rule
    val fakeAdbRule = FakeAdbServerProviderRule {
        installDefaultCommandHandlers()
    }

    @JvmField
    @Rule
    val closeables = CloseablesRule()

    @Test
    fun testFactoryCanBeEnabledAndDisabled(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = fakeAdbRule.adbSession
        val server = ProcessInventoryServerConnection.create(session, TestServerConfig())
        val pid1 = 20
        val fakeDevice = fakeAdbRule.fakeAdb.addSampleDevice(apiLevel = 30)
        fakeDevice.addSampleJdwpProcess(pid1)
        val device = session.waitForOnlineConnectedDevice(fakeDevice.deviceId)
        val jdwpProcess = device.jdwpProcessTracker.processesFlow.mapNotNull { processList ->
            processList.firstOrNull { it.pid == pid1 }
        }.first()

        var enabled = true
        session.installProcessInventoryJdwpProcessCommandDispatcherFactory(
            server,
            enabled = { enabled }
        )

        // Act
        enabled = true
        val externalCollectorList1 = session.externalJdwpProcessCommandDispatcherFactoryList.mapNotNull {
            it.create(jdwpProcess)
        }

        enabled = false
        val externalCollectorList2 = session.externalJdwpProcessCommandDispatcherFactoryList.mapNotNull {
            it.create(jdwpProcess)
        }

        // Assert
        assertEquals(1, externalCollectorList1.size)
        assertEquals(0, externalCollectorList2.size)
    }

    @Test
    fun testProcessCommandsAreDispatchedToAllClients(): Unit = runBlockingWithTimeout {
        fakeAdbRule.host.setPropertyValue(
            AdbLibToolsProcessInventoryServerProperties.LOCAL_PORT_V1,
            findFreeTcpPort()
        )

        // Prepare
        // Create 2 adblib sessions for a single fake adb server, install a
        // ProcessInventoryServer on both sessions, start collecting properties from both
        // sessions with a long timeout for keeping JDWP sessions open (one of the 2 adblib
        // sessions is stuck waiting for the other one when trying to collect properties),
        // check that a "ResumeJdwpProcess" command is processed correctly by one of the session
        // (the one that holds onto the JDWP session)
        fakeAdbRule.host.setPropertyValue(
            AdbLibToolsProperties.PROCESS_PROPERTIES_READ_TIMEOUT,
            Duration.ofSeconds(5)
        )

        // Act
        val processList = testSendProcessCommandWithMultipleSessions(sequence {
            val session1 = fakeAdbRule.adbSession
            val session2 = createSessionClone(fakeAdbRule)
            session1.installTestProcessInventoryServer()
            session2.installTestProcessInventoryServer()
            yield(session1)
            yield(session2)
        }) { jdwpProcessList ->
            // Test sanity check: verify that the JDWP process was properly set up, i.e.
            // it is in the `isWaitingForDebugger` state
            jdwpProcessList.forEach { jdwpProcess ->
                val clientState = jdwpProcess.run {
                    fakeAdbRule.fakeAdb.device(device.serialNumber).getClient(pid)!!
                }
                Assert.assertTrue(clientState.waitingForDebugger)
            }

            // Create and start all registered command dispatchers
            val dispatchers = jdwpProcessList.map { jdwpProcess ->
                jdwpProcess.externalJdwpProcessCommandDispatcherList().map {
                    it.start()
                    it
                }.first()
            }

            // Send a "resume process" command to one of the dispatchers
            dispatchers.first().also { dispatcher ->
                val command = ExternalJdwpProcessCommandDispatcher.ProcessCommand.ResumeJdwpProcess(dispatcher.process.pid)
                dispatchers.first().executeCommand(command)
            }
            jdwpProcessList
        }

        // Assert: The process has been resumed!
        Assert.assertEquals(2, processList.size)
        val clientState = processList[0].run {
            fakeAdbRule.fakeAdb.device(device.serialNumber).getClient(pid)!!
        }
        Assert.assertEquals(false, clientState.waitingForDebugger)

    }

    private suspend fun <R> testSendProcessCommandWithMultipleSessions(
        sequenceOf: Sequence<AdbSession>,
        commandRunner: suspend (List<JdwpProcess>) -> R
    ): R {
        val fakeAdbServer = fakeAdbRule.fakeAdb
        val sessions = sequenceOf.toList()

        // Create a single process for testing
        val pid = 20
        val fakeDevice = fakeAdbServer.addSampleDevice(apiLevel = 30)
        fakeDevice.addSampleJdwpProcess(pid, isWaitingForDebugger = true)

        // Find the `ConnectedDevice` in each session
        val connectedDevices = sessions.map {
            it.waitForOnlineConnectedDevice(fakeDevice.deviceId)
        }

        // Find the `JdwpProcess` in each session
        val jdwpProcesses = connectedDevices.map { device ->
            device.jdwpProcessTracker.processesFlow.mapNotNull { processList ->
                processList.firstOrNull { it.pid == pid }
            }.first()
        }

        // Act
        return commandRunner(jdwpProcesses)
    }

    private fun FakeAdbServerProvider.addSampleDevice(apiLevel: Int): DeviceState {
        return connectDevice(
            deviceId = "1234",
            manufacturer = "test1",
            deviceModel = "test2",
            release = "model",
            sdk = AndroidApiLevel(apiLevel),
            hostConnectionType = DeviceState.HostConnectionType.USB
        ).also {
            it.deviceStatus = DeviceState.DeviceStatus.ONLINE
        }
    }

    private fun DeviceState.addSampleJdwpProcess(pid: Int, isWaitingForDebugger: Boolean = false): ClientState {
        return startClient(
            pid,
            userId = 0,
            packageName = "a.b.c",
            isWaiting = isWaitingForDebugger
        ).also {
            // Additional features to return in FEAT reply packet
            it.addFeature("feat1")
            it.addFeature("feat2")
            it.addFeature("feat3")
        }
    }

    private fun createSessionClone(fakeAdbRule: FakeAdbServerProviderRule): AdbSession {
        val host = fakeAdbRule.host
        return AdbSession.create(
            host,
            fakeAdbRule.createChannelProvider(),
            Duration.ofMillis(SOCKET_CONNECT_TIMEOUT_MS)
        ).also {
            registerCloseable(it)
        }
    }

    private fun AdbSession.installTestProcessInventoryServer() {
        val server = ProcessInventoryServerConnection.create(this, TestServerConfig())
        this.installProcessInventoryJdwpProcessCommandDispatcherFactory(
            server,
            enabled = { true }
        )
    }

    private suspend fun findFreeTcpPort(): Int {
        val session = registerCloseable(FakeAdbSession())
        val freePort = session.channelFactory.createServerSocket().use {
            it.bind(InetSocketAddress(0)).port
        }
        return freePort
    }

    private class TestServerConfig : ProcessInventoryServerConfiguration {

        override var clientDescription: String = "test_client"

        override var serverDescription: String = "test_server"
    }

    private fun <T : AutoCloseable> registerCloseable(item: T): T {
        return closeables.register(item)
    }
}
