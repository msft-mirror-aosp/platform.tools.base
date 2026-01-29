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
package com.android.adblib.tools.debugging

import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.ListWithStateFlowStatus
import com.android.adblib.StateFlowStatus
import com.android.adblib.property
import com.android.adblib.tools.AdbLibToolsProperties.JDWP_PROCESS_TRACKER_SHOULD_USE_TRACK_APP_IF_AVAILABLE
import com.android.adblib.tools.debugging.impl.JdwpProcessTrackerImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks the list of active [JdwpProcess] processes on a given [ConnectedDevice].
 *
 * See the [processesFlow] property for the list of [processes][JdwpProcess] exposed
 * as a [StateFlow].
 */
interface JdwpProcessTracker {

    /**
     * The [ConnectedDevice] this [JdwpProcessTracker] is attached to.
     */
    val device: ConnectedDevice

    /**
     * A [CoroutineScope] tied to the lifecycle of this [JdwpProcessTracker], which is typically
     * tied to the lifecycle of the corresponding [device].
     */
    val scope: CoroutineScope

    /**
     * The [StateFlow] of active [JdwpProcess] for this [device].
     *
     * Every time a process is created or terminated, a new (immutable) [List] is emitted
     * to the [StateFlow]. However, it is guaranteed that [JdwpProcess] instances contained
     * in emitted lists remain the same for processes that remain active.
     *
     * Note: Once [scope] has completed, this [StateFlow] value is an empty list, and there will
     * be no additional updates to the flow.
     */
    val processesFlow: StateFlow<JdwpProcessList>

    companion object {

        /**
         * Creates a [JdwpProcessTracker] instance that actively tracks JDWP processes
         * of a given [device]. Use the [JdwpProcessTracker.processesFlow] property to access
         * or collect the list of active [JdwpProcess].
         *
         * @param device The [ConnectedDevice] to track processes on.
         * @param useTrackAppIfAvailable If `true`, the implementation will use the `track-app`
         *   device service if available. This is generally more efficient, and allows reusing
         *   an existing `track-app` service connection if one is already active.
         */
        fun create(
            device: ConnectedDevice,
            useTrackAppIfAvailable: Boolean = device.session.property(
                JDWP_PROCESS_TRACKER_SHOULD_USE_TRACK_APP_IF_AVAILABLE
            )
        ): JdwpProcessTracker {
            return JdwpProcessTrackerImpl(device, useTrackAppIfAvailable)
        }
    }
}

/**
 * An entry of the [JdwpProcessTracker.processesFlow], containing the list of [JdwpProcess].
 *
 * Use [status] property to get more information about the state of the connection.
 */
class JdwpProcessList(
    list: List<JdwpProcess>,
    flowStatus: StateFlowStatus
) : ListWithStateFlowStatus<JdwpProcess>(list, flowStatus)

fun Throwable.rethrowCancellation() {
    if (this is CancellationException) {
        throw this
    }
}

/**
 * Device cache key for [jdwpProcessTracker]
 */
private val jdwpProcessTrackerKey = CoroutineScopeCache.Key<JdwpProcessTracker>("JdwpProcessTracker device cache entry")

/**
 * The default [JdwpProcessTracker] for this device, giving access to the list of [JdwpProcess]
 * currently active on the device (through a [StateFlow]).
 */
val ConnectedDevice.jdwpProcessTracker: JdwpProcessTracker
    get() = this.cache.getOrPut(jdwpProcessTrackerKey) {
        JdwpProcessTracker.create(this)
    }
