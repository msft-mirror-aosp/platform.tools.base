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
package com.android.adblib.tools.debugging.processinventory.server

import com.android.adblib.AdbSession
import com.android.adblib.IsThreadSafe
import com.android.adblib.adbLogger
import com.android.adblib.tools.debugging.AtomicStateFlow
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.DeviceId
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.JdwpProcessInfo
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.ProcessUpdate
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.ProcessUpdates
import com.android.adblib.withPrefix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Keeps track of the [List] of [processes][JdwpProcessInfo] of a given device [DeviceId]
 */
@IsThreadSafe
internal class DeviceProcessCatalog(session: AdbSession, val deviceId: DeviceId) {

    private val logger = adbLogger(session)
        .withPrefix("adbSessionId=${deviceId.adbSessionId}, " +
                            "serialNumber=${deviceId.serialNumber} - ")

    private val processListAtomicStateFlow = AtomicStateFlow(MutableStateFlow(ProcessList.Empty))

    private val processListFlow = processListAtomicStateFlow.asStateFlow()

    /**
     * Returns a [Flow] of [ProcessInventoryServerProto.ProcessUpdates] that emits a new item
     * everytime anything changes in the [list of processes][ProcessList] tracked by
     * this [DeviceProcessCatalog].
     */
    fun trackProcesses(): Flow<ProcessUpdates> = flow {
        val collector = this
        logger.debug { "trackProcesses(): entering flow" }

        // Capture known list (snapshot) so that we can compute deltas over time
        var currentList = processListFlow.value

        // Send known list so that
        logger.debug { "trackProcesses(): emitting initial list of processes" }
        collector.emitInitialProcessList(currentList)

        // Note: We rely on any change to any process properties results in a new list
        // in the flow.
        processListFlow.collect {
            val newList = it
            val updates = computeProcessListUpdates(currentList, newList)
            logger.verbose { "Computed updates to processes: isEmpty=${updates.isEmpty()}" }

            // Don't send anything if there are no updates
            if (!updates.isEmpty()) {
                collector.emitProcessListUpdates(updates)
                currentList = newList
            }
        }
    }

    /**
     * Updates the [list of processes][ProcessList] tracked by this [DeviceProcessCatalog].
     *
     * Updates are reflected in the [Flow] returned by [trackProcesses].
     */
    fun updateProcessList(processUpdates: ProcessUpdates) {
        updateProcessListStateFlow { oldProcessList ->
            // Create a new process list from the updates we are receiving
            // * Remove "deleted" processes
            // * Merge info for existing processes
            // * Add new processes "as-is"
            val processInfoMap = oldProcessList.processes.associateBy { it.pid }.toMutableMap()
            processUpdates.processUpdateList.forEach { processUpdate ->
                when {
                    processUpdate.hasProcessTerminatedPid() -> {
                        val pid = processUpdate.processTerminatedPid
                        logger.debug { "Process $pid has exited" }
                        processInfoMap.remove(pid)
                    }

                    processUpdate.hasProcessUpdated() -> {
                        val newJdwpProcessInfo = processUpdate.processUpdated
                        logger.debug { "Process ${newJdwpProcessInfo.pid} has been updated: $newJdwpProcessInfo" }
                        val currentJdwpProcessInfo =
                            processInfoMap.computeIfAbsent(newJdwpProcessInfo.pid) { newJdwpProcessInfo }
                        processInfoMap[newJdwpProcessInfo.pid] =
                            currentJdwpProcessInfo.mergeWith(newJdwpProcessInfo)
                    }
                }
            }

            // Return new process list
            ProcessList(
                processes = processInfoMap.values.sortedBy { it.pid },
            )
        }
    }

    private suspend fun FlowCollector<ProcessUpdates>.emitInitialProcessList(
        processList: ProcessList
    ) {
        val addProcesses = ProcessListUpdates(
            addedProcessInfo = processList.processes,
            removedProcessInfo = emptyList(),
            updatedProcessInfo = emptyList()
        )
        emitProcessListUpdates(addProcesses)
    }

