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
package com.android.deploy.service

import com.android.adblib.AdbSession
import com.android.adblib.AdbSession.Companion.create
import com.android.adblib.AdbSessionHost
import com.android.adblib.ConnectedDevice
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.ddmlibcompatibility.AdbLibIDeviceManagerFactory
import com.android.adblib.device
import com.android.adblib.serialNumber
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.jdwpProcessTracker
import com.android.adblib.tools.debugging.processinventory.ProcessInventoryServerConnection
import com.android.adblib.tools.debugging.processinventory.installProcessInventoryJdwpProcessCommandDispatcherFactory
import com.android.adblib.tools.debugging.processinventory.installProcessInventoryJdwpProcessPropertiesCollectorFactory
import com.android.adblib.tools.debugging.processinventory.server.ProcessInventoryServerConfiguration
import com.android.adblib.tools.debugging.resumeProcess
import com.android.adblib.waitForDevice
import com.android.adblib.waitUntilOnline
import com.android.ddmlib.AdbHelper
import com.android.ddmlib.AdbInitOptions
import com.android.ddmlib.AndroidDebugBridge
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private val logger: Logger = Logger.getLogger(AdbHelper::class.java.getName())

object AdbHelper {

  /** Initializes the AndroidDebugBridge, using adblib or ddmlib. */
  fun initAndroidDebugBridge(): AdbSession {
    val host = AdbSessionHost()
    val session = create(host)

    val inventoryServerEnabled = {
      val enabled = System.getProperty("deploy.service.use.adblib.inventory.server").toBoolean()
      logger.info("adblib inventory server is $enabled")
      enabled
    }

    val inventoryServerConfig = GameToolsProcessInventoryServerConfiguration()
    val inventoryServerConnection = ProcessInventoryServerConnection.create(session, inventoryServerConfig)
    session.installProcessInventoryJdwpProcessPropertiesCollectorFactory(inventoryServerConnection, inventoryServerEnabled)
    session.installProcessInventoryJdwpProcessCommandDispatcherFactory(inventoryServerConnection, inventoryServerEnabled)
    val options = AdbInitOptions.Builder().setIDeviceManagerFactory(AdbLibIDeviceManagerFactory(session)).build()
    AndroidDebugBridge.init(options)
    return session
  }

  /**
   * Returns a [ConnectedDevice] for the given [serialNumber], or `null` if not found.
   *
   * Note: The [timeoutMs] is for the [session.connectedDevicesTracker] to transition out of its initial state, not for the device itself to
   * come online.
   */
  suspend fun getConnectedDevice(session: AdbSession, serialNumber: String, timeoutMs: Long = 5000): ConnectedDevice? {
    val tracker = session.connectedDevicesTracker
    val devices =
      withTimeoutOrNull(timeoutMs.milliseconds) {
        tracker.connectedDevices.first { !it.flowStatus.isStartOfFlow }
      } ?: return null

    devices.flowStatus.currentError?.let { error ->
      logger.warning("Error in connected devices tracker for device $serialNumber: ${error.message}")
    }

    return tracker.device(serialNumber)
  }

  suspend fun resumeProcess(session: AdbSession, serialNumber: String, pid: Int) {
    val process =
      withTimeoutOrNull(5.seconds) { waitForProcess(session, serialNumber, pid) }
        ?: throw Exception("Process $pid did not show up as isWaitingForDebugger on device $serialNumber")

    withTimeout(5.seconds) { process.resumeProcess() }
  }

  suspend fun waitForProcess(session: AdbSession, deviceSerial: String, pid: Int): JdwpProcess {
    val device = session.connectedDevicesTracker.waitForDevice(deviceSerial).also { it.waitUntilOnline() }

    val process = device.jdwpProcessTracker.processesFlow.mapNotNull { processList -> processList.firstOrNull { it.pid == pid } }.first()

    return process
  }
}

private class GameToolsProcessInventoryServerConfiguration : ProcessInventoryServerConfiguration {

  override val clientDescription: String = "GameTools-ProcessInventoryServer-Client"
  override val serverDescription: String = "GameTools-ProcessInventoryServer-Server"
}
