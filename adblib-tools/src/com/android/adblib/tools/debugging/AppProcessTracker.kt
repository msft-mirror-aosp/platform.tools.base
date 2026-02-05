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
package com.android.adblib.tools.debugging

import com.android.adblib.AdbDeviceServices
import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.ListWithStateFlowStatus
import com.android.adblib.StateFlowStatus
import com.android.adblib.tools.debugging.impl.AppProcessTrackerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks the list of active [AppProcess] processes on a given [ConnectedDevice].
 *
 * See the [appProcessFlow] property for the list of [processes][AppProcess] exposed as a [StateFlow].
 *
 * Note: To prevent running multiple [AdbDeviceServices.trackApp] services concurrently, use the [ConnectedDevice.appProcessTracker]
 * extensions to access the [AppProcessTracker] for a given [ConnectedDevice].
 */
interface AppProcessTracker {

  /** The [ConnectedDevice] this [AppProcessTracker] is attached to. */
  val device: ConnectedDevice

  /**
   * A [CoroutineScope] tied to the lifecycle of this [AppProcessTracker], which is typically tied to the lifecycle of the corresponding
   * [device].
   */
  val scope: CoroutineScope

  /**
   * The [StateFlow] of active [AppProcess] for this [device].
   *
   * Every time a process is created or terminated, a new (immutable) [List] is emitted to the [StateFlow]. However, it is guaranteed that
   * [AppProcess] instances contained in emitted lists remain the same for processes that remain active.
   *
   * Note: Once [scope] has completed, this [StateFlow] value is an empty list, and there will be no additional updates to the flow.
   */
  val appProcessFlow: StateFlow<AppProcessList>

  companion object {

    /**
     * Returns a [AppProcessTracker] instance that actively tracks "app processes" of a given [device]. Use the
     * [AppProcessTracker.appProcessFlow] property to access or collect the list of active [AppProcess].
     */
    fun create(device: ConnectedDevice): AppProcessTracker {
      return AppProcessTrackerImpl(device)
    }
  }
}

/**
 * An entry of the [AppProcessTracker.appProcessFlow], containing the list of [AppProcess].
 *
 * Use [status] property to get more information about the state of the connection.
 */
class AppProcessList(list: List<AppProcess>, flowStatus: StateFlowStatus) : ListWithStateFlowStatus<AppProcess>(list, flowStatus)

/** Device cache key for [appProcessTracker] */
private val appProcessTrackerKey = CoroutineScopeCache.Key<AppProcessTracker>("AppProcessTracker device cache entry")

/**
 * The default [AppProcessTracker] for this device, giving access to the list of [AppProcessTracker] currently active on the device (through
 * a [StateFlow]).
 */
val ConnectedDevice.appProcessTracker: AppProcessTracker
  get() = this.cache.getOrPut(appProcessTrackerKey) { AppProcessTracker.create(this) }