    private suspend fun FlowCollector<ProcessUpdates>.emitProcessListUpdates(
        updates: ProcessListUpdates
    ) {
        logger.debug {
            "emitProcessListUpdates: emitting updates (" +
                    "added info count=${updates.addedProcessInfo.size}, " +
                    "updated info count=${updates.updatedProcessInfo.size}, " +
                    "removed info count=${updates.removedProcessInfo.size})"
        }
        logger.verbose { "emitProcessListUpdates: added info=${updates.addedProcessInfo}" }
        logger.verbose { "emitProcessListUpdates: updated info =${updates.updatedProcessInfo}" }
        logger.verbose { "emitProcessListUpdates: removed info =${updates.removedProcessInfo}" }

        val response = ProcessUpdates
            .newBuilder()
            .addAllProcessUpdate(
                updates.addedProcessInfo.map { processInfo ->
                    ProcessUpdate
                        .newBuilder()
                        .setProcessUpdated(processInfo)
                        .build()
                } + updates.removedProcessInfo.map { processInfo ->
                    ProcessUpdate
                        .newBuilder()
                        .setProcessTerminatedPid(processInfo.pid)
                        .build()
                } + updates.updatedProcessInfo.map { processInfo ->
                    ProcessUpdate
                        .newBuilder()
                        .setProcessUpdated(processInfo)
                        .build()
                }
            )
            .build()

        emit(response)
    }

    private class ProcessListUpdates(
        val addedProcessInfo: List<JdwpProcessInfo>,
        val updatedProcessInfo: List<JdwpProcessInfo>,
        val removedProcessInfo: List<JdwpProcessInfo>,
    ) {

        fun isEmpty(): Boolean {
            return addedProcessInfo.isEmpty() &&
                    updatedProcessInfo.isEmpty() &&
                    removedProcessInfo.isEmpty()
        }
    }

    private fun computeProcessListUpdates(
        currentList: ProcessList,
        newList: ProcessList
    ): ProcessListUpdates {
        val oldInfoMap = currentList.processes.associateBy { it.pid }
        val newInfoMap = newList.processes.associateBy { it.pid }
        val addedInfoPids = newInfoMap.keys subtract oldInfoMap.keys
        val updatedInfoPids = (oldInfoMap.keys intersect newInfoMap.keys).filter { pid ->
            val oldInfo = oldInfoMap[pid]
            val newInfo = newInfoMap[pid]
            oldInfo != newInfo
        }
        val removedInfoPids = oldInfoMap.keys subtract newInfoMap.keys

        return ProcessListUpdates(
            addedProcessInfo = newInfoMap.filter { addedInfoPids.contains(it.key) }.values.toList(),
            updatedProcessInfo = newInfoMap.filter { updatedInfoPids.contains(it.key) }.values.toList(),
            removedProcessInfo = oldInfoMap.filter { removedInfoPids.contains(it.key) }.values.toList(),
        )
    }

    private fun updateProcessListStateFlow(update: (ProcessList) -> ProcessList) {
        processListAtomicStateFlow.update { currentList ->
            update(currentList).also { newList ->
                logger.debug {
                    "Updating process list from ${currentList.processes.size} element(s) " +
                            "to ${newList.processes.size} element(s)"
                }
            }
        }
    }

    /**
     * A simple wrapper around a [List] of [JdwpProcessInfo]
     */
    private data class ProcessList(val processes: List<JdwpProcessInfo>) {

        companion object {

            val Empty = ProcessList(emptyList())
        }
    }

    /**
     * Returns a [JdwpProcessInfo] instances resulting from the merging of properties of this
     * [JdwpProcessInfo] with [other].
     */
    private fun JdwpProcessInfo.mergeWith(other: JdwpProcessInfo): JdwpProcessInfo {
        return JdwpProcessInfo.newBuilder(this)
            .also { proto ->
                proto.pid = other.pid
                proto.completed = other.completed
                if (other.hasCompletedException()) proto.completedException = other.completedException
                if (other.hasProcessName()) proto.processName = other.processName
                if (other.hasPackageName()) proto.packageName = other.packageName
                if (other.hasUserId()) proto.userId = other.userId
                if (other.hasInstructionSet()) proto.instructionSet = other.instructionSet
                if (other.hasVmIdentifier()) proto.vmIdentifier = other.vmIdentifier
                if (other.hasJvmFlags()) proto.jvmFlags = other.jvmFlags
                if (other.hasNativeDebuggable()) proto.nativeDebuggable = other.nativeDebuggable
                if (other.hasWaitingForDebugger()) proto.waitingForDebugger = other.waitingForDebugger
                if (other.hasFeatures()) proto.features = other.features
            }
            .build()
    }
}
