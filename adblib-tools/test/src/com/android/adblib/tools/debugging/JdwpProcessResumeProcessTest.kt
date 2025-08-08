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
package com.android.adblib.tools.debugging

import com.android.adblib.AdbSession
import com.android.adblib.SOCKET_CONNECT_TIMEOUT_MS
import com.android.adblib.serialNumber
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CloseablesRule
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.FakeAdbServerProvider
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.tools.AdbLibToolsProperties
import com.android.adblib.tools.debugging.impl.AbstractJdwpProcess
import com.android.adblib.tools.debugging.processinventory.AdbLibToolsProcessInventoryServerProperties
import com.android.adblib.tools.debugging.processinventory.ProcessInventoryServerConnection
import com.android.adblib.tools.debugging.processinventory.installProcessInventoryJdwpProcessCommandDispatcherFactory
import com.android.adblib.tools.debugging.processinventory.installProcessInventoryJdwpProcessPropertiesCollectorFactory
import com.android.adblib.tools.debugging.processinventory.server.ProcessInventoryServerConfiguration
import com.android.adblib.tools.testutils.waitForOnlineConnectedDevice
import com.android.fakeadbserver.ClientState
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import java.net.InetSocketAddress
import java.time.Duration
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

class JdwpProcessResumeProcessTest {

    @JvmField
    @Rule
    val fakeAdbRule = FakeAdbServerProviderRule {
        installDefaultCommandHandlers()
    }

    @JvmField
    @Rule
    val closeables = CloseablesRule()

    @Test
    fun testJdwpProcessResumeWorksWithJdwpPropertiesCollector(): Unit = runBlockingWithTimeout {
        // Prepare
        fakeAdbRule.host.setPropertyValue(
          AdbLibToolsProcessInventoryServerProperties.LOCAL_PORT_V1,
          findFreeTcpPort()
        )

        fakeAdbRule.host.setPropertyValue(
            AdbLibToolsProperties.PROCESS_PROPERTIES_READ_TIMEOUT,
            Duration.ofSeconds(5)
        )

        // Accelerate JDWP properties collection to make test faster
        fakeAdbRule.host.setPropertyValue(
            AdbLibToolsProperties.PROCESS_PROPERTIES_COLLECTOR_DELAY_DEFAULT,
            Duration.ofMillis(20)
        )

        // Act: create 2 adb sessions with one device, and call `resumeProcess`
        val jdwpProcessList = createDeviceAndProcessInAdbSessions(
            deviceApiLevel = 30 /* ensure fake device does not support app_info */,
            adbSessions = createTwoSession()
        )

        // All processes should reach `isWaitingForDebugger`==`false` (since the Process Inventory
        // server is active)
        combine(jdwpProcessList.map { it.jdwpPropertiesCollector.stateFlow }) {
            it.toList()
        }.first {
            it.all { jdwpProcessProperties -> jdwpProcessProperties.isWaitingForDebugger.isValue(true) }
        }

        // Call `resumeProcess` on the JdwpProcess that does **not** have a JDWP connection open
        // (We could call on any process, but picking one makes the test more deterministic)
        val processWithActivationCountFlow = jdwpProcessList.map { jdwpProcess ->
            (jdwpProcess as AbstractJdwpProcess).jdwpSessionActivationCount.map {
                Pair(jdwpProcess, it)
            }
        }
        // Find the first process that has an `activationCount == 0`.
        // Note: We cannot directly check for "0", as there is retry behavior, so we need
        // to "wait" for the flow values to have a value of "0".
        // We know there will be one at some point because 1) only one has a value of "1"
        // consistently, and 2) the other ones retry only every 5 seconds, so there is plenty
        // of time to find a value of "0" for those.
        val processWithNoJdwpConnectionOpen = combine(processWithActivationCountFlow) {
            it.toList()
        }.first {
            it.firstOrNull { (_, activationCount) -> activationCount == 0 } != null
        }.first().first

        processWithNoJdwpConnectionOpen.resumeProcess()

        // Assert
        Assert.assertEquals(2, jdwpProcessList.size)

        // The `Client` process (in FakeAdb) should have been resumed
        jdwpProcessList.first().also { jdwpProcess ->
            val client = fakeAdbRule.fakeAdb.device(jdwpProcess.device.serialNumber).getClient(jdwpProcess.pid)
            Assert.assertNotNull(client)
            Assert.assertFalse(client!!.waitingForDebugger)
        }

        // All processes should reach `isWaitingForDebugger`==`false` (since the Process Inventory
        // server is active)
        combine(jdwpProcessList.map { it.jdwpPropertiesCollector.stateFlow }) {
            it.toList()
        }.first {
            it.all { jdwpProcessProperties ->
                jdwpProcessProperties.isWaitingForDebugger.isValue(false)
            }
        }
    }

