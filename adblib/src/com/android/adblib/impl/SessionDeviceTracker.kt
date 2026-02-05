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
package com.android.adblib.impl

import com.android.adblib.AdbFeatures
import com.android.adblib.AdbHostServices
import com.android.adblib.AdbSession
import com.android.adblib.DeviceInfo
import com.android.adblib.ErrorLine
import com.android.adblib.ListWithErrors
import com.android.adblib.StateFlowStatus
import com.android.adblib.TrackedDeviceList
import com.android.adblib.adbLogger
import com.android.adblib.utils.logIOCompletionErrors
import com.android.adblib.utils.logInfo
import com.android.adblib.warningsTracker
import java.io.EOFException
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

internal class SessionDeviceTracker(private val session: AdbSession, private val retryDelay: Duration) {

  private val logger = adbLogger(session.host)

  private val mutableFlow = MutableStateFlow(startOfFlow)

  private val trackDevicesJob: Job by
    lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
      session.scope.launch { runCatching { trackDevices() }.onFailure { throwable -> logger.logIOCompletionErrors(throwable) } }
    }

  /**
   * The [StateFlow] of [TrackedDeviceList], which contains both the list of [DeviceInfo] entries as well as a
   * [TrackedDeviceList.flowStatus] describing the current state of the connection (see [StateFlowStatus]).
   *
   * This flow continues to emit updates until [session] is closed at which time [TrackedDeviceList.flowStatus] would be set to
   * [StateFlowStatus.endOfFlow].
   */
  val stateFlow: StateFlow<TrackedDeviceList> = mutableFlow.asStateFlow()
    get() {
      // Note: We rely on "lazy" to ensure the tracking coroutine is launched only once
      trackDevicesJob
      return field
    }

  private suspend fun trackDevices() {
    var connectionId = 0
    try {
      startTrackDevices()
        .onStart {
          connectionId++
          logger.debug { "trackDevices() is starting, connection id=$connectionId" }
        }
        .map { deviceList ->
          logger.debug { "trackDevices(): mapping deviceList $deviceList" }
          session.warningsTracker.didRecover(key = this@SessionDeviceTracker.toString()).logInfo(logger) {
            "trackDevices() succeeded after a previous failure"
          }
          TrackedDeviceList(connectionId, deviceList, StateFlowStatus.active)
        }
        .retryWhen { throwable, _ ->
          if (throwable is CancellationException) {
            false
          } else {
            connectionId++
            if (throwable is EOFException) {
              session.warningsTracker.getLogAction(key = this@SessionDeviceTracker.toString(), "reached EOF").logInfo(logger) {
                "trackDevices() reached EOF, will retry in ${retryDelay.toMillis()} millis, connection id=$connectionId"
              }
            } else {
              session.warningsTracker.getLogAction(key = this@SessionDeviceTracker.toString(), throwable.message.orEmpty()).logInfo(
                logger,
                throwable,
              ) {
                "trackDevices() failed, will retry in ${retryDelay.toMillis()} millis, connection id=$connectionId"
              }
            }
            // emit TrackerDisconnected state while we wait to retry the collection
            emit(TrackedDeviceList(connectionId, emptyDeviceList, StateFlowStatus.retrying(throwable)))
            delay(retryDelay.toMillis())
            true
          }
        }
        .collect { mutableFlow.value = it }
    } finally {
      mutableFlow.value = TrackedDeviceList(connectionId, emptyDeviceList, StateFlowStatus.endOfFlow)
    }
  }

  private fun startTrackDevices() = flow {
    val format = pickBestFormat()
    emitAll(session.hostServices.trackDevices(format))
  }

  private suspend fun pickBestFormat(): AdbHostServices.DeviceInfoFormat {
    // If protobuffer output is not supported, we need to fallback to long text instead.
    return if (supportsDevicesListBinaryProto()) {
      AdbHostServices.DeviceInfoFormat.BINARY_PROTO_FORMAT
    } else {
      AdbHostServices.DeviceInfoFormat.LONG_FORMAT
    }
  }

  private suspend fun supportsDevicesListBinaryProto(): Boolean {
    return session.hostServices.hostFeatures().contains(AdbFeatures.DEVICE_LIST_BINARY_PROTO)
  }

  companion object {
    private val emptyDeviceList = ListWithErrors(emptyList<DeviceInfo>(), emptyList<ErrorLine>())
    private val startOfFlow = TrackedDeviceList(0, emptyDeviceList, StateFlowStatus.startOfFlow)
  }
}
