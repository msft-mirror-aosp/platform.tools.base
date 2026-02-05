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
import com.android.adblib.ConnectedDevice
import com.android.adblib.DeviceState
import com.android.adblib.adbLogger
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.ddmlibcompatibility.debugging.AdblibIDeviceWrapper
import com.android.adblib.serialNumber
import com.android.adblib.utils.createChildScope
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.IUserDataMap
import com.android.ddmlib.idevicemanager.IDeviceManager
import com.android.ddmlib.idevicemanager.IDeviceManagerListener
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

internal class AdbLibIDeviceManager(
  private val session: AdbSession,
  private val bridge: AndroidDebugBridge,
  private val iDeviceManagerListener: IDeviceManagerListener,
) : IDeviceManager {

  private val logger = adbLogger(session)

  private val scope = session.scope.createChildScope(isSupervisor = true)
  private var deviceTrackingJob: Job

  private val externallyVisibleDeviceList = ExternallyVisibleDevices()
  private val ddmlibEventQueue = DdmlibEventQueueWithShutdown(logger, "DeviceUpdates")
  private var initialDeviceListDone = false

  init {

    scope.launch { ddmlibEventQueue.runDispatcher() }

    deviceTrackingJob =
      scope.launch {
        // Using `IdentityHashMap` as `connectedDevicesTracker.connectedDevices` guarantees
        // to return the same instance for the same device
        val deviceMap = IdentityHashMap<ConnectedDevice, AdblibIDeviceWrapper>()
        val deviceInfoTrackingJobs = IdentityHashMap<ConnectedDevice, Job>()
        session.connectedDevicesTracker.connectedDevices.collect { value ->
          run {
            // Process added devices
            val added = value.filter { !deviceMap.containsKey(it) }
            val addedIDevices = mutableListOf<IDevice>()
            for (key in added) {
              val deviceStateHolder = DeviceStateHolder()
              val iDevice =
                AdblibIDeviceWrapper(key, bridge, deviceStateHolder::value).also {
                  it.computeUserDataIfAbsent(deviceStateHolderKey) { _ -> deviceStateHolder }
                }
              deviceMap[key] = iDevice
              addedIDevices.add(iDevice)
            }

            // Process removed devices
            val removed = deviceMap.keys.filter { !value.contains(it) }
            val removedIDevices = mutableListOf<IDevice>()
            for (key in removed) {
              deviceInfoTrackingJobs.remove(key)?.also {
                it.cancel("Cancelling DeviceInfo tracking for removed device [${key.serialNumber}]")
                it.join()
              }
              deviceMap.remove(key)?.also {
                it.deviceStateHolder.update(DeviceState.DISCONNECTED)
                removedIDevices.add(it)
              }
            }

            // flag the fact that we have build the list at least once
            initialDeviceListDone = true

            if (addedIDevices.isNotEmpty()) {
              // Set current deviceState before triggering `iDeviceManagerListener.addedDevices`
              for (addedConnectedDevice in added) {
                val iDevice = deviceMap.getValue(addedConnectedDevice)
                iDevice.deviceStateHolder.update(addedConnectedDevice.deviceInfoFlow.value.deviceState)
              }
              postAndWaitForCompletion(scope, "devices added") {
                externallyVisibleDeviceList.addAll(addedIDevices)
                iDeviceManagerListener.addedDevices(addedIDevices)
              }

              for (addedConnectedDevice in added) {
                val iDevice = deviceMap.getValue(addedConnectedDevice)
                deviceInfoTrackingJobs[addedConnectedDevice] =
                  scope.launch {
                    addedConnectedDevice.deviceInfoFlow
                      .map { it.deviceState }
                      // Match ddmlib behavior by not triggering device state
                      // change event for a `DISCONNECTED` device state value.
                      .takeWhile { it != DeviceState.DISCONNECTED }
                      .collect {
                        val stateChanged = iDevice.deviceStateHolder.update(it)
                        if (stateChanged) {
                          postAndWaitForCompletion(scope, "device state changed") { iDeviceManagerListener.deviceStateChanged(iDevice) }
                        }
                      }
                  }
              }
            }

            if (removedIDevices.isNotEmpty()) {
              postAndWaitForCompletion(scope, "devices removed") {
                externallyVisibleDeviceList.removeAll(removedIDevices)
                iDeviceManagerListener.removedDevices(removedIDevices)
              }
            }
          }
        }
      }
  }

  suspend fun shutdown() {
    deviceTrackingJob.cancel("${this::class.simpleName} is being shutdown")
    deviceTrackingJob.join()

    // At this time, no new events will be added to `ddmlibEventQueue` and so it's ok to
    // shut it down. The queue receiver will process elements currently in queue.
    ddmlibEventQueue.shutdown()

    // To match ddmlib implementation, we need to inform listeners about devices
    // being disconnected when terminating adb connection.
    iDeviceManagerListener.removedDevices(externallyVisibleDeviceList.getDeviceList())
    externallyVisibleDeviceList.clear()
  }

  override fun close() {
    scope.cancel("${this::class.simpleName} has been closed")
  }

  override fun getDevices(): List<IDevice> {
    return externallyVisibleDeviceList.getDeviceList()
  }

  override fun hasInitialDeviceList(): Boolean {
    return initialDeviceListDone
  }

  private suspend fun postAndWaitForCompletion(scope: CoroutineScope, name: String, handler: () -> Unit) {
    val processed = CompletableDeferred<Unit>(scope.coroutineContext.job)
    ddmlibEventQueue.post(scope, name) {
      try {
        handler()
      } finally {
        processed.complete(Unit)
      }
    }
    processed.await()
  }

  private val IDevice.deviceStateHolder: DeviceStateHolder
    get() {
      return getUserDataOrNull(deviceStateHolderKey) ?: throw AssertionError("IDevice instance should have a DeviceStateHolder value")
    }

  /** Container of the list [IDevices] that are externally visible from `IDevice` interface. Must be thread-safe. */
  private class ExternallyVisibleDevices {
    private val concurrentSet = ConcurrentHashMap.newKeySet<IDevice>()
    @Volatile private var tempLazyList: List<IDevice>? = null

    fun addAll(list: List<IDevice>) {
      concurrentSet.addAll(list)
      tempLazyList = null
    }

    fun removeAll(list: List<IDevice>) {
      concurrentSet.removeAll(list)
      tempLazyList = null
    }

    fun clear() {
      concurrentSet.clear()
      tempLazyList = null
    }

    fun getDeviceList(): List<IDevice> {
      // Allocate list only once per list of devices to alleviate
      // GC pressure of `IDevice.getDevices()`
      return tempLazyList ?: run { concurrentSet.toList().also { tempLazyList = it } }
    }
  }

  companion object {

    private val deviceStateHolderKey = IUserDataMap.Key<DeviceStateHolder>()
  }

  /** Control the value of deviceState, so that the listeners of the device change events properly observe device state changes. */
  private class DeviceStateHolder {

    @Volatile private var _value: DeviceState? = null

    val value: DeviceState?
      get() {
        return _value
      }

    fun update(newValue: DeviceState): Boolean {
      // `DeviceState.DISCONNECTED` is a final state, so do not change away from it
      return if (_value != newValue && _value != DeviceState.DISCONNECTED) {
        _value = newValue
        true
      } else {
        false
      }
    }
  }
}
