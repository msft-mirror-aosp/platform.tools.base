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

import com.android.adblib.AdbDeviceServices
import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.ProcessIdList
import com.android.adblib.getOrPutSynchronized
import com.android.adblib.tools.debugging.impl.TrackJdwpImpl
import kotlinx.coroutines.flow.StateFlow

/**
 * A thread-safe wrapper for [AdbDeviceServices.trackJdwp] that exposes a [StateFlow] of
 * [JdwpProcessIdList] for a given [ConnectedDevice].
 *
 * The implementation uses a single underlying [AdbDeviceServices.trackJdwp] invocation
 * that is shared by all collectors of the flow.
 *
 * Use the [ConnectedDevice.trackJdwp] extension to access this component.
 */
interface TrackJdwp {

    /**
     * The [ConnectedDevice] that this [TrackApp] is dedicated to
     */
    val device: ConnectedDevice

    /**
     * The [StateFlow] of [JdwpProcessIdList], which contains both the list JDWP process IDs,
     * entries and a [JdwpProcessIdList.flowStatus] describing the current state of the
     * connection (see [StateFlowStatus])
     */
    val stateFlow: StateFlow<JdwpProcessIdList>
}

/**
 * An entry of the [TrackJdwp.stateFlow], containing the list of JDWP process IDs.
 *
 * Use [status] property to get more information about the state of the connection.
 */
class JdwpProcessIdList(
    list: ProcessIdList,
    flowStatus: StateFlowStatus
) : ListWithStateFlowStatus<Int>(list, flowStatus)

/**
 * The [TrackJdwp] instance dedicated to this [ConnectedDevice]
 */
val ConnectedDevice.trackJdwp: TrackJdwp
    get() = cache.getOrPutSynchronized(trackJdwpKey) {
        TrackJdwpImpl(this)
    }

private val trackJdwpKey = CoroutineScopeCache.Key<TrackJdwp>("TrackJdwp")
