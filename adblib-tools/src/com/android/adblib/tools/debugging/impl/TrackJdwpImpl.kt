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

import com.android.adblib.AdbSession
import com.android.adblib.ConnectedDevice
import com.android.adblib.StateFlowStatus
import com.android.adblib.adbLogger
import com.android.adblib.emptyProcessIdList
import com.android.adblib.property
import com.android.adblib.scope
import com.android.adblib.selector
import com.android.adblib.tools.AdbLibToolsProperties
import com.android.adblib.tools.debugging.JdwpProcessIdList
import com.android.adblib.tools.debugging.TrackJdwp
import com.android.adblib.tools.debugging.utils.serviceFlowToMutableStateFlow
import com.android.adblib.utils.logIOCompletionErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Implementation of [TrackJdwp] */
internal class TrackJdwpImpl(override val device: ConnectedDevice) : TrackJdwp {
  private val session: AdbSession
    get() = device.session

  private val scope: CoroutineScope
    get() = device.scope

  private val logger = adbLogger(session)

  private val mutableFlow = MutableStateFlow(startOfFlow)

  private val trackProcessesJob: Job by
    lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
      scope.launch { runCatching { trackProcesses() }.onFailure { throwable -> logger.logIOCompletionErrors(throwable) } }
    }

  override val stateFlow: StateFlow<JdwpProcessIdList> = mutableFlow.asStateFlow()
    get() {
      // Note: We rely on "lazy" to ensure the tracking coroutine is launched only once
      trackProcessesJob
      return field
    }

  private suspend fun trackProcesses() {
    device.serviceFlowToMutableStateFlow(
      serviceInvocation = { device ->
        device.session.deviceServices.trackJdwp(device.selector).map { list -> JdwpProcessIdList(list, StateFlowStatus.active) }
      },
      destinationStateFlow = mutableFlow,
      lastValue = endOfFlow,
      retryValue = { JdwpProcessIdList(emptyProcessIdList(), StateFlowStatus.retrying(it)) },
      retryDelay = session.property(AdbLibToolsProperties.TRACK_JDWP_RETRY_DELAY),
    )
  }

  companion object {
    private val startOfFlow = JdwpProcessIdList(emptyProcessIdList(), StateFlowStatus.startOfFlow)
    private val endOfFlow = JdwpProcessIdList(emptyProcessIdList(), StateFlowStatus.endOfFlow)
  }
}
