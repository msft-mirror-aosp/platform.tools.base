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
package com.android.adblib.tools.debugging.impl

import com.android.adblib.ConnectedDevice
import com.android.adblib.StateFlowStatus
import com.android.adblib.adbLogger
import com.android.adblib.scope
import com.android.adblib.tools.debugging.JdwpProcessList
import com.android.adblib.tools.debugging.JdwpProcessTracker
import com.android.adblib.tools.debugging.isTrackAppSupported
import com.android.adblib.tools.debugging.trackApp
import com.android.adblib.tools.debugging.trackJdwp
import com.android.adblib.utils.createChildScope
import com.android.adblib.utils.logIOCompletionErrors
import com.android.adblib.utils.toImmutableList
import com.android.adblib.waitUntilOnline
import com.android.adblib.withPrefix
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

internal class JdwpProcessTrackerImpl(override val device: ConnectedDevice, private val useTrackAppIfAvailable: Boolean) :
  JdwpProcessTracker {

  private val logger = adbLogger(device.session).withPrefix("${device.session} - $device - ")

  private val processesMutableFlow = MutableStateFlow(JdwpProcessList(emptyList(), StateFlowStatus.startOfFlow))

  private val trackProcessesJob: Job by
    lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
      scope.launch {
        runCatching { trackProcesses() }.onFailure { throwable -> logger.logIOCompletionErrors(throwable, "JdwpProcessTracker") }
      }
    }

  override val scope = device.scope.createChildScope(isSupervisor = true)

  override val processesFlow = processesMutableFlow.asStateFlow()
    get() {
      // Note: We rely on "lazy" to ensure the tracking coroutine is launched only once
      trackProcessesJob
      return field
    }

  private suspend fun trackProcesses() {
    try {
      val useTrackApp =
        if (useTrackAppIfAvailable) {
          device.waitUntilOnline()
          device.isTrackAppSupported()
        } else {
          false
        }

      if (useTrackApp) {
        device.trackApp.stateFlow
          .takeWhile { appProcessEntries -> !appProcessEntries.flowStatus.isEndOfFlow }
          .collect { appProcessEntries ->
            val processIds = appProcessEntries.filter { it.debuggable }.map { it.pid }.toSet()
            emitJdwpProcessList(processIds, appProcessEntries.flowStatus)
          }
      } else {
        device.trackJdwp.stateFlow
          .takeWhile { jdwpProcessIdList -> !jdwpProcessIdList.flowStatus.isEndOfFlow }
          .collect { jdwpProcessIdList ->
            val processIds = jdwpProcessIdList.toSet()
            emitJdwpProcessList(processIds, jdwpProcessIdList.flowStatus)
          }
      }
    } finally {
      processesMutableFlow.value = JdwpProcessList(emptyList(), StateFlowStatus.endOfFlow)
    }
  }

  private suspend fun emitJdwpProcessList(processIds: Set<Int>, flowStatus: StateFlowStatus) {
    val processMap = device.jdwpProcessManager.addProcesses(processIds)
    processMap.values.toImmutableList().also { processList ->
      logger.verbose { "Emitting new list of JDWP processes: $processList" }
      processesMutableFlow.emit(JdwpProcessList(processList, flowStatus))
    }
  }
}
