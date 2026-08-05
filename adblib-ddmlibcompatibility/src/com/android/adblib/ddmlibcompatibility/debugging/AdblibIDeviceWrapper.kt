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
package com.android.adblib.ddmlibcompatibility.debugging

import com.android.adblib.AdbDeviceSyncServices
import com.android.adblib.AdbFailResponseException
import com.android.adblib.AdbProtocolErrorException
import com.android.adblib.ConnectedDevice
import com.android.adblib.DeviceSelector
import com.android.adblib.INFINITE_DURATION
import com.android.adblib.RemoteFileMode
import com.android.adblib.SocketSpec
import com.android.adblib.adbLogger
import com.android.adblib.availableFeatures
import com.android.adblib.ddmlibcompatibility.AdbLibDdmlibCompatibilityProperties.RUN_BLOCKING_LEGACY_DEFAULT_TIMEOUT
import com.android.adblib.ddmlibcompatibility.AdbLibIDeviceManager
import com.android.adblib.ddmlibcompatibility.DEFAULT_DDMLIB_TIMEOUT
import com.android.adblib.deviceProperties
import com.android.adblib.isRoot
import com.android.adblib.property
import com.android.adblib.rootAndWait
import com.android.adblib.scope
import com.android.adblib.serialNumber
import com.android.adblib.syncRecv
import com.android.adblib.syncSend
import com.android.adblib.syncStat
import com.android.adblib.tools.EmulatorCommandException
import com.android.adblib.tools.localConsoleAddress
import com.android.adblib.tools.openEmulatorConsole
import com.android.adblib.utils.logIOCompletionErrors
import com.android.adblib.waitUntilOnline
import com.android.adblib.withErrorTimeout
import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.AdbHelper
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.AvdData
import com.android.ddmlib.Client
import com.android.ddmlib.FileListingService
import com.android.ddmlib.IDevice
import com.android.ddmlib.IDevice.DeviceState
import com.android.ddmlib.IDevice.RE_EMULATOR_SN
import com.android.ddmlib.IDeviceSharedImpl
import com.android.ddmlib.IDeviceSharedImpl.INSTALL_TIMEOUT_MINUTES
import com.android.ddmlib.IDeviceUsageTracker
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.IUserDataMap
import com.android.ddmlib.InstallMetrics
import com.android.ddmlib.InstallReceiver
import com.android.ddmlib.Log
import com.android.ddmlib.ProfileableClient
import com.android.ddmlib.RawImage
import com.android.ddmlib.ScreenRecorderOptions
import com.android.ddmlib.ServiceInfo
import com.android.ddmlib.SimpleConnectedSocket
import com.android.ddmlib.SyncException
import com.android.ddmlib.SyncService
import com.android.ddmlib.idevicemanager.IDeviceManagerListener
import com.android.ddmlib.internal.UserDataMapImpl
import com.android.ddmlib.log.LogReceiver
import com.android.sdklib.AndroidVersion
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.time.Duration
import java.util.ArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Implementation of [IDevice] that entirely relies on adblib services, i.e. does not depend on implementation details of ddmlib.
 *
 * @param deviceState the device state that should be exposed via [IDevice.getState] (or other public methods of [IDevice]). Note the value
 *   may be out of sync with the value of [ConnectedDevice] state, because the device state values exposed through public entry points need
 *   to be updated deterministically when [IDeviceManagerListener.deviceStateChanged] events are invoked by [AdbLibIDeviceManager].
 */
