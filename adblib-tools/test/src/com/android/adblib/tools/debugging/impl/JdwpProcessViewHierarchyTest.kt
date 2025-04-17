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
package com.android.adblib.tools.debugging.impl

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.FakeAdbServerProvider
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.JdwpProcessViewHierarchy
import com.android.adblib.tools.debugging.toByteBuffer
import com.android.adblib.tools.testutils.AdbLibToolsTestBase
import com.android.adblib.tools.testutils.waitForOnlineConnectedDevice
import com.android.fakeadbserver.ClientState
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class JdwpProcessViewHierarchyTest : AdbLibToolsTestBase() {

    @Test
    fun listViewRootsWorks() = runBlockingWithTimeout { // Prepare
        val windowName = "root1"
        val viewHierarchy = createJdwpProcessViewHierarchy(fakeAdb)
        fakeAdb.device("1234").client(10).viewsState.addViewRoot(windowName)

        // Act / Assert
        viewHierarchy.listViewRoots { payload, size ->
            val data = payload.toByteBuffer(size)
            val windowsCount = data.int
            assertEquals(1, windowsCount)
            val windowNameLength = data.int
            assertEquals(windowName.length, windowNameLength)
            assertEquals(windowName, getString(data, windowNameLength))
        }
    }

    @Test
    fun dumpViewHierarchyWorks() = runBlockingWithTimeout { // Prepare
        val windowName = "root1"
        val otherWindowName = "otherWindow"
        val viewHierarchy = createJdwpProcessViewHierarchy(fakeAdb)
        val clientState = fakeAdb.device("1234").client(10)
        val data1 = createFakeViewData(windowName, 100)
        val data2 = createFakeViewData(otherWindowName, 200)
        clientState.viewsState.addViewHierarchy(
            windowName, skipChildren = false, includeProperties = true, useV2 = true, data = data1
        )
        clientState.viewsState.addViewHierarchy(
            otherWindowName, skipChildren = false, includeProperties = true, useV2 = true, data = data2
        )

        // Act / Assert
        viewHierarchy.dumpViewHierarchy(
            windowName, skipChildren = false, includeProperties = true, useV2 = true
        ) { payload, size ->
            val dumpViewData = payload.toByteBuffer(size)
            assertEquals(data1, dumpViewData)
        }
    }

    @Test
    fun captureViewWorks() = runBlockingWithTimeout { // Prepare
        val windowName = "root1"
        val viewName = "view1"
        val viewHierarchy = createJdwpProcessViewHierarchy(fakeAdb)
        val clientState = fakeAdb.device("1234").client(10)
        val data = createFakeViewData(windowName, 100)
        clientState.viewsState.addViewCapture(windowName, viewName, data)

        // Act / Assert
        viewHierarchy.captureView(
            windowName, viewName
        ) { payload, size ->
            val dumpViewData = payload.toByteBuffer(size)
            assertEquals(data, dumpViewData)
        }
    }

    private suspend fun createJdwpProcessViewHierarchy(fakeAdb: FakeAdbServerProvider): JdwpProcessViewHierarchy {
        val deviceID = "1234"
        val fakeDevice = fakeAdb.connectDevice(
            deviceID, "test1", "test2", "model", AndroidApiLevel(30), DeviceState.HostConnectionType.USB
        )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        val connectedDevice = waitForOnlineConnectedDevice(session, fakeDevice.deviceId)
        fakeDevice.startClient(10, 0, "a.b.c", false)
        val process = connectedDevice.jdwpProcessManager.getProcess(10)
        return JdwpProcessViewHierarchyImpl(process)
    }

    private fun createFakeViewData(view: String, size: Int): ByteBuffer {
        val result = ByteBuffer.allocate(4 + 2 * view.length + size)
        result.putInt(view.length)
        view.forEach { result.putChar(it) }
        repeat(size) {
            result.put(5)
        }
        result.flip()
        return result
    }

    private fun DeviceState.client(pid: Int): ClientState {
        return getClient(pid)
            ?: throw IllegalArgumentException("Client $pid does not exist on device ${this.deviceId}")
    }

    private fun JdwpProcessManager.getProcess(pid: Int): JdwpProcess {
        return this.addProcesses(setOf(pid))[pid]!!
    }

    private fun getString(buffer: ByteBuffer, size: Int): String {
        val data = CharArray(size)
        for (i in 0 until size) {
            data[i] = buffer.char
        }
        return String(data)
    }
}
