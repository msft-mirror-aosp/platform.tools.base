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
package com.android.tools.deployer.common

import com.android.adblib.ConnectedDevice
import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.InstallException
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.SimpleConnectedSocket
import com.android.ddmlib.SyncException
import com.android.ddmlib.TimeoutException
import com.android.sdklib.AndroidVersion
import java.io.IOException
import java.io.InputStream
import java.util.Optional
import java.util.concurrent.TimeUnit

class DeviceHolder(
  private val iDevice: IDevice,
  /**
   * [connectedDevice] is Optional to indicate whether we attempted to find a device corresponding to [iDevice] in the [AdbSession].
   * - null: Lookup was not attempted (e.g. in legacy paths or tests).
   * - Optional.empty(): Lookup was attempted but failed (device not found or error occurred).
   * - Optional.of(device): Lookup was attempted and succeeded.
   */
  private val connectedDevice: Optional<ConnectedDevice>?,
) {

  val version: AndroidVersion
    get() = iDevice.version

  val serialNumber: String
    get() = iDevice.serialNumber

  val abis: List<String>
    get() = iDevice.abis

  val name: String
    get() = iDevice.name

  val clients: Array<Client>
    get() = iDevice.clients

  val isRoot: Boolean
    get() = iDevice.isRoot

  @Throws(AdbCommandRejectedException::class, IOException::class, TimeoutException::class)
  fun rawExec2(executable: String, parameters: Array<String>): SimpleConnectedSocket {
    return iDevice.rawExec2(executable, parameters)
  }

  @Throws(AdbCommandRejectedException::class, ShellCommandUnresponsiveException::class, TimeoutException::class, IOException::class)
  fun executeShellCommand(
    command: String,
    receiver: IShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeToOutputResponseUnit: TimeUnit,
    `is`: InputStream?,
  ) {
    iDevice.executeShellCommand(command, receiver, maxTimeToOutputResponse, maxTimeToOutputResponseUnit, `is`)
  }

  @Throws(AdbCommandRejectedException::class, ShellCommandUnresponsiveException::class, TimeoutException::class, IOException::class)
  fun executeShellCommand(
    command: String,
    receiver: IShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeToOutputResponseUnit: TimeUnit,
  ) {
    iDevice.executeShellCommand(command, receiver, maxTimeToOutputResponse, maxTimeToOutputResponseUnit)
  }

  @Throws(AdbCommandRejectedException::class, ShellCommandUnresponsiveException::class, TimeoutException::class, IOException::class)
  fun executeBinderCommand(
    parameters: Array<String>,
    receiver: IShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeToOutputResponseUnit: TimeUnit,
    `is`: InputStream?,
  ) {
    iDevice.executeBinderCommand(parameters, receiver, maxTimeToOutputResponse, maxTimeToOutputResponseUnit, `is`)
  }

  @Throws(InstallException::class)
  fun uninstallPackage(packageName: String): String? {
    return iDevice.uninstallPackage(packageName)
  }

  fun supportsFeature(feature: IDevice.Feature): Boolean {
    return iDevice.supportsFeature(feature)
  }

  fun supportsFeature(feature: IDevice.HardwareFeature): Boolean {
    return iDevice.supportsFeature(feature)
  }

  @Throws(AdbCommandRejectedException::class, SyncException::class, TimeoutException::class, IOException::class)
  fun pushFile(local: String, remote: String) {
    iDevice.pushFile(local, remote)
  }

  @Throws(TimeoutException::class, AdbCommandRejectedException::class, IOException::class, ShellCommandUnresponsiveException::class)
  fun root(): Boolean {
    return iDevice.root()
  }

  fun forceStop(packageName: String) {
    iDevice.forceStop(packageName)
  }

  /**
   * Overridden to support tracking DeviceHolder instances in collections (e.g., in DeployerApplicationTerminator's HashSet). Equality is
   * defined by the underlying device identity (currently IDevice).
   */
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    val that = other as DeviceHolder
    return iDevice == that.iDevice
  }

  override fun hashCode(): Int {
    return iDevice.hashCode()
  }
}
