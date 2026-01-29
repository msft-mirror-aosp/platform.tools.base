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
import com.android.adblib.AdbFeatures
import com.android.adblib.AppProcessEntry
import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.ListWithStateFlowStatus
import com.android.adblib.StateFlowStatus
import com.android.adblib.activityManager
import com.android.adblib.getOrPutSynchronized
import com.android.adblib.hasAvailableFeature
import com.android.adblib.tools.debugging.impl.TrackAppImpl
import kotlinx.coroutines.flow.StateFlow

/**
 * A thread-safe wrapper for [AdbDeviceServices.trackApp] that exposes a [StateFlow] of
 * [AppProcessEntryList] for a given [ConnectedDevice].
 *
 * The implementation uses a single underlying [AdbDeviceServices.trackApp] invocation
 * that is shared by all collectors of the flow.
 *
 * Use the [ConnectedDevice.trackApp] extension to access this component.
 *
 * Note: The caller is responsible for calling [ConnectedDevice.isTrackAppSupported]
 * to make sure [AdbDeviceServices.trackApp] is supported by the device, otherwise the
 * [StateFlow] will only contains entries of [StateFlowStatus.isRetrying] status.
 */
interface TrackApp {
    /**
     * The [ConnectedDevice] that this [TrackApp] is dedicated to
     */
    val device: ConnectedDevice

    /**
     * The [StateFlow] of [AppProcessEntryList], which contains both the list of [AppProcessEntry]
     * entries as well as a [AppProcessEntryList.status] describing the current state of the
     * connection (see [StateFlowStatus])
     */
    val stateFlow: StateFlow<AppProcessEntryList>
}

/**
 * An entry of the [TrackApp.stateFlow], containing the list of [AppProcessEntry].
 *
 * Use [status] property to get more information about the state of the connection.
 */
class AppProcessEntryList(
    list: List<AppProcessEntry>,
    flowStatus: StateFlowStatus
) : ListWithStateFlowStatus<AppProcessEntry>(list, flowStatus)


/**
 * The [TrackApp] instance dedicated to this [ConnectedDevice]
 */
val ConnectedDevice.trackApp: TrackApp
    get() = cache.getOrPutSynchronized(trackAppKey) {
        TrackAppImpl(this)
    }

private val trackAppKey = CoroutineScopeCache.Key<TrackApp>("TrackApp")

/**
 * Whether [AdbFeatures.TRACK_APP] is supported by this [ConnectedDevice]. If `false`,
 * [TrackApp] will only emit errors to the [TrackApp.stateFlow].
 */
suspend fun ConnectedDevice.isTrackAppSupported(): Boolean {
    // Note: "track-app" is only supported on API 31+ (Android "S"), but there
    // is an official feature for it.
    return hasAvailableFeature(AdbFeatures.TRACK_APP)
}

/**
 * Whether [trackApp] **and** [AdbFeatures.APP_INFO] are supported, meaning
 * [AppProcessEntry] instances will be populated with [AppProcessEntry.processName],
 * [AppProcessEntry.packageNames], etc. This should return `true` for API 36 and later.
 */
suspend fun ConnectedDevice.isAppInfoSupported(): Boolean {
    // Note: In theory, `app_info` implies `track_app`, but we check anyway.
    // `track_app` was introduced around API 31, whereas `app_info` was introduced
    // around API 36.
    return if (!isTrackAppSupported()) {
        false
    }
    // ADB server and the ADB daemon ("adbd") on the device needs to support `app_info`...
    else if (!hasAvailableFeature(AdbFeatures.APP_INFO)) {
        false
    } else {
        val capabilitiesResult = activityManager.capabilities() ?: return false

        // ...as well as the Android VM...
        // ...and the Android Framework
        capabilitiesResult.vmCapabilities.contains(AdbFeatures.APP_INFO) &&
                capabilitiesResult.frameworkCapabilities.contains(AdbFeatures.APP_INFO)
    }
}
