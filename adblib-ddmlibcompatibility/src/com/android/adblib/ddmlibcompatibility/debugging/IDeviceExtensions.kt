/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.adblib.ddmlibcompatibility.debugging

import com.android.adblib.DeviceState
import com.android.ddmlib.AvdData
import com.android.ddmlib.IDevice
import com.google.common.util.concurrent.Futures
import java.util.concurrent.Future
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Returns AVD data associated with this device if it's already available or can be quickly retrieved. Returns `null` if the device is not
 * an emulator, is offline, or if the retrieval fails or times out.
 *
 * This is an alternative to using `IDevice.avdData` which returns a `Future` that completes only when successful.
 */
suspend fun IDevice.quickRetrieveAvdData(): AvdData? {
  this as? AdblibIDeviceWrapper
    ?: throw IllegalStateException("This method should only be used by AdblibIDeviceWrapper implementation of IDevice")

  // If avdData is already populated simply return it
  val avdDataFuture = avdData
  avdDataFuture.getDoneOrNull()?.let {
    return it
  }

  // Try waiting for the avd data while the device is online.
  // Note: 'avdData' access above triggers avd fetch if AVD data hasn't been retrieved yet.
  return withTimeoutOrNull(1.seconds) {
    combine(avdFetchStatusFlow, connectedDevice.deviceInfoFlow) { status, deviceInfo ->
        // Produces a boolean that indicates whether we should stop waiting for avdData to complete
        status == AdblibIDeviceWrapper.AvdFetchStatus.SUCCEEDED ||
          status == AdblibIDeviceWrapper.AvdFetchStatus.FAILED ||
          deviceInfo.deviceState != DeviceState.ONLINE
      }
      .first { it }
    avdDataFuture.getDoneOrNull()
  }
}

private fun <V> Future<V>.getDoneOrNull(): V? {
  return try {
    Futures.getDone(this)
  } catch (_: Exception) {
    null
  }
}
