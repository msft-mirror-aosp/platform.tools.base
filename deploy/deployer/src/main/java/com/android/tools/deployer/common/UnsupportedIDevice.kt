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

import com.android.ddmlib.AdbHelper
import com.android.ddmlib.Client
import com.android.ddmlib.FileListingService
import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.InstallReceiver
import com.android.ddmlib.RawImage
import com.android.ddmlib.ScreenRecorderOptions
import com.android.ddmlib.ServiceInfo
import com.android.ddmlib.SyncService
import com.android.ddmlib.log.LogReceiver
import com.android.sdklib.AndroidVersion
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.InputStream
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * An unsupported implementation of [IDevice] used when connected device migration is enabled and real [IDevice] instances from DDMLIB are
 * not available.
 *
 * This is a temporary class and will be removed once the migration to adblib in the deployer is rolled out.
 *
 * All methods throw [UnsupportedOperationException].
 */
@Suppress("OVERRIDE_DEPRECATION")
class UnsupportedIDevice : IDevice {

  override fun getSerialNumber(): String = unsupported()

  override fun getName(): String = unsupported()

  override fun executeShellCommand(command: String?, receiver: IShellOutputReceiver?, maxTimeToOutputResponse: Int) = unsupported()

  override fun executeShellCommand(command: String?, receiver: IShellOutputReceiver?) = unsupported()

  override fun executeShellCommand(
    command: String?,
    receiver: IShellOutputReceiver?,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit?,
  ) = unsupported()

  override fun executeShellCommand(
    command: String?,
    receiver: IShellOutputReceiver?,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit?,
  ) = unsupported()

  override fun getSystemProperty(name: String): ListenableFuture<String> = unsupported()

  override fun getAvdName(): String? = unsupported()

  override fun getAvdPath(): String? = unsupported()

  override fun getState(): IDevice.DeviceState = unsupported()

  override fun getProperties(): MutableMap<String, String> = unsupported()

  override fun getPropertyCount(): Int = unsupported()

  override fun getProperty(name: String): String? = unsupported()

  override fun arePropertiesSet(): Boolean = unsupported()

  override fun getPropertySync(name: String?): String = unsupported()

  override fun getPropertyCacheOrSync(name: String?): String = unsupported()

  override fun supportsFeature(feature: IDevice.Feature): Boolean = unsupported()

  override fun supportsFeature(feature: IDevice.HardwareFeature): Boolean = unsupported()

  override fun services(): MutableMap<String, ServiceInfo>? = unsupported()

  override fun getMountPoint(name: String): String? = unsupported()

  override fun isOnline(): Boolean = unsupported()

  override fun isEmulator(): Boolean = unsupported()

  override fun isOffline(): Boolean = unsupported()

  override fun isBootLoader(): Boolean = unsupported()

  override fun hasClients(): Boolean = unsupported()

  override fun getClients(): Array<Client> = unsupported()

  override fun getClient(processName: String?): Client = unsupported()

  override fun getSyncService(): SyncService? = unsupported()

  override fun getFileListingService(): FileListingService = unsupported()

  override fun getScreenshot(): RawImage = unsupported()

  override fun getScreenshot(timeout: Long, unit: TimeUnit?): RawImage = unsupported()

  override fun startScreenRecorder(remoteFilePath: String, options: ScreenRecorderOptions, receiver: IShellOutputReceiver) = unsupported()

  override fun runEventLogService(receiver: LogReceiver?) = unsupported()

  override fun runLogService(logname: String?, receiver: LogReceiver?) = unsupported()

  override fun createForward(localPort: Int, remotePort: Int) = unsupported()

  override fun createForward(localPort: Int, remoteSocketName: String?, namespace: IDevice.DeviceUnixSocketNamespace?) = unsupported()

  override fun getClientName(pid: Int): String = unsupported()

  override fun pushFile(local: String, remote: String) = unsupported()

  override fun pullFile(remote: String?, local: String?) = unsupported()

  override fun installPackage(packageFilePath: String?, reinstall: Boolean, vararg extraArgs: String?) = unsupported()

  override fun installPackage(packageFilePath: String?, reinstall: Boolean, receiver: InstallReceiver?, vararg extraArgs: String?) =
    unsupported()

  override fun installPackage(
    packageFilePath: String?,
    reinstall: Boolean,
    receiver: InstallReceiver?,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit?,
    vararg extraArgs: String?,
  ) = unsupported()

  override fun installPackages(
    apks: MutableList<File>,
    reinstall: Boolean,
    installOptions: MutableList<String>,
    timeout: Long,
    timeoutUnit: TimeUnit,
  ) = unsupported()

  override fun syncPackageToDevice(localFilePath: String?): String = unsupported()

  override fun installRemotePackage(remoteFilePath: String?, reinstall: Boolean, vararg extraArgs: String?) = unsupported()

  override fun installRemotePackage(remoteFilePath: String?, reinstall: Boolean, receiver: InstallReceiver?, vararg extraArgs: String?) =
    unsupported()

  override fun installRemotePackage(
    remoteFilePath: String?,
    reinstall: Boolean,
    receiver: InstallReceiver?,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit?,
    vararg extraArgs: String?,
  ) = unsupported()

  override fun removeRemotePackage(remoteFilePath: String?) = unsupported()

  override fun uninstallPackage(packageName: String?): String = unsupported()

  override fun uninstallApp(applicationID: String?, vararg extraArgs: String?): String = unsupported()

  override fun reboot(into: String?) = unsupported()

  override fun root(): Boolean = unsupported()

  override fun isRoot(): Boolean = unsupported()

  override fun getBatteryLevel(): Int = unsupported()

  override fun getBatteryLevel(freshnessMs: Long): Int = unsupported()

  override fun getBattery(): Future<Int> = unsupported()

  override fun getBattery(freshnessTime: Long, timeUnit: TimeUnit): Future<Int> = unsupported()

  override fun getAbis(): MutableList<String> = unsupported()

  override fun getDensity(): Int = unsupported()

  override fun getLanguage(): String? = unsupported()

  override fun getRegion(): String? = unsupported()

  override fun getVersion(): AndroidVersion = unsupported()

  override fun executeRemoteCommand(
    command: String,
    rcvr: IShellOutputReceiver,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
  ) = unsupported()

  override fun executeRemoteCommand(command: String, rcvr: IShellOutputReceiver, maxTimeToOutputResponse: Long, maxTimeUnits: TimeUnit) =
    unsupported()

  override fun executeRemoteCommand(
    adbService: AdbHelper.AdbService,
    command: String,
    rcvr: IShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
    `is`: InputStream?,
  ) = unsupported()

  override fun executeRemoteCommand(
    adbService: AdbHelper.AdbService,
    command: String,
    rcvr: IShellOutputReceiver,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
    `is`: InputStream?,
  ) = unsupported()

  private fun unsupported(): Nothing = throw UnsupportedOperationException("Operation not supported on UnsupportedIDevice")
}
