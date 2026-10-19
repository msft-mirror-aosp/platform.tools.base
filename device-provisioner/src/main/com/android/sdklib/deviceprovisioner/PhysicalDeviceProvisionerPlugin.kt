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
package com.android.sdklib.deviceprovisioner

import com.android.adblib.ConnectedDevice
import com.android.adblib.deviceProperties
import com.android.adblib.serialNumber
import com.android.adblib.utils.createChildScope
import com.android.annotations.concurrency.GuardedBy
import com.android.sdklib.deviceprovisioner.DeviceState.Connected
import com.android.sdklib.deviceprovisioner.DeviceState.Disconnected
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Plugin providing access to physical devices, connected over USB, Wi-Fi, or another network transport. */
class PhysicalDeviceProvisionerPlugin(val scope: CoroutineScope, private val deviceIcons: DeviceIcons) : DeviceProvisionerPlugin {

  companion object {
    const val PLUGIN_ID = "PhysicalDevice"
  }

  override val priority = 0

  /**
   * Index of devices by their serial number. This is the device serial number, i.e. the ro.serialno property, not the adb serial number,
   * which for WiFi devices has extra stuff around it.
   */
  @GuardedBy("devicesMutex") private val devicesBySerial = hashMapOf<String, PhysicalDeviceHandle>()
  /** Lock guarding [devicesBySerial] and the contents of [PhysicalDeviceHandle]. */
  private val devicesMutex = Mutex()

  private val _devices = MutableStateFlow(emptyList<DeviceHandle>())
  override val devices = _devices.asStateFlow()

  override suspend fun claim(device: ConnectedDevice): DeviceHandle? {
    val properties = device.deviceProperties().all().asMap()
    val serialNumber = checkNotNull(properties["ro.serialno"]) { "Missing [ro.serialno] property" }

    val deviceProperties = DeviceProperties.build {
      readAdbSerialNumber(device.serialNumber)
      disambiguator = serialNumber
      readCommonProperties(properties)
      readDeviceType(device, properties)
      populateDeviceInfoProto(PLUGIN_ID, device.serialNumber, properties, randomConnectionId())
      if (connectionType == null) {
        connectionType = ConnectionType.USB
      }
      resolution = Resolution.readFromDevice(device)
      icon =
        when (deviceType) {
          DeviceType.HANDHELD -> deviceIcons.handheld
          DeviceType.WEAR -> deviceIcons.wear
          DeviceType.TV -> deviceIcons.tv
          DeviceType.AUTOMOTIVE -> deviceIcons.automotive
          else -> deviceIcons.handheld
        }
    }

    // If a system property says it's virtual, it probably is. Otherwise, we'll assume it's physical.
    if (deviceProperties.isVirtual == true) {
      return null
    }

    val newState = Connected(deviceProperties, device)
    val handle = devicesMutex.withLock {
      when (val existing = devicesBySerial[serialNumber]) {
        null ->
          PhysicalDeviceHandle(serialNumber, scope.createChildScope(isSupervisor = true), newState).also {
            devicesBySerial[serialNumber] = it
            updateDevices()
          }
        // The device is already connected over another transport; record the additional connection.
        else -> existing.also { it.updateState(device, newState) }
      }
    }

    scope.launch {
      // Update device state on termination.
      device.awaitDisconnection()
      devicesMutex.withLock {
        handle.deviceDisconnected(device)
        if (handle.state !is Connected) {
          handle.scope.cancel()
          devicesBySerial.remove(serialNumber)
          updateDevices()
        }
      }
    }
    return handle
  }

  private fun updateDevices() {
    _devices.value = devicesBySerial.values.toList()
  }
}

/** Handle of a physical device, which may be reachable over several transports (USB, Wi-Fi, network) at the same time. */
private class PhysicalDeviceHandle(private val serialNumber: String, override val scope: CoroutineScope, initialState: Connected) :
  DeviceHandle {

  override val id = DeviceId(PhysicalDeviceProvisionerPlugin.PLUGIN_ID, false, "serial=$serialNumber")

  /**
   * The state of every ADB connection to this device. This is updated synchronously by [updateState] and [deviceDisconnected], rather than
   * by combining Flows, so that [state] and [awaitRelease] observe the update as soon as [PhysicalDeviceProvisionerPlugin.claim] releases
   * the devices mutex.
   */
  @GuardedBy("PhysicalDeviceProvisionerPlugin.devicesMutex")
  private val connections = linkedMapOf(initialState.connectedDevice to initialState)

  /** The properties of the most recently preferred connection, used to synthesize a [Disconnected] state. */
  private var lastProperties: DeviceProperties = initialState.properties

  /** The connections currently held by this handle, in preference order. */
  private val connectionsFlow = MutableStateFlow(listOf(initialState.connectedDevice))

  override val stateFlow = MutableStateFlow<DeviceState>(initialState)

  /** Recomputes [stateFlow] and [connectionsFlow] from [connections]. */
  private fun refresh() {
    // Prefer USB, then Wi-Fi, then other network transports, following the declaration order of [ConnectionType].
    val preferred = connections.values.sortedBy { it.properties.connectionType }
    connectionsFlow.value = preferred.map { it.connectedDevice }
    stateFlow.value =
      preferred.firstOrNull()?.also { lastProperties = it.properties }
        ?: Disconnected(lastProperties.toBuilder().apply { connectionType = null }.build())
  }

  override suspend fun awaitRelease(device: ConnectedDevice) {
    connectionsFlow.takeWhile { device in it }.collect()
  }

  fun updateState(device: ConnectedDevice, newState: Connected) {
    connections[device] = newState
    refresh()
  }

  fun deviceDisconnected(device: ConnectedDevice) {
    connections.remove(device)
    refresh()
  }

  override fun toString(): String = "PhysicalDeviceHandle for $id"
}