internal class AdblibIDeviceWrapper(
  internal val connectedDevice: ConnectedDevice,
  bridge: AndroidDebugBridge,
  private val deviceState: () -> com.android.adblib.DeviceState?,
) : IDevice {

  private val logger = adbLogger(connectedDevice.session)

  private val iDeviceUsageTracker = bridge.getiDeviceUsageTracker()

  private val iDeviceSharedImpl = IDeviceSharedImpl(this)
  private val deviceClientManager =
    AdbLibClientManagerFactory.createClientManager(connectedDevice.session).createDeviceClientManager(bridge, this)

  /** Name and path of the AVD */
  @Volatile private var mAvdName: String? = null
  @Volatile private var mAvdPath: String? = null

  /**
   * We use a [SettableFuture] because we want to return the same future every time [getAvdData] is called. If the fetch attempt fails, the
   * future is set to `null` to avoid hanging.
   */
  private val mAvdDataFuture = SettableFuture.create<AvdData?>()

  // We need to cache all properties so that they could be returned by getProperties()
  internal val propertiesMapRef = AtomicReference<Map<String, String>?>(null)

  private val inProgressAllPropertiesJob = AtomicReference<Job?>(null)

  private val pendingPropertyFutures = HashMap<String, MutableList<SettableFuture<String?>>>()

  init {
    connectedDevice.scope.launch { fetchAvdData() }
    prefetchProperties()
  }

  private val mUserDataMap = UserDataMapImpl()

  override fun getName(): String {
    return iDeviceSharedImpl.name
  }

  override fun executeShellCommand(command: String, receiver: IShellOutputReceiver) {
    logUsage(IDeviceUsageTracker.Method.EXECUTE_SHELL_COMMAND_1) {
      // This matches the behavior of `DeviceImpl`

      executeRemoteCommand(command, receiver, maxTimeToOutputResponse = DEFAULT_DDMLIB_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    }
  }

  @Deprecated("")
  override fun executeShellCommand(command: String, receiver: IShellOutputReceiver, maxTimeToOutputResponse: Int) {
    logUsage(IDeviceUsageTracker.Method.EXECUTE_SHELL_COMMAND_2) {
      // This matches the behavior of `DeviceImpl`
      executeRemoteCommand(command, receiver, maxTimeToOutputResponse.toLong(), TimeUnit.MILLISECONDS)
    }
  }

  override fun executeShellCommand(command: String, receiver: IShellOutputReceiver, maxTimeToOutputResponse: Long, maxTimeUnits: TimeUnit) {
    logUsage(IDeviceUsageTracker.Method.EXECUTE_SHELL_COMMAND_3) {
      // This matches the behavior of `DeviceImpl`
      executeRemoteCommand(command, receiver, 0L, maxTimeToOutputResponse, maxTimeUnits)
    }
  }

  override fun executeShellCommand(
    command: String,
    receiver: IShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
    `is`: InputStream?,
  ) {
    logUsage(IDeviceUsageTracker.Method.EXECUTE_SHELL_COMMAND_4) {
      // Note that `AdbHelper.AdbService.EXEC` is passed down to match the behavior of `DeviceImpl`
      executeRemoteCommand(AdbHelper.AdbService.EXEC, command, receiver, 0L, maxTimeToOutputResponse, maxTimeUnits, `is`)
    }
  }

  override fun executeShellCommand(
    command: String,
    receiver: IShellOutputReceiver,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
  ) {
    logUsage(IDeviceUsageTracker.Method.EXECUTE_SHELL_COMMAND_5) {
      // This matches the behavior of `DeviceImpl`
      executeRemoteCommand(command, receiver, maxTimeout, maxTimeToOutputResponse, maxTimeUnits)
    }
  }

  override fun getSystemProperty(name: String): ListenableFuture<String?> {
    // NOTE: Calling `logUsage` here would log too many events, so let's not log these events
    if (name.startsWith("ro.")) {
      propertiesMapRef.get()?.let {
        return Futures.immediateFuture(it[name])
      }
    }

    val future = SettableFuture.create<String?>()
    synchronized(pendingPropertyFutures) { pendingPropertyFutures.computeIfAbsent(name) { ArrayList() }.add(future) }
    triggerAllPropertiesFetch()
    return future
  }

  override fun getSerialNumber(): String {
    // NOTE: Calling `logUsage` here would log too many events, so let's not log these events
    return connectedDevice.serialNumber
  }

  @Deprecated("Prefer using getAvdData()")
  override fun getAvdName(): String? = logUsage(IDeviceUsageTracker.Method.GET_AVD_NAME) { mAvdName }

  @Deprecated("Prefer using getAvdData()")
  override fun getAvdPath(): String? = logUsage(IDeviceUsageTracker.Method.GET_AVD_PATH) { mAvdPath }

  override fun getAvdData(): ListenableFuture<AvdData?> = logUsage(IDeviceUsageTracker.Method.GET_AVD_DATA) { mAvdDataFuture }

  private suspend fun fetchAvdData() {
    if (!isEmulator) {
      mAvdDataFuture.set(null)
      return
    }

    try {
      // Wait until the device goes online before creating avd data.
      // Note that extra care should be taken when relying on `connectedDevice` state
      // instead of `AdblibIDeviceWrapper.deviceStateProvider`. In this case it's ok to use
      // the former as all we care about is populating avd data as soon as possible.
      connectedDevice.waitUntilOnline()

      val emulatorMatchResult =
        RE_EMULATOR_SN.toRegex().matchEntire(serialNumber) ?: error("Invalid emulator serial pattern: $serialNumber")
      val port = emulatorMatchResult.groupValues[1].toIntOrNull() ?: error("Invalid emulator port: $serialNumber")

      connectedDevice.session.openEmulatorConsole(localConsoleAddress(port)).use { console ->
        val avdName = kotlin.runCatching { console.avdName() }.getOrNull()
        val path = kotlin.runCatching { console.avdPath() }.getOrNull()
        val avdData = AvdData(avdName, path)

        mAvdName = avdData.name
        mAvdPath = avdData.path
        mAvdDataFuture.set(avdData)
      }
    } catch (t: Throwable) {
      if (t is CancellationException) {
        throw t
      }
      if (t is EmulatorCommandException) {
        logger.warn(t, "Couldn't open emulator console")
      } else {
        logger.logIOCompletionErrors(t, "Failed to retrieve AVD data")
      }
    } finally {
      // Ensure the future is ALWAYS completed
      if (!mAvdDataFuture.isDone) {
        mAvdDataFuture.set(null)
      }
    }
  }

  override fun getState(): DeviceState? {
    return deviceState()?.let {
      // `DISCONNECTED` is not a device state returned from `adb devices` and so we should not
      // use `DeviceState.getState` method which is not reliable in this case.
      if (it == com.android.adblib.DeviceState.DISCONNECTED) {
        return DeviceState.DISCONNECTED
      }
      DeviceState.getState(it.state)
    }
  }

  @Deprecated("")
  override fun getProperties(): Map<String, String> =
    logUsage(IDeviceUsageTracker.Method.GET_PROPERTIES) { propertiesMapRef.get() ?: emptyMap() }

  @Deprecated("")
  override fun getPropertyCount(): Int = logUsage(IDeviceUsageTracker.Method.GET_PROPERTY_COUNT) { propertiesMapRef.get()?.size ?: 0 }

  override fun getProperty(name: String): String? =
    logUsage(IDeviceUsageTracker.Method.GET_PROPERTY) {
      val timeout = if (propertiesMapRef.get() == null) INITIAL_GET_PROP_TIMEOUT_MS else GET_PROP_TIMEOUT_MS
      try {
        return@logUsage getSystemProperty(name).get(timeout, TimeUnit.MILLISECONDS)
      } catch (_: InterruptedException) {
        // ignore
      } catch (_: ExecutionException) {
        // ignore
      } catch (_: TimeoutException) {
        // ignore
      }
      null
    }

  override fun arePropertiesSet(): Boolean = logUsage(IDeviceUsageTracker.Method.ARE_PROPERTIES_SET) { propertiesMapRef.get() != null }

  @Deprecated("")
  override fun getPropertySync(name: String?): String {
    unsupportedMethod()
  }

  @Deprecated("")
  override fun getPropertyCacheOrSync(name: String?): String {
    unsupportedMethod()
  }

  private fun prefetchProperties() {
    triggerAllPropertiesFetch()
  }

  private fun triggerAllPropertiesFetch() {
    // Quick exit to avoid creating jobs unnecessarily when it's already running
    if (inProgressAllPropertiesJob.get() != null) return

    val job =
      connectedDevice.scope.launch(start = CoroutineStart.LAZY) {
        runCatching {
            connectedDevice.waitUntilOnline()
            val props = connectedDevice.deviceProperties().all().associate { it.name to it.value }
            propertiesMapRef.set(props)

            // Resolve all pending futures and clear the map
            val snapshot =
              synchronized(pendingPropertyFutures) {
                val copy = HashMap(pendingPropertyFutures)
                pendingPropertyFutures.clear()
                copy
              }

            snapshot.forEach { (propName, futures) ->
              val value = props[propName]
              futures.forEach { it.set(value) }
            }
          }
          .onFailure { t ->
            val snapshot =
              synchronized(pendingPropertyFutures) {
                val copy = HashMap(pendingPropertyFutures)
                pendingPropertyFutures.clear()
                copy
              }
            snapshot.forEach { (_, futures) -> futures.forEach { it.setException(t) } }
          }
          .also {
            inProgressAllPropertiesJob.set(null)
            // One last check to avoid the race condition where a request was added
            // just before we set the job to null.
            val hasPending = synchronized(pendingPropertyFutures) { pendingPropertyFutures.isNotEmpty() }
            if (hasPending) {
              triggerAllPropertiesFetch()
            }
          }
      }

    if (inProgressAllPropertiesJob.compareAndSet(null, job)) {
      job.start()
    } else {
      // Discard a lazy job since another thread won the `compareAndSet`
      job.cancel()
    }
  }

  override fun supportsFeature(feature: IDevice.Feature): Boolean {
    // NOTE: Calling `logUsage` here would log too many events, so let's not log these events
    val availableFeatures: Set<String> =
      try {
        runBlockingLegacy { connectedDevice.availableFeatures() }
      } catch (e: IOException) {
        // Note that this block handles `AdbFailResponseException` as well.
        // ignore to match the behavior in the `DeviceImpl`
        logger.warn(e, "Error querying `availableFeatures`")
        emptySet()
      }

    return iDeviceSharedImpl.supportsFeature(feature, availableFeatures)
  }

  override fun supportsFeature(feature: IDevice.HardwareFeature): Boolean =
    logUsage(IDeviceUsageTracker.Method.SUPPORTS_FEATURE_1) { iDeviceSharedImpl.supportsFeature(feature) }

  override fun services(): MutableMap<String, ServiceInfo> = logUsage(IDeviceUsageTracker.Method.SERVICES) { iDeviceSharedImpl.services() }

  override fun getMountPoint(name: String): String? {
    unsupportedMethod()
  }

  override fun toString(): String {
    return serialNumber
  }

  override fun isOnline(): Boolean =
    logUsage(IDeviceUsageTracker.Method.IS_ONLINE) { deviceState() == com.android.adblib.DeviceState.ONLINE }

  override fun isEmulator(): Boolean {
    return serialNumber.matches(RE_EMULATOR_SN.toRegex())
  }

  override fun isOffline(): Boolean {
    return deviceState() == com.android.adblib.DeviceState.OFFLINE
  }

  override fun isBootLoader(): Boolean {
    return deviceState() == com.android.adblib.DeviceState.BOOTLOADER
  }

  override fun hasClients(): Boolean {
    unsupportedMethod()
  }

  override fun getClients(): Array<Client> {
    return deviceClientManager.clients.toTypedArray()
  }

  /** Even though the argument is called applicationName, we actually look for the processName to maintain backward compatibility. */
  override fun getClient(processName: String?): Client? {
    return clients.firstOrNull { processName == it.clientData.processName }
  }

  override fun getProfileableClients(): Array<ProfileableClient> {
    return deviceClientManager.profileableClients.toTypedArray()
  }

  override fun getSyncService(): SyncService {
    unsupportedMethod()
  }

  override fun getFileListingService(): FileListingService {
    unsupportedMethod()
  }

  override fun getScreenshot(): RawImage {
    unsupportedMethod()
  }

  override fun getScreenshot(timeout: Long, unit: TimeUnit?): RawImage {
    unsupportedMethod()
  }

  override fun startScreenRecorder(remoteFilePath: String, options: ScreenRecorderOptions, receiver: IShellOutputReceiver) {
    unsupportedMethod()
  }

  override fun runEventLogService(receiver: LogReceiver?) {
    unsupportedMethod()
  }

  override fun runLogService(logname: String?, receiver: LogReceiver?) {
    unsupportedMethod()
  }

  override fun createForward(localPort: Int, remotePort: Int) {
    logUsage(IDeviceUsageTracker.Method.CREATE_FORWARD_1) {
      runBlockingLegacy {
        mapToDdmlibException {
          val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)
          connectedDevice.session.hostServices.forward(
            deviceSelector,
            local = SocketSpec.Tcp(localPort),
            remote = SocketSpec.Tcp(remotePort),
            rebind = true,
          )
        }
      }
    }
  }

  override fun createForward(localPort: Int, remoteSocketName: String, namespace: IDevice.DeviceUnixSocketNamespace) {
    logUsage(IDeviceUsageTracker.Method.CREATE_FORWARD_2) {
      runBlockingLegacy {
        mapToDdmlibException {
          val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)
          val remoteSocketSpec =
            when (namespace) {
              IDevice.DeviceUnixSocketNamespace.ABSTRACT -> SocketSpec.LocalAbstract(remoteSocketName)

              IDevice.DeviceUnixSocketNamespace.RESERVED -> SocketSpec.LocalReserved(remoteSocketName)

              IDevice.DeviceUnixSocketNamespace.FILESYSTEM -> SocketSpec.LocalFileSystem(remoteSocketName)
            }
          connectedDevice.session.hostServices.forward(deviceSelector, local = SocketSpec.Tcp(localPort), remoteSocketSpec, rebind = true)
        }
      }
    }
  }

  override fun removeForward(localPort: Int) {
    logUsage(IDeviceUsageTracker.Method.REMOVE_FORWARD) {
      runBlockingLegacy {
        mapToDdmlibException {
          val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)
          connectedDevice.session.hostServices.killForward(deviceSelector, local = SocketSpec.Tcp(localPort))
        }
      }
    }
  }

  override fun createReverse(remotePort: Int, localPort: Int) {
    logUsage(IDeviceUsageTracker.Method.CREATE_REVERSE) {
      runBlockingLegacy {
        mapToDdmlibException {
          val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)
          connectedDevice.session.deviceServices.reverseForward(
            deviceSelector,
            remote = SocketSpec.Tcp(remotePort),
            local = SocketSpec.Tcp(localPort),
            rebind = true,
          )
        }
      }
    }
  }

  override fun removeReverse(remotePort: Int) {
    logUsage(IDeviceUsageTracker.Method.REMOVE_REVERSE) {
      runBlockingLegacy {
        mapToDdmlibException {
          val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)
          connectedDevice.session.deviceServices.reverseKillForward(deviceSelector, remote = SocketSpec.Tcp(remotePort))
        }
      }
    }
  }

  override fun getClientName(pid: Int): String {
    return clients.firstOrNull { c -> c.clientData.pid == pid }?.clientData?.packageName ?: IDevice.UNKNOWN_PACKAGE
  }

  override fun pushFile(local: String, remote: String) {
    logUsage(IDeviceUsageTracker.Method.PUSH_FILE) {
      runBlockingLegacy(timeout = INFINITE_DURATION) {
        val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)

        val localFile = File(local).toPath()
        val localFileDate = Files.getLastModifiedTime(localFile)
        Log.d(LOG_TAG, "Uploading $localFile onto device '$serialNumber'")

        mapToSyncException {
          connectedDevice.session.deviceServices.syncSend(
            deviceSelector,
            localFile,
            remote,
            RemoteFileMode.fromPath(localFile) ?: RemoteFileMode.DEFAULT,
            localFileDate,
          )
        }
      }
    }
  }

  override fun pullFile(remote: String, local: String) {
    logUsage(IDeviceUsageTracker.Method.PULL_FILE) {
      runBlockingLegacy(timeout = INFINITE_DURATION) {
        val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)

        val localFile = File(local).toPath()
        Log.d(LOG_TAG, "Pull file from device '$serialNumber': `$remote` -> `$localFile`")

        mapToSyncException { connectedDevice.session.deviceServices.syncRecv(deviceSelector, remote, localFile) }
      }
    }
  }

  override fun statFile(remote: String): SyncService.FileStat? =
    logUsage(IDeviceUsageTracker.Method.STAT_FILE) {
      runBlockingLegacy {
        val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)

        Log.d(LOG_TAG, "Stat remote file '$remote' on device '$serialNumber'")

        mapToDdmlibException {
          connectedDevice.session.deviceServices.syncStat(deviceSelector, remote)?.let {
            SyncService.FileStat(it.remoteFileMode.modeBits, it.size, it.lastModified.to(TimeUnit.SECONDS))
          }
        }
      }
    }

  override fun installPackage(packageFilePath: String, reinstall: Boolean, vararg extraArgs: String?) {
    logUsage(IDeviceUsageTracker.Method.INSTALL_PACKAGE_1) {
      // Use default basic installReceiver
      installPackage(packageFilePath, reinstall, InstallReceiver(), *extraArgs)
    }
  }

  override fun installPackage(packageFilePath: String, reinstall: Boolean, receiver: InstallReceiver, vararg extraArgs: String?) {
    logUsage(IDeviceUsageTracker.Method.INSTALL_PACKAGE_2) {
      // Use default values for some timeouts.
      installPackage(packageFilePath, reinstall, receiver, 0L, INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES, *extraArgs)
    }
  }

  override fun installPackage(
    packageFilePath: String,
    reinstall: Boolean,
    receiver: InstallReceiver,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit?,
    vararg extraArgs: String?,
  ) {
    logUsage(IDeviceUsageTracker.Method.INSTALL_PACKAGE_3) {
      iDeviceSharedImpl.installPackage(packageFilePath, reinstall, receiver, maxTimeout, maxTimeToOutputResponse, maxTimeUnits, *extraArgs)
    }
  }

  override fun installPackages(apks: MutableList<File>, reinstall: Boolean, installOptions: MutableList<String>) {
    logUsage(IDeviceUsageTracker.Method.INSTALL_PACKAGES_1) {
      // Use the default single apk installer timeout.
      installPackages(apks, reinstall, installOptions, INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    }
  }

  override fun installPackages(
    apks: MutableList<File>,
    reinstall: Boolean,
    installOptions: MutableList<String>,
    timeout: Long,
    timeoutUnit: TimeUnit,
  ) {
    logUsage(IDeviceUsageTracker.Method.INSTALL_PACKAGES_2) {
      iDeviceSharedImpl.installPackages(apks, reinstall, installOptions, timeout, timeoutUnit)
    }
  }

  override fun getLastInstallMetrics(): InstallMetrics? {
    return iDeviceSharedImpl.lastInstallMetrics
  }

  override fun syncPackageToDevice(localFilePath: String): String =
    logUsage(IDeviceUsageTracker.Method.SYNC_PACKAGE_TO_DEVICE) {
      val packageFileName = File(localFilePath).getName()
      val remoteFilePath = "/data/local/tmp/$packageFileName"
      pushFile(localFilePath, remoteFilePath)
      remoteFilePath
    }

  override fun installRemotePackage(remoteFilePath: String?, reinstall: Boolean, vararg extraArgs: String?) {
    unsupportedMethod()
  }

  override fun installRemotePackage(remoteFilePath: String?, reinstall: Boolean, receiver: InstallReceiver?, vararg extraArgs: String?) {
    unsupportedMethod()
  }

  override fun installRemotePackage(
    remoteFilePath: String,
    reinstall: Boolean,
    receiver: InstallReceiver,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
    vararg extraArgs: String?,
  ) {
    logUsage(IDeviceUsageTracker.Method.INSTALL_REMOTE_PACKAGE) {
      iDeviceSharedImpl.installRemotePackage(
        remoteFilePath,
        reinstall,
        receiver,
        maxTimeout,
        maxTimeToOutputResponse,
        maxTimeUnits,
        *extraArgs,
      )
    }
  }

  override fun removeRemotePackage(remoteFilePath: String?) {
    logUsage(IDeviceUsageTracker.Method.REMOVE_REMOTE_PACKAGE) { iDeviceSharedImpl.removeRemotePackage(remoteFilePath) }
  }

  override fun uninstallPackage(packageName: String): String? =
    logUsage(IDeviceUsageTracker.Method.UNINSTALL_PACKAGE) { uninstallApp(packageName) }

  override fun uninstallApp(applicationID: String, vararg extraArgs: String): String? =
    logUsage(IDeviceUsageTracker.Method.UNINSTALL_APP) { iDeviceSharedImpl.uninstallApp(applicationID, *extraArgs) }

  override fun reboot(into: String?) {
    unsupportedMethod()
  }

  override fun root(): Boolean =
    logUsage(IDeviceUsageTracker.Method.ROOT) {
      runBlockingLegacy(timeout = INFINITE_DURATION) {
        val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)
        connectedDevice.session.deviceServices.rootAndWait(deviceSelector)
        isRoot()
      }
    }

  override fun forceStop(applicationName: String?) {
    logUsage(IDeviceUsageTracker.Method.FORCE_STOP) { iDeviceSharedImpl.forceStop(applicationName) }
  }

  override fun kill(applicationName: String?) {
    logUsage(IDeviceUsageTracker.Method.KILL) { iDeviceSharedImpl.kill(applicationName) }
  }

  override fun isRoot(): Boolean =
    logUsage(IDeviceUsageTracker.Method.IS_ROOT) {
      runBlockingLegacy(timeout = Duration.ofMillis(QUERY_IS_ROOT_TIMEOUT_MS)) { connectedDevice.isRoot() }
    }

  @Deprecated("")
  override fun getBatteryLevel(): Int {
    unsupportedMethod()
  }

  @Deprecated("")
  override fun getBatteryLevel(freshnessMs: Long): Int {
    unsupportedMethod()
  }

  override fun getBattery(): Future<Int> {
    unsupportedMethod()
  }

  override fun getBattery(freshnessTime: Long, timeUnit: TimeUnit): Future<Int> {
    unsupportedMethod()
  }

  override fun getAbis(): MutableList<String> = logUsage(IDeviceUsageTracker.Method.GET_ABIS) { iDeviceSharedImpl.abis }

  override fun getDensity(): Int = logUsage(IDeviceUsageTracker.Method.GET_DENSITY) { iDeviceSharedImpl.density }

  override fun getLanguage(): String? {
    unsupportedMethod()
  }

  override fun getRegion(): String? {
    unsupportedMethod()
  }

  override fun getVersion(): AndroidVersion {
    return iDeviceSharedImpl.version
  }

  override fun executeRemoteCommand(command: String, rcvr: IShellOutputReceiver, maxTimeToOutputResponse: Long, maxTimeUnits: TimeUnit) {
    logUsage(IDeviceUsageTracker.Method.EXECUTE_REMOTE_COMMAND_1) {
      // Note that `AdbHelper.AdbService.SHELL` is passed down to match the behavior of `DeviceImpl`
      executeRemoteCommand(AdbHelper.AdbService.SHELL, command, rcvr, maxTimeToOutputResponse, maxTimeUnits, null /* inputStream */)
    }
  }

  override fun executeRemoteCommand(
    command: String,
    rcvr: IShellOutputReceiver,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
  ) {
    logUsage(IDeviceUsageTracker.Method.EXECUTE_REMOTE_COMMAND_2) {
      // Note that `AdbHelper.AdbService.SHELL` is passed down to match the behavior of `DeviceImpl`
      executeRemoteCommand(
        AdbHelper.AdbService.SHELL,
        command,
        rcvr,
        maxTimeout,
        maxTimeToOutputResponse,
        maxTimeUnits,
        null, /* inputStream */
      )
    }
  }

  override fun executeRemoteCommand(
    adbService: AdbHelper.AdbService,
    command: String,
    rcvr: IShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
    `is`: InputStream?,
  ) {
    logUsage(IDeviceUsageTracker.Method.EXECUTE_REMOTE_COMMAND_3) {
      executeRemoteCommand(adbService, command, rcvr, 0L, maxTimeToOutputResponse, maxTimeUnits, `is`)
    }
  }

  override fun executeRemoteCommand(
    adbService: AdbHelper.AdbService,
    command: String,
    receiver: IShellOutputReceiver,
    maxTimeout: Long,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
    inputStream: InputStream?,
  ) {
    logUsage(IDeviceUsageTracker.Method.EXECUTE_REMOTE_COMMAND_4) {
      when (adbService) {
        AdbHelper.AdbService.SHELL,
        AdbHelper.AdbService.EXEC -> {
          // TODO(b/298475728): Revisit this when we are closer to having a working implementation
          // of `IDevice`
          // If `shutdownOutput` is true then we get a "java.lang.SecurityException: Files still
          // open" exception
          // when executing a "package install-commit" command after the "package install-write"
          // command
          // since the package manager doesn't handle shutdown correctly. This applies to legacy
          // EXEC protocol.
          val shutdownOutput =
            when (adbService) {
              AdbHelper.AdbService.EXEC -> false
              else -> true
            }
          runBlockingLegacy(timeout = INFINITE_DURATION) {
            mapToDdmlibException {
              executeShellCommand(
                adbService,
                connectedDevice,
                command,
                receiver,
                maxTimeout,
                maxTimeToOutputResponse,
                maxTimeUnits,
                inputStream,
                shutdownOutput,
              )
            }
          }
        }

        AdbHelper.AdbService.ABB_EXEC -> {
          runBlockingLegacy(timeout = INFINITE_DURATION) {
            mapToDdmlibException {
              executeAbbCommand(
                adbService,
                connectedDevice,
                command,
                receiver,
                maxTimeout,
                maxTimeToOutputResponse,
                maxTimeUnits,
                inputStream,
                shutdownOutput = false, // TODO(b/298475728): See the comment above
              )
            }
          }
        }
      }
    }
  }

  override fun executeBinderCommand(
    parameters: Array<out String>,
    receiver: IShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeUnits: TimeUnit,
    inputStream: InputStream?,
  ) {
    if (supportsFeature(IDevice.Feature.ABB_EXEC)) {
      executeRemoteCommand(
        AdbHelper.AdbService.ABB_EXEC,
        parameters.joinToString("\u0000"),
        receiver,
        0L,
        maxTimeToOutputResponse,
        maxTimeUnits,
        inputStream,
      )
    } else {
      executeShellCommand("cmd " + parameters.joinToString(" "), receiver, maxTimeToOutputResponse, maxTimeUnits, inputStream)
    }
  }

  override fun rawExec(executable: String, parameters: Array<out String>): SocketChannel {
    unsupportedMethod()
  }

  override fun rawExec2(executable: String, parameters: Array<out String>): SimpleConnectedSocket =
    logUsage(IDeviceUsageTracker.Method.RAW_EXEC2) {
      runBlockingLegacy(timeout = INFINITE_DURATION) {
        mapToDdmlibException {
          val command = StringBuilder(executable)
          for (parameter in parameters) {
            command.append(" ")
            command.append(parameter)
          }
          val channel = connectedDevice.session.deviceServices.rawExec(DeviceSelector.fromSerialNumber(serialNumber), command.toString())
          AdblibChannelWrapper(channel)
        }
      }
    }

  override fun <T : Any> computeUserDataIfAbsent(key: IUserDataMap.Key<T>, mappingFunction: Function<IUserDataMap.Key<T>, T>): T {
    return mUserDataMap.computeUserDataIfAbsent(key, mappingFunction)
  }

  override fun <T> getUserDataOrNull(key: IUserDataMap.Key<T>): T? {
    return mUserDataMap.getUserDataOrNull(key)
  }

  override fun <T> removeUserData(key: IUserDataMap.Key<T>): T? {
    return mUserDataMap.removeUserData(key)
  }

  /** Executes a block and logs its success of failure status using the Android Studio UsageTracker */
  private inline fun <R> logUsage(method: IDeviceUsageTracker.Method, crossinline block: () -> R): R {
    return try {
      block().also { iDeviceUsageTracker?.logUsage(method, false) }
    } catch (t: Throwable) {
      iDeviceUsageTracker?.logUsage(method, true)
      throw t
    }
  }

  private fun unsupportedMethod(): Nothing {
    iDeviceUsageTracker?.logUsage(IDeviceUsageTracker.Method.UNSUPPORTED_METHOD, false)
    throw UnsupportedOperationException("This method is not used in Android Studio")
  }

  /**
   * Similar to [runBlocking] but with a custom [timeout], and dealing with `InterruptedException` by converting them into `IOException` as
   * `IDevice` interface checked exceptions don't include throwing `InterruptedException`
   *
   * @throws IOException that wraps `TimeoutException` if [block] take more than [timeout] to execute
   * @throws IOException that wraps `InterruptedException`, if encountered
   */
  private fun <R> runBlockingLegacy(
    timeout: Duration = connectedDevice.session.property(RUN_BLOCKING_LEGACY_DEFAULT_TIMEOUT),
    block: suspend CoroutineScope.() -> R,
  ): R {
    try {
      return runBlocking {
        if (timeout == INFINITE_DURATION) {
          block()
        } else {
          connectedDevice.session.withErrorTimeout(timeout) { block() }
        }
      }
    } catch (e: TimeoutException) {
      throw IOException("Operation timed out", e)
    } catch (e: InterruptedException) {
      // We wrap `InterruptedException` in `IOException` to maintain the contract
      // defined by `IDevice` interface where no `InterruptedException` is ever thrown
      throw IOException("Operation interrupted", e)
    }
  }

  /**
   * Maps exceptions thrown from the `AdbDeviceServices` and `AdbHostServices` methods of `adblib` to the (approximately) equivalent
   * exceptions in `ddmlib`.
   *
   * Note that there is no perfect mapping from `adblib` to `ddmlib` exceptions. For example, instead of `ShellCommandUnresponsiveException`
   * `adblib` throws a more general `TimeoutException` exception.
   */
  private inline fun <R> mapToDdmlibException(block: () -> R): R {
    return try {
      block()
    } catch (e: AdbFailResponseException) {
      throw AdbCommandRejectedException.create(e.failMessage).also { it.initCause(e) }
    }
  }

  /**
   * Maps exceptions throws from the [AdbDeviceSyncServices] methods of `adblib` to the (approximately) equivalent [SyncException] of
   * `ddmlib`.
   *
   * TODO: Map [IOException] and [AdbFailResponseException] to the corresponding [SyncException]. This is not trivial as we don't have
   *   enough info in the exceptions thrown to correctly map to SyncException
   */
  private inline fun <R> mapToSyncException(block: () -> R): R {
    return try {
      block()
    } catch (e: CancellationException) {
      throw SyncException(SyncException.SyncError.CANCELED, e)
    } catch (e: AdbProtocolErrorException) {
      throw SyncException(SyncException.SyncError.TRANSFER_PROTOCOL_ERROR, e)
    } catch (e: AdbFailResponseException) {
      throw SyncException(SyncException.SyncError.TRANSFER_PROTOCOL_ERROR, e)
    }
  }

  companion object {

    private const val LOG_TAG = "AdblibIDeviceWrapper"
    private const val GET_PROP_TIMEOUT_MS = 1000L
    private const val INITIAL_GET_PROP_TIMEOUT_MS = 5000L
    private const val QUERY_IS_ROOT_TIMEOUT_MS = 1000L
  }
}