    @Test
    fun testJdwpProcessResumeWorksWithAppInfo(): Unit = runBlockingWithTimeout {
        // Prepare
        fakeAdbRule.host.setPropertyValue(
          AdbLibToolsProcessInventoryServerProperties.LOCAL_PORT_V1,
          findFreeTcpPort()
        )

        fakeAdbRule.host.setPropertyValue(
            AdbLibToolsProperties.PROCESS_PROPERTIES_READ_TIMEOUT,
            Duration.ofSeconds(5)
        )

        // Ensure APP_INFO is enabled
        fakeAdbRule.host.setPropertyValue(
            AdbLibToolsProperties.PROCESS_PROPERTIES_COLLECTOR_USE_APP_INFO_IF_AVAILABLE,
            true
        )

        // Act: create 2 adb sessions with one device
        val jdwpProcessList = createDeviceAndProcessInAdbSessions(
            deviceApiLevel = 36 /* ensure fake device supports app info */,
            adbSessions = createTwoSession()
        )

        // Wait until are processes are in the `isWaitingForDebugger` state
        jdwpProcessList.forEach { jdwpProcess ->
            jdwpProcess.jdwpPropertiesCollector.stateFlow.first { it.isWaitingForDebugger.isValue(true) }
        }

        // Call resume process for a "random" session
        jdwpProcessList.first().resumeProcess()

        // Assert: All processes should now be in the `isWaitingForDebugger` == false
        Assert.assertEquals(2, jdwpProcessList.size)
        jdwpProcessList.forEach { jdwpProcess ->
            jdwpProcess.jdwpPropertiesCollector.stateFlow.first { it.isWaitingForDebugger.isValue(false) }
        }
    }

    private fun createTwoSession(): Sequence<AdbSession> = sequence {
        val session1 = fakeAdbRule.adbSession
        val session2 = createSessionClone(fakeAdbRule)
        session1.setupForTest()
        session2.setupForTest()
        yield(session1)
        yield(session2)
    }

    private suspend fun createDeviceAndProcessInAdbSessions(
        deviceApiLevel: Int = 30,
        adbSessions: Sequence<AdbSession>
    ): List<JdwpProcess> {
        // Create a single device with a single process for testing
        val pid = 20
        val fakeDevice = fakeAdbRule.fakeAdb.addSampleDevice(apiLevel = deviceApiLevel)
        fakeDevice.addSampleJdwpProcess(pid, isWaitingForDebugger = true)

        // Find the JdwpProcess in each AdbSession
        val jdwpProcessList = adbSessions.toList().map { session ->
            // Find the `ConnectedDevice` in each session
            val connectedDevice = session.waitForOnlineConnectedDevice(fakeDevice.deviceId)

            // Find the `JdwpProcess` in each session
            val process = connectedDevice.jdwpProcessTracker.processesFlow.mapNotNull { processList ->
                processList.firstOrNull { it.pid == pid }
            }.first()

            if (!connectedDevice.isAppInfoSupported()) {
                // Ensure JDWP properties collection and external command dispatcher are started
                process.jdwpPropertiesCollector.stateFlow.first {
                    it.packageName.hasValue
                }
            }
            process
        }

        return jdwpProcessList
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

    private fun AdbSession.setupForTest() {
        val sessionId = this.hashCode()
        val config = TestServerConfig(sessionId)
        val server = ProcessInventoryServerConnection.create(this, config).also {
            registerCloseable(it)
        }
        this.installProcessInventoryJdwpProcessPropertiesCollectorFactory(
            server,
            enabled = { true }
        )
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

    private class TestServerConfig(sessionId: Int) : ProcessInventoryServerConfiguration {

        override var clientDescription: String = "test_client_$sessionId"

        override var serverDescription: String = "test_server_$sessionId"
    }

    private fun <T : AutoCloseable> registerCloseable(item: T): T {
        return closeables.register(item)
    }

    private fun <T: Any> OptionalValue<T>.isValue(value: T): Boolean {
        return this.hasValue && this.getOrThrow() == value
    }

}
