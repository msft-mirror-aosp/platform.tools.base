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
package com.android.adblib.tools.debugging.processinventory.server

import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.DeviceId
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.OptionalBool
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.OptionalInt32
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.OptionalString
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.OptionalStringList
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceProcessCatalogTest {

    private val fakeAdbSession = FakeAdbSession()
    private val deviceId = DeviceId.newBuilder()
                .setAdbSessionId("test-session")
                .setSerialNumber("test-device")
                .build()
    private val catalog = DeviceProcessCatalog(fakeAdbSession, deviceId)

    @Test
    fun trackProcessUpdatesInitiallyEmitsEmptyUpdateList(): Unit =
        CoroutineTestUtils.runBlockingWithTimeout {
            val processUpdates = catalog.trackProcessUpdates().first()
            assertTrue(processUpdates.processUpdateList.isEmpty())
        }

    @Test
    fun trackProcessUpdatesEmitsUpdatedProcessValues(): Unit =
        CoroutineTestUtils.runBlockingWithTimeout {
            var collectedIndex = 0
            catalog.trackProcessUpdates().take(2).collect { processUpdates ->
                if (collectedIndex == 0) {
                    // Assert initial state: empty list
                    assertTrue(processUpdates.processUpdateList.isEmpty())

                    // Update process
                    val process = createJdwpProcessInfo(
                        pid = 100,
                        processName = "proc1",
                        packageName = "package1",
                        userId = 1001,
                        instructionSet = "x86",
                        vmIdentifier = "vmId1",
                        jvmFlags = "jvmFlags1",
                        nativeDebuggable = true,
                        waitingForDebugger = true,
                        feature = "feat1"
                    )
                    val processUpdates = createProcessUpdates(process)
                    catalog.handleProcessUpdates(processUpdates)
                } else if (collectedIndex == 1) {
                    // Assert updated process
                    assertEquals(1, processUpdates.processUpdateCount)
                    val processInfo = processUpdates.getProcessUpdate(0).processUpdated
                    assertEquals(100, processInfo.pid)
                    assertEquals("proc1", processInfo.processName.stringValue)
                    assertEquals("package1", processInfo.packageNames.stringsValueList.first())
                    assertEquals(1001, processInfo.userId.int32Value)
                    assertEquals("x86", processInfo.instructionSet.stringValue)
                    assertEquals("vmId1", processInfo.vmIdentifier.stringValue)
                    assertEquals("jvmFlags1", processInfo.jvmFlags.stringValue)
                    assertEquals(true, processInfo.nativeDebuggable.boolValue)
                    assertEquals(true, processInfo.waitingForDebugger.boolValue)
                    assertEquals("feat1", processInfo.features.stringsValueList.first())
                }
                collectedIndex++
            }
        }

    @Test
    fun newerValuesOverwriteOlderValues(): Unit =
        CoroutineTestUtils.runBlockingWithTimeout {
            // Prepare
            var collectedIndex = 0
            val process = createJdwpProcessInfo(
                pid = 100,
                processName = "proc1",
                packageName = "package1",
                userId = 1001,
                instructionSet = "x86",
                vmIdentifier = "vmId1",
                jvmFlags = "jvmFlags1",
                nativeDebuggable = true,
                waitingForDebugger = true,
                feature = "feat1"
            )
            val processUpdates = createProcessUpdates(process)
            catalog.handleProcessUpdates(processUpdates)

            // Act / Assert
            catalog.trackProcessUpdates().take(2).collect { processUpdates ->
                if (collectedIndex == 0) {
                    // Assert original values
                    assertEquals(1, processUpdates.processUpdateCount)
                    val processInfo = processUpdates.getProcessUpdate(0).processUpdated
                    assertEquals(100, processInfo.pid)
                    assertEquals("proc1", processInfo.processName.stringValue)
                    assertEquals("package1", processInfo.packageNames.stringsValueList.first())
                    assertEquals(1001, processInfo.userId.int32Value)
                    assertEquals("x86", processInfo.instructionSet.stringValue)
                    assertEquals("vmId1", processInfo.vmIdentifier.stringValue)
                    assertEquals("jvmFlags1", processInfo.jvmFlags.stringValue)
                    assertEquals(true, processInfo.nativeDebuggable.boolValue)
                    assertEquals(true, processInfo.waitingForDebugger.boolValue)
                    assertEquals("feat1", processInfo.features.stringsValueList.first())

                    // Update process values
                    val updatedProcess = createJdwpProcessInfo(
                        pid = 100,
                        processName = "proc2",
                        packageName = "package2",
                        userId = 1002,
                        instructionSet = "x86_64",
                        vmIdentifier = "vmId2",
                        jvmFlags = "jvmFlags2",
                        nativeDebuggable = false,
                        waitingForDebugger = false,
                        feature = "feat2"
                    )
                    val processUpdates = createProcessUpdates(updatedProcess)
                    catalog.handleProcessUpdates(processUpdates)
                } else if (collectedIndex == 1) {
                    // Assert updated process
                    assertEquals(1, processUpdates.processUpdateCount)
                    val processInfo = processUpdates.getProcessUpdate(0).processUpdated
                    assertEquals(100, processInfo.pid)
                    assertEquals("proc2", processInfo.processName.stringValue)
                    assertEquals("package2", processInfo.packageNames.stringsValueList.first())
                    assertEquals(1002, processInfo.userId.int32Value)
                    assertEquals("x86_64", processInfo.instructionSet.stringValue)
                    assertEquals("vmId2", processInfo.vmIdentifier.stringValue)
                    assertEquals("jvmFlags2", processInfo.jvmFlags.stringValue)
                    assertEquals(false, processInfo.nativeDebuggable.boolValue)
                    assertEquals(false, processInfo.waitingForDebugger.boolValue)
                    assertEquals("feat2", processInfo.features.stringsValueList.first())
                }
                collectedIndex++
            }
        }

    @Test
    fun emptyProcessValuesDoNotOverwriteExistingValues(): Unit =
        CoroutineTestUtils.runBlockingWithTimeout {
            // Prepare
            var collectedIndex = 0
            val process = createJdwpProcessInfo(
                pid = 100,
                processName = "proc1",
                packageName = "package1",
                userId = 1001,
                instructionSet = "x86",
                vmIdentifier = "vmId1",
                jvmFlags = "jvmFlags1",
                nativeDebuggable = true,
                waitingForDebugger = true,
                feature = "feat1"
            )
            val processUpdates = createProcessUpdates(process)
            catalog.handleProcessUpdates(processUpdates)

            // Act / Assert
            val job = async {
                catalog.trackProcessUpdates().collect { processUpdates ->
                    if (collectedIndex == 0) {
                        // Assert process values
                        assertEquals(1, processUpdates.processUpdateCount)
                        val processInfo = processUpdates.getProcessUpdate(0).processUpdated
                        assertEquals(100, processInfo.pid)
                        assertEquals("proc1", processInfo.processName.stringValue)
                        assertEquals("package1", processInfo.packageNames.stringsValueList.first())
                        assertEquals(1001, processInfo.userId.int32Value)
                        assertEquals("x86", processInfo.instructionSet.stringValue)
                        assertEquals("vmId1", processInfo.vmIdentifier.stringValue)
                        assertEquals("jvmFlags1", processInfo.jvmFlags.stringValue)
                        assertEquals(true, processInfo.nativeDebuggable.boolValue)
                        assertEquals(true, processInfo.waitingForDebugger.boolValue)
                        assertEquals("feat1", processInfo.features.stringsValueList.first())

                        // After processing initial value try setting process values to `Empty`
                        val updatedProcess = createJdwpProcessInfo(pid = 100)
                        val processUpdates = createProcessUpdates(updatedProcess)
                        catalog.handleProcessUpdates(processUpdates)
                    } else if (collectedIndex == 1) {
                        fail("No collection should take place as empty values should not override existing values")
                    }
                    collectedIndex++
                }
            }

            delay(100)
            job.cancelAndJoin()
            // Update with empty values does not trigger any updates
            assertEquals(1, collectedIndex)
        }

    private fun createProcessUpdates(process: ProcessInventoryServerProto.JdwpProcessInfo): ProcessInventoryServerProto.ProcessUpdates {
        return ProcessInventoryServerProto.ProcessUpdates.newBuilder()
            .also { it.addProcessUpdateBuilder().setProcessUpdated(process).build() }
            .build()
    }

    private fun createJdwpProcessInfo(
        pid: Int,
        processName: String? = null,
        packageName: String? = null,
        userId: Int? = null,
        instructionSet: String? = null,
        vmIdentifier: String? = null,
        jvmFlags: String? = null,
        nativeDebuggable: Boolean? = null,
        waitingForDebugger: Boolean? = null,
        feature: String? = null
    ): ProcessInventoryServerProto.JdwpProcessInfo {
        val builder = ProcessInventoryServerProto.JdwpProcessInfo.newBuilder().setPid(pid)
        builder.processName = processName?.let {
            OptionalString.newBuilder().setHasValue(true).setStringValue(it).build()
        } ?: OptionalString.getDefaultInstance()
        builder.packageNames = packageName?.let {
            OptionalStringList.newBuilder().setHasValue(true).addStringsValue(it).build()
        } ?: OptionalStringList.getDefaultInstance()
        builder.userId = userId?.let {
            OptionalInt32.newBuilder().setHasValue(true).setInt32Value(it).build()
        } ?: OptionalInt32.getDefaultInstance()
        builder.instructionSet = instructionSet?.let {
            OptionalString.newBuilder().setHasValue(true).setStringValue(it).build()
        } ?: OptionalString.getDefaultInstance()
        builder.vmIdentifier = vmIdentifier?.let {
            OptionalString.newBuilder().setHasValue(true).setStringValue(it).build()
        } ?: OptionalString.getDefaultInstance()
        builder.jvmFlags = jvmFlags?.let {
            OptionalString.newBuilder().setHasValue(true).setStringValue(it).build()
        } ?: OptionalString.getDefaultInstance()
        builder.nativeDebuggable = nativeDebuggable?.let {
            OptionalBool.newBuilder().setHasValue(true).setBoolValue(it).build()
        } ?: OptionalBool.getDefaultInstance()
        builder.waitingForDebugger = waitingForDebugger?.let {
            OptionalBool.newBuilder().setHasValue(true).setBoolValue(it).build()
        } ?: OptionalBool.getDefaultInstance()
        builder.features = feature?.let {
            OptionalStringList.newBuilder().setHasValue(true).addStringsValue(it).build()
        } ?: OptionalStringList.getDefaultInstance()
        return builder.build()
    }
}
