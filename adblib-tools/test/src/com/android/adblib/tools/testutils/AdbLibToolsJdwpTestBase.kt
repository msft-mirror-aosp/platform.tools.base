/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.adblib.tools.testutils

import com.android.adblib.AdbSession
import com.android.adblib.ByteBufferAdbOutputChannel
import com.android.adblib.ConnectedDevice
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.serialNumber
import com.android.adblib.testingutils.CoroutineTestUtils
import com.android.adblib.testingutils.FakeAdbServerProvider
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.JdwpSession
import com.android.adblib.tools.debugging.impl.AbstractJdwpProcess
import com.android.adblib.tools.debugging.impl.JdwpProcessManager
import com.android.adblib.tools.debugging.impl.JdwpProcessSessionFinder
import com.android.adblib.tools.debugging.impl.addJdwpProcessSessionFinder
import com.android.adblib.tools.debugging.impl.jdwpProcessManager
import com.android.adblib.tools.debugging.jdwpProcessTracker
import com.android.adblib.tools.debugging.packets.JdwpPacketView
import com.android.adblib.tools.debugging.packets.ddms.DdmsChunkType
import com.android.adblib.tools.debugging.packets.ddms.DdmsChunkView
import com.android.adblib.tools.debugging.packets.ddms.DdmsPacketConstants
import com.android.adblib.tools.debugging.packets.ddms.EphemeralDdmsChunk
import com.android.adblib.tools.debugging.packets.ddms.writeToChannel
import com.android.adblib.tools.debugging.packets.impl.JdwpCommands
import com.android.adblib.tools.debugging.packets.impl.MutableJdwpPacket
import com.android.adblib.tools.debugging.packets.impl.PayloadProvider
import com.android.adblib.tools.debugging.properties
import com.android.adblib.tools.debugging.utils.AdbRewindableInputChannel
import com.android.adblib.utils.ResizableBuffer
import com.android.adblib.waitForDevice
import com.android.fakeadbserver.ClientState
import com.android.fakeadbserver.DeviceState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import java.io.EOFException

open class AdbLibToolsJdwpTestBase : AdbLibToolsTestBase() {
    protected class JdwpProxySessionInfo(
        val fakeDevice: DeviceState,
        val process: JdwpProcess,
        val debuggerJdwpSession: JdwpSession
    )

