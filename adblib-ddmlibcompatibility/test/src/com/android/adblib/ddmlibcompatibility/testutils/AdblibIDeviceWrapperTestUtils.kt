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
package com.android.adblib.ddmlibcompatibility.testutils

import com.android.adblib.AdbSession
import com.android.adblib.ConnectedDevice
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.waitForDevice
import com.android.adblib.waitUntilState
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import kotlin.time.Duration

suspend fun FakeAdbServerProviderRule.createConnectedDevice(
  serialNumber: String,
  deviceStatus: DeviceState.DeviceStatus = DeviceState.DeviceStatus.ONLINE,
  sdk: AndroidApiLevel = AndroidApiLevel(30),
  delayStdout: Duration = Duration.ZERO,
): Pair<ConnectedDevice, DeviceState> {
  // Connect a fake device to the server and wait for it to come online.
  val fakeDevice = fakeAdb.connectDevice(serialNumber, "test1", "test2", "model", sdk, DeviceState.HostConnectionType.USB)
  fakeDevice.delayStdout = delayStdout
  // Ensure the initialization sequence OFFLINE -> ONLINE started in
  // `fakeAdb.connectDevice` is complete, and we have a stable handle to the ConnectedDevice.
  val connectedDevice = waitForConnectedDevice(adbSession, serialNumber, DeviceState.DeviceStatus.ONLINE)

  if (deviceStatus != DeviceState.DeviceStatus.ONLINE) {
    fakeDevice.deviceStatus = deviceStatus
    connectedDevice.waitForDeviceState(deviceStatus)
  }

  return Pair(connectedDevice, fakeDevice)
}

suspend fun waitForConnectedDevice(session: AdbSession, serialNumber: String, deviceStatus: DeviceState.DeviceStatus): ConnectedDevice {
  val connectedDevice = session.connectedDevicesTracker.waitForDevice(serialNumber)

  connectedDevice.waitForDeviceState(deviceStatus)
  return connectedDevice
}

suspend fun ConnectedDevice.waitForDeviceState(deviceStatus: DeviceState.DeviceStatus) {
  val targetState = com.android.adblib.DeviceState.parseState(deviceStatus.state)
  waitUntilState(targetState)
}
