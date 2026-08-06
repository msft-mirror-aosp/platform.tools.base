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

import com.android.adblib.AdbSession
import com.android.adblib.ConnectedDevice
import com.android.adblib.deviceProperties
import com.android.adblib.property
import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.InstallException
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.SimpleConnectedSocket
import com.android.ddmlib.SyncException
import com.android.ddmlib.TimeoutException
import com.android.sdklib.AndroidVersion
import com.android.sdklib.AndroidVersionUtil
import com.android.tools.deploy.proto.Deploy
import java.io.IOException
import java.io.InputStream
import java.util.Optional
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val DEVICE_PROPERTIES_TIMEOUT = 2000.milliseconds

class DeviceHolder
@JvmOverloads
constructor(
  private val iDevice: IDevice,
  /**
   * The resolved [ConnectedDevice] from the [AdbSession] when adblib migration is enabled.
   *
   * The nullability of this [Optional] reflects the lookup outcome:
   * - `null`: Lookup was not attempted (legacy execution path or tests).
   * - `Optional.empty()`: Lookup was attempted, but no matching [ConnectedDevice] was found, e.g. because the device got disconnected.
   * - `Optional.of(device)`: Lookup was attempted and a matching [ConnectedDevice] was successfully resolved.
   */
  private val connectedDevice: Optional<ConnectedDevice>?,
  /** Whether the adblib migration is enabled. */
  private val useConnectedDevice: Boolean = false,
) {

  val version: AndroidVersion
    get() {
      return runMigratedOrElse(
        onLegacy = {
          // Retrieve using IDevice
          iDevice.version
        },
        onMigrated = { connectedDevice ->
          // Retrieve using ConnectedDevice
          val properties =
            try {
              runBlocking {
                // `version` is build from device properties and this call may take 2 seconds for
                // the properties to load
                withTimeout(DEVICE_PROPERTIES_TIMEOUT) {
                  if (connectedDevice.isPresent) {
                    connectedDevice.get().deviceProperties().allReadonly()
                  } else null
                }
              }
            } catch (_: Exception) {
              // Do not throw exceptions to match the iDevice.version behavior
              null
            }
          properties?.let { AndroidVersionUtil.androidVersionFromDeviceProperties(it) } ?: AndroidVersion.DEFAULT
        },
      )
    }

  val serialNumber: String
    get() = iDevice.serialNumber

  val abis: List<String>
    get() = iDevice.abis

  val name: String
    get() = iDevice.name

  fun getPidsForPackageName(packageName: String): List<Int> {
    return iDevice.clients.filter { packageName == it.clientData.packageName }.map { it.clientData.pid }
  }

  fun getArchForPid(pid: Int): Deploy.Arch {
    val client = iDevice.clients.firstOrNull { it.clientData.pid == pid } ?: return Deploy.Arch.ARCH_UNKNOWN
    val abi = client.clientData.abi ?: return Deploy.Arch.ARCH_UNKNOWN
    return when {
      abi.startsWith("32-bit") -> Deploy.Arch.ARCH_32_BIT
      abi.startsWith("64-bit") -> Deploy.Arch.ARCH_64_BIT
      else -> AdbClient.getArchForAbi(abi) ?: Deploy.Arch.ARCH_UNKNOWN
    }
  }

  val isRoot: Boolean
    get() = iDevice.isRoot

  @Throws(IOException::class)
  fun rawExec2(executable: String, parameters: Array<String>): SimpleConnectedSocket {
    try {
      return iDevice.rawExec2(executable, parameters)
    } catch (e: Exception) {
      when (e) {
        is AdbCommandRejectedException,
        is TimeoutException -> throw IOException(e)
        else -> throw e
      }
    }
  }

  @Throws(IOException::class)
  fun executeShellCommand(
    command: String,
    receiver: IShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeToOutputResponseUnit: TimeUnit,
  ) {
    try {
      iDevice.executeShellCommand(command, receiver, maxTimeToOutputResponse, maxTimeToOutputResponseUnit)
    } catch (e: Exception) {
      when (e) {
        is AdbCommandRejectedException,
        is ShellCommandUnresponsiveException,
        is TimeoutException -> throw IOException(e)
        else -> throw e
      }
    }
  }

  @Throws(IOException::class)
  fun executeBinderCommand(
    parameters: Array<String>,
    receiver: IShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeToOutputResponseUnit: TimeUnit,
    `is`: InputStream?,
  ) {
    try {
      iDevice.executeBinderCommand(parameters, receiver, maxTimeToOutputResponse, maxTimeToOutputResponseUnit, `is`)
    } catch (e: Exception) {
      when (e) {
        is AdbCommandRejectedException,
        is ShellCommandUnresponsiveException,
        is TimeoutException -> throw IOException(e)
        else -> throw e
      }
    }
  }

  @Throws(IOException::class)
  fun uninstallPackage(packageName: String): String? {
    try {
      return iDevice.uninstallPackage(packageName)
    } catch (e: InstallException) {
      throw IOException(e)
    }
  }

  val isRealPkgNameSupported: Boolean
    get() =
      runMigratedOrElse(
        onLegacy = { iDevice.supportsFeature(IDevice.Feature.REAL_PKG_NAME) },
        onMigrated = { connectedDevice ->
          if (!connectedDevice.isPresent) throw IOException("Device not found")
          version.isAtLeast(29, "R")
        },
      )

  val isSkipVerificationSupported: Boolean
    get() =
      runMigratedOrElse(
        onLegacy = { iDevice.supportsFeature(IDevice.Feature.SKIP_VERIFICATION) },
        onMigrated = { connectedDevice ->
          if (!connectedDevice.isPresent) throw IOException("Device not found")
          // Relaxed check: only support API >= 30, ignoring pre-release R previews (API 29 with "R" codename).
          version.isAtLeast(30)
        },
      )

  val isEmbedded: Boolean
    get() =
      runMigratedOrElse(
        onLegacy = { iDevice.supportsFeature(IDevice.HardwareFeature.EMBEDDED) },
        onMigrated = { connectedDevice ->
          if (!connectedDevice.isPresent) throw IOException("Device not found")
          runBlocking {
            val characteristics = connectedDevice.get().deviceProperties().allReadonly()["ro.build.characteristics"] ?: ""
            characteristics.split(",").contains(IDevice.HardwareFeature.EMBEDDED.characteristic)
          }
        },
      )

  @Throws(IOException::class)
  fun pushFile(local: String, remote: String) {
    try {
      iDevice.pushFile(local, remote)
    } catch (e: Exception) {
      when (e) {
        is AdbCommandRejectedException,
        is SyncException,
        is TimeoutException -> throw IOException(e)
        else -> throw e
      }
    }
  }

  @Throws(IOException::class)
  fun root(): Boolean {
    try {
      return iDevice.root()
    } catch (e: Exception) {
      when (e) {
        is AdbCommandRejectedException,
        is ShellCommandUnresponsiveException,
        is TimeoutException -> throw IOException(e)
        else -> throw e
      }
    }
  }

  private inline fun <T> runMigratedOrElse(
    crossinline onLegacy: () -> T,
    // `onMigrated` accepts an Optional<ConnectedDevice> since the ConnectedDevice lookup could have failed (e.g. because device has
    // disconnected)
    crossinline onMigrated: (Optional<ConnectedDevice>) -> T,
  ): T {
    // TODO: Once we add code that looks up `connectedDevice` using AdbSession.connectedDeviceTracker
    //  and feed it to DeviceHolder across the codebase we could remove the check for
    //  `connectedDevice != null`.
    return if (useConnectedDevice && connectedDevice != null) {
      onMigrated(connectedDevice)
    } else {
      onLegacy()
    }
  }

  companion object {
    @JvmStatic
    fun checkEnableUseConnectedDevice(session: AdbSession): Boolean {
      return session.property(DeployerProperties.USE_CONNECTED_DEVICE)
    }
  }
}
