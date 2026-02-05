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

import com.android.adblib.ConnectedDevice
import com.android.adblib.DeviceState
import com.android.adblib.serialNumber
import com.android.adblib.waitUntilOnline
import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * This flow can be used to keep track of debuggable processes.
 *
 * The [Flow] starts when the device becomes [DeviceState.ONLINE] and remains active as long as the device is connected. The flow terminates
 * with an [EOFException] when the device disconnects.
 *
 * This flow keeps the caller informed about the lifecycle of debuggable processes, notifying it when they start, stop, or have their
 * properties modified.
 *
 * See [JdwpProcessChange] for more info.
 *
 * @throws EOFException if the device disconnects while the flow is active.
 * @throws IOException if the device disconnects while waiting for [DeviceState.ONLINE].
 */
val ConnectedDevice.jdwpProcessChangeFlow: Flow<JdwpProcessChange>
  get() = channelFlow {
    val device = this@jdwpProcessChangeFlow
    var currentProcesses = mapOf<Int, JdwpProcess>()
    val currentProcessTrackingJobs: MutableMap<Int, Job> = mutableMapOf()

    device.waitUntilOnline()
    device.jdwpProcessTracker.processesFlow.collect { processes ->
      // Do not emit if we are only initializing the flow
      if (processes.flowStatus.isStartOfFlow) {
        return@collect
      }

      if (processes.flowStatus.isEndOfFlow) {
        throw EOFException("Stopping `jdwpProcessChangeFlow` as device `${device.serialNumber}` disconnected")
      }

      val processesById = processes.associateBy { it.pid }
      val removedPids = currentProcesses.keys - processesById.keys
      val addedPids = processesById.keys - currentProcesses.keys

      // Send [ProcessChange] for removed processes
      removedPids.forEach { pid ->
        currentProcessTrackingJobs.remove(pid)?.also { propertiesJob ->
          propertiesJob.cancel("Cancelling process tracking job [pid=$pid]")
          propertiesJob.join()
        }
        send(JdwpProcessChange.Removed(currentProcesses[pid]!!.toJdwpProcessInfo()))
      }

      addedPids.forEach { pid ->
        val addedProcess = processesById[pid]!!
        val addedProcessInfo = addedProcess.toJdwpProcessInfo()
        // Send [ProcessChange] for added process.
        send(JdwpProcessChange.Added(addedProcessInfo))

        // Keep track of process properties updates
        val job =
          addedProcess.scope.launch {
            // Combine both 'properties' and 'proxy status' flows so that changes to
            // either are unified in a single `collect`.
            addedProcess.propertiesFlow
              .combine(addedProcess.jdwpProxySocketServer.proxyStatusFlow) { properties, proxyStatus ->
                JdwpProcessInfo(device = addedProcess.device, properties = properties, proxyStatus = proxyStatus)
              }
              .collectIndexed { index, updatedProcessInfo ->
                // Skip the first update if we just sent it out as part of `Added` update
                if (index != 0 || updatedProcessInfo != addedProcessInfo) {
                  // Send [ProcessChange] for updated process.
                  send(JdwpProcessChange.Updated(updatedProcessInfo))
                }
              }
          }
        currentProcessTrackingJobs[pid] = job
      }

      currentProcesses = processesById
    }
  }

/** Represents a change in debuggable processes on a [ConnectedDevice] */
sealed class JdwpProcessChange(val processInfo: JdwpProcessInfo) {

  /** Debuggable process added since last flow emit */
  class Added(processInfo: JdwpProcessInfo) : JdwpProcessChange(processInfo)

  /** Debuggable process removed since last flow emit */
  class Removed(processInfo: JdwpProcessInfo) : JdwpProcessChange(processInfo)

  /** Debuggable process for which the process properties have changed */
  class Updated(processInfo: JdwpProcessInfo) : JdwpProcessChange(processInfo)
}
