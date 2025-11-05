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
package com.android.fakeadbserver

import com.android.fakeadbserver.hostcommandhandlers.ListDevicesCommandHandler
import com.android.sdklib.AndroidApiLevel

interface FakeDeviceCreator {

    /**
     * Connect a device to the FakeAdb server.
     *
     * @param deviceId           the unique device ID of the device, e.g. a device serial for a USB-connected device
     * @param manufacturer       the manufacturer name of the device
     * @param deviceModel        the model name of the device
     * @param release            an arbitrary string that will be used as the Android version
     * @param sdk                the SDK version of the device
     * @param hostConnectionType the simulated connection type to the device
     * @param maxSpeedMbps       the device's maximum supported speed (hardware limit)
     * @param negotiatedSpeedMbps the active link speed (connection limit)
     * @return a [DeviceState] which can be used to change the state of the device, e.g.
     * make it go online.
     */
    fun connectDevice(
        deviceId: String,
        manufacturer: String,
        deviceModel: String,
        release: String,
        sdk: AndroidApiLevel,
        hostConnectionType: DeviceState.HostConnectionType,
        maxSpeedMbps: Long = ListDevicesCommandHandler.DEFAULT_SPEED,
        negotiatedSpeedMbps: Long = ListDevicesCommandHandler.DEFAULT_SPEED,
    ): DeviceState

    /**
     * Removes a device from the FakeAdb server.
     *
     * @param deviceId the unique device ID of the device, e.g. a device serial for
     * a USB connected device.
     */
    fun disconnectDevice(deviceId: String)
}
