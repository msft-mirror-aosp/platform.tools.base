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
package com.android.adblib.impl

import com.android.adblib.AdbSession
import com.android.adblib.ConnectionStatus
import com.android.adblib.ConnectionStatusTracker
import com.android.adblib.TrackedDeviceList
import com.android.adblib.adbLogger
import com.android.adblib.trackDevices
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ConnectionStatusTrackerImpl(override val session: AdbSession) : ConnectionStatusTracker {

  private val logger = adbLogger(session)

  private val connectionStatusStateFlow = MutableStateFlow(ConnectionStatus(false, 0))

  private val monitorJob: Job by lazy { launchConnectionTracking() }

  override val connectionStatus = connectionStatusStateFlow.asStateFlow()
    get() {
      monitorJob
      return field
    }

  private fun launchConnectionTracking(): Job {
    return session.scope.launch {
      logger.debug { "Starting connection status tracker coroutine" }
      try {
        session
          .trackDevices()
          .map { it.toConnectionStatus() }
          .distinctUntilChanged()
          .collect { connectionStatus -> connectionStatusStateFlow.value = connectionStatus }
      } finally {
        logger.debug { "Shutting down connection status tracker coroutine" }
        if (connectionStatusStateFlow.value.isConnected) {
          connectionStatusStateFlow.update { ConnectionStatus(false, it.connectionId + 1) }
        }
      }
    }
  }

  private fun TrackedDeviceList.toConnectionStatus(): ConnectionStatus {
    if (!flowStatus.isActive) {
      return ConnectionStatus(false, connectionId)
    }

    return ConnectionStatus(true, connectionId)
  }
}
