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
package com.android.adblib.ddmlibcompatibility

import com.android.adblib.AdbSession
import com.android.annotations.concurrency.WorkerThread
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.idevicemanager.IDeviceManager
import com.android.ddmlib.idevicemanager.IDeviceManagerFactory
import com.android.ddmlib.idevicemanager.IDeviceManagerListener

/** Factory for [IDeviceManager] instances based on [AdbSession]. */
class AdbLibIDeviceManagerFactory(private val session: AdbSession) : IDeviceManagerFactory {

  override fun createIDeviceManager(bridge: AndroidDebugBridge): IDeviceManager {

    // Listener that notifies AndroidDebugBridge of changes to devices
    val listener: IDeviceManagerListener =
      object : IDeviceManagerListener {
        @WorkerThread
        override fun addedDevices(deviceList: MutableList<IDevice>) {
          if (bridge === AndroidDebugBridge.getBridge()) {
            for (device in deviceList) {
              AndroidDebugBridge.deviceConnected(device)
            }
          }
        }

        @WorkerThread
        override fun removedDevices(deviceList: MutableList<IDevice>) {
          if (bridge === AndroidDebugBridge.getBridge()) {
            for (device in deviceList) {
              AndroidDebugBridge.deviceDisconnected(device)
            }
          }
        }

        @WorkerThread
        override fun deviceStateChanged(device: IDevice) {
          if (bridge === AndroidDebugBridge.getBridge()) {
            AndroidDebugBridge.deviceChanged(device, IDevice.CHANGE_STATE)
          }
        }
      }

    return AdbLibIDeviceManager(session, bridge, listener)
  }
}