    protected suspend fun createJdwpProxySession(pid: Int): JdwpProxySessionInfo {
        val deviceID = "1234"
        val fakeDevice =
            fakeAdb.connectDevice(
                deviceID,
                "test1",
                "test2",
                "model",
                "30", // SDK >= 30 is required for abb_exec feature.
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        val connectedDevice = waitForOnlineConnectedDevice(session, fakeDevice.deviceId)
        fakeDevice.startClient(pid, 0, "a.b.c", false)
        val process = connectedDevice.jdwpProcessManager.getProcess(pid)
        //process.startMonitoring()
        CoroutineTestUtils.yieldUntil {
            process.properties.jdwpProxyStatus.socketAddress != null &&
                    process.properties.processName != null
        }
        val jdwpSession = attachDebuggerSession(process)
        return JdwpProxySessionInfo(
            fakeDevice = fakeDevice,
            process = process,
            debuggerJdwpSession = jdwpSession
        )
    }

    protected suspend fun attachDebuggerSession(process: JdwpProcess): JdwpSession {
        process.propertiesFlow.first { it.jdwpProxyStatus.socketAddress != null }
        val clientSocket = registerCloseable(
            session.channelFactory.connectSocket(
                process.properties.jdwpProxyStatus.socketAddress!!
            )
        )
        return registerCloseable(
            JdwpSession.wrapSocketChannel(
                process.device,
                clientSocket,
                process.pid,
                2_000
            )
        )
    }

    protected fun createVmVersionPacket(jdwpSession: JdwpSession): JdwpPacketView {
        val packet = MutableJdwpPacket()
        packet.id = jdwpSession.nextPacketId()
        packet.length = 11
        packet.isCommand = true
        packet.cmdSet = JdwpCommands.CmdSet.SET_VM.value
        packet.cmd = JdwpCommands.VmCmd.CMD_VM_VERSION.value
        return packet
    }

    protected suspend fun sendVmVersionPacket(jdwpSession: JdwpSession): JdwpPacketView {
        return coroutineScope {
            val packet = createVmVersionPacket(jdwpSession)

            val reply = async {
                val replyPacket: JdwpPacketView
                while (true) {
                    val r = jdwpSession.receivePacket()
                    if (r.id == packet.id) {
                        replyPacket = r
                        break
                    }
                }
                replyPacket
            }

            jdwpSession.sendPacket(packet)
            reply.await()
        }
    }

    protected suspend fun createDdmsHeloPacket(jdwpSession: JdwpSession): JdwpPacketView {
        val heloChunk = EphemeralDdmsChunk(
            type = DdmsChunkType.HELO,
            length = 0,
            payloadProvider = PayloadProvider.emptyPayload()
        )

        val packet = MutableJdwpPacket()
        packet.id = jdwpSession.nextPacketId()
        packet.length = 11 + 8
        packet.isCommand = true
        packet.cmdSet = DdmsPacketConstants.DDMS_CMD_SET
        packet.cmd = DdmsPacketConstants.DDMS_CMD
        packet.payloadProvider =
            PayloadProvider.forInputChannel(heloChunk.toRewindableInputChannel())
        return packet
    }

    private suspend fun DdmsChunkView.toRewindableInputChannel(): AdbRewindableInputChannel {
        val workBuffer = ResizableBuffer()
        val outputChannel = ByteBufferAdbOutputChannel(workBuffer)
        this.writeToChannel(outputChannel)
        val serializedChunk = workBuffer.forChannelWrite()
        return AdbRewindableInputChannel.forByteBuffer(serializedChunk)
    }

    protected fun JdwpSession.receivedPacketFlow() = flow {
        while (true) {
            val packet = try {
                this@receivedPacketFlow.receivePacket()
            } catch (e: EOFException) {
                // Reached EOF, flow terminates
                break
            }
            emit(packet)
        }
    }

    internal fun JdwpProcessManager.getProcess(pid: Int): AbstractJdwpProcess {
        return this.addProcesses(setOf(pid))[pid]!! as AbstractJdwpProcess
    }

    /**
     * Return a new "child" [AdbSession] of this [AdbSession]. A "child" session delegates JDWP
     * facilities to its parent session (see [JdwpProcessSessionFinder]).
     */
    protected fun AdbSession.createDelegatingChildSession(fakeAdbRule: FakeAdbServerProviderRule): AdbSession {
        val childSession = AdbSession.createChildSession(
            this,
            fakeAdbRule.host,
            fakeAdbRule.fakeAdb.createChannelProvider(fakeAdbRule.host)
        )
        childSession.addJdwpProcessSessionFinder(object : JdwpProcessSessionFinder {
            override fun findDelegateSession(forSession: AdbSession): AdbSession {
                return if (forSession == childSession) {
                    this@createDelegatingChildSession
                } else {
                    forSession
                }
            }
        })
        return childSession
    }

    /**
     * Given [sourceProcess], a [JdwpProcess] in a given [AdbSession], waits for and returns
     * a [JdwpProcess] instance from this [AdbSession] that has the same process ID as
     * [sourceProcess]. Typically, the wait should be small, as it is only intended to take
     * into account the fact that [AdbSession] instances are notified of new [JdwpProcess]
     * instances asynchronously.
     *
     * This method is intended to be used in conjunction with [createDelegatingChildSession].
     */
    protected suspend fun AdbSession.awaitJdwpProcess(sourceProcess: JdwpProcess): JdwpProcess {
        // Find the device in this session, then look for process with same pid
        val sessionDevice = connectedDevicesTracker.waitForDevice(sourceProcess.device.serialNumber)
        return sessionDevice.jdwpProcessTracker.processesFlow.transform { processList ->
            processList.firstOrNull { it.pid == sourceProcess.pid }?.also {
                emit(it)
            }
        }.first()
    }

    internal suspend fun createJdwpProcess(
        deviceApi: Int = 30,
        pid: Int = 10,
        waitForDebugger: Boolean = true
    ): Triple<FakeAdbServerProvider, ConnectedDevice, AbstractJdwpProcess> {
        val device = fakeAdb.addDevice(deviceApi)
        val clientState = device.createFakeAdbProcess(pid, waitForDebugger)
        val process = device.jdwpProcessManager.getProcess(clientState.pid)
        return Triple(fakeAdb, device, process)
    }

    internal suspend fun FakeAdbServerProvider.addDevice(deviceApi: Int = 30): ConnectedDevice {
        val fakeAdb = this
        val fakeDevice = addFakeDevice(fakeAdb, deviceApi)
        return waitForOnlineConnectedDevice(session, fakeDevice.deviceId)
    }

    internal suspend fun ConnectedDevice.createFakeAdbProcess(
        pid: Int = 10,
        waitForDebugger: Boolean = false
    ): ClientState {
        return fakeAdb.device(serialNumber).startClient(
            pid = pid,
            userId = 2,
            processName = "p1",
            packageName = "pkg",
            isWaiting = waitForDebugger
        )
    }
}
