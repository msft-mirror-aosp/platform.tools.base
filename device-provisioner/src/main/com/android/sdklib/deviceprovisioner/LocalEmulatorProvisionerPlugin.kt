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

import com.android.adblib.AdbLogger
import com.android.adblib.AdbSession
import com.android.adblib.ConnectedDevice
import com.android.adblib.DevicePropertyNames
import com.android.adblib.deviceProperties
import com.android.adblib.scope
import com.android.adblib.serialNumber
import com.android.adblib.tools.EmulatorConsole
import com.android.adblib.tools.localConsoleAddress
import com.android.adblib.tools.openEmulatorConsole
import com.android.adblib.utils.createChildScope
import com.android.annotations.concurrency.GuardedBy
import com.android.sdklib.AndroidVersion
import com.android.sdklib.SdkVersionInfo
import com.android.sdklib.SystemImageTags
import com.android.sdklib.deviceprovisioner.DeviceState.Connected
import com.android.sdklib.deviceprovisioner.DeviceState.Disconnected
import com.android.sdklib.deviceprovisioner.LocalEmulatorProvisionerPlugin.Companion.PLUGIN_ID
import com.android.sdklib.devices.Abi
import com.android.sdklib.internal.avd.AvdBuilder
import com.android.sdklib.internal.avd.AvdInfo
import com.android.sdklib.internal.avd.AvdInfo.AvdStatus
import com.android.sdklib.internal.avd.ConfigKey
import com.android.sdklib.internal.avd.HardwareProperties
import com.android.sdklib.internal.avd.UserSettingsKey
import com.android.sdklib.internal.avd.UserSettingsKey.PREFERRED_ABI
import com.android.utils.ILogger
import com.google.wireless.android.sdk.stats.DeviceInfo
import java.nio.file.Path
import java.time.Duration
import javax.swing.Icon
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Provides access to emulators running on the local machine from the standard AVD directory. Supports creating, editing, starting, and
 * stopping AVD instances.
 *
 * This plugin creates device handles for all AVDs present in the standard AVD directory, running or not. The AVD path is used to identify
 * devices and establish the link between connected devices and their handles. The directory is periodically rescanned to find new devices,
 * and immediately rescanned after an edit is made via a device action.
 */
class LocalEmulatorProvisionerPlugin
internal constructor(
  private val context: LocalEmulatorContext,
  private val scope: CoroutineScope,
  private val adbSession: AdbSession,
  private val refreshAvds: () -> List<AvdInfo>,
  rescanPeriod: Duration = Duration.ofSeconds(10),
  pluginExtensions: List<ExtensionProvider<LocalEmulatorProvisionerPlugin, *>> = emptyList(),
  private val handleExtensions: List<ExtensionProvider<LocalEmulatorDeviceHandle, *>> = emptyList(),
) : DeviceProvisionerPlugin {

  constructor(
    scope: CoroutineScope,
    adbSession: AdbSession,
    refreshAvds: () -> List<AvdInfo>,
    deviceIcons: DeviceIcons,
    rescanPeriod: Duration = Duration.ofSeconds(10),
    pluginExtensions: List<ExtensionProvider<LocalEmulatorProvisionerPlugin, *>> = emptyList(),
    handleExtensions: List<ExtensionProvider<LocalEmulatorDeviceHandle, *>> = emptyList(),
  ) : this(
    context =
      LocalEmulatorContext(
        logger = adbSession.host.loggerFactory.createLogger(LocalEmulatorProvisionerPlugin::class.java),
        deviceIcons = deviceIcons,
        clock = Clock.System,
      ),
    scope = scope,
    adbSession = adbSession,
    refreshAvds = refreshAvds,
    rescanPeriod = rescanPeriod,
    pluginExtensions = pluginExtensions,
    handleExtensions = handleExtensions,
  )

  private val logger by context::logger

  companion object {

    const val PLUGIN_ID = "LocalEmulator"
  }

  // We can identify local emulators reliably, so this can be relatively high priority.
  override val priority = 100

  private val mutex = Mutex()
  @GuardedBy("mutex") private val deviceHandles = HashMap<Path, LocalEmulatorDeviceHandle>()

  private val _devices = MutableStateFlow<List<LocalEmulatorDeviceHandle>>(emptyList())
  override val devices: StateFlow<List<DeviceHandle>> = _devices.asStateFlow()

  // TODO: Consider if it would be better to use a filesystem watcher here instead of polling.
  private val avdScanner = PeriodicAction(scope, rescanPeriod, ::rescanAvds)

  private val extensionRegistry = ExtensionRegistry(this, pluginExtensions)

  override fun <T : Extension> extension(extensionClass: Class<T>) = extensionRegistry.extension(extensionClass)

  init {
    avdScanner.runNow()

    scope.coroutineContext.job.invokeOnCompletion { avdScanner.cancel() }
  }

  /**
   * Scans the AVDs on disk and updates our devices.
   *
   * Do not call directly; this should only be called by PeriodicAction.
   */
  private suspend fun rescanAvds() {
    try {
      val avdsOnDisk = refreshAvds().associateBy { it.dataFolderPath }
      mutex.withLock {
        // Remove any current DeviceHandles that are no longer present on disk, unless they are
        // connected. (If a client holds on to the disconnected device handle, and it gets
        // recreated with the same path, the client will get a new device handle, which is fine.)
        val iterator = deviceHandles.entries.iterator()
        while (iterator.hasNext()) {
          val (path, handle) = iterator.next()
          if (!avdsOnDisk.containsKey(path) && handle.state is Disconnected) {
            iterator.remove()
            handle.scope.cancel()
          }
        }

        for ((path, avdInfo) in avdsOnDisk) {
          when (val handle = deviceHandles[path]) {
            null ->
              deviceHandles[path] =
                LocalEmulatorDeviceHandle(
                  context = context,
                  refreshDevices = ::refreshDevices,
                  scope = scope.createChildScope(isSupervisor = true),
                  extensions = handleExtensions,
                  initialAvdInfo = avdInfo,
                )
            else -> handle.updateAvdInfo(avdInfo)
          }
        }

        _devices.value = deviceHandles.values.toList()
      }
    } catch (t: Throwable) {
      if (t is CancellationException) {
        throw t
      }
      // The PeriodicAction's action must not throw, or it will not get rescheduled.
      logger.error(t, "Exception scanning AVDs")
    }
  }

  override suspend fun claim(device: ConnectedDevice): DeviceHandle? {
    val result = LOCAL_EMULATOR_REGEX.matchEntire(device.serialNumber) ?: return null
    val port = result.groupValues[1].toIntOrNull() ?: return null

    logger.debug { "Opening emulator console to $port" }
    val emulatorConsole = withTimeoutOrNull(5.seconds) { adbSession.openEmulatorConsole(localConsoleAddress(port)) } ?: return null

    // This will fail on emulator versions prior to 30.0.18.
    val pathResult = kotlin.runCatching { emulatorConsole.avdPath() }
    val path = pathResult.getOrNull()

    if (path == null) {
      // If we can't connect to the emulator console, this isn't operationally a local emulator
      logger.debug { "Unable to read path for device ${device.serialNumber} from emulator console" }
      emulatorConsole.close()
      return null
    }

    // Try to link this device to an existing handle.
    var handle = mutex.withLock { deviceHandles[path] }
    if (handle == null) {
      // We didn't read this path from disk yet. Rescan and try again.
      avdScanner.runNow().join()
      handle = mutex.withLock { deviceHandles[path] }
    }
    if (handle == null) {
      // Apparently this emulator is not on disk, or it is not in the directory that we scan for
      // AVDs. (Perhaps GMD or Crow failed to pick it up.)
      logger.debug { "Unexpected device at $path" }
      emulatorConsole.close()
      return null
    }
    handle.updateConnectedDevice(device, emulatorConsole, port)

    logger.debug { "Linked ${device.serialNumber} to AVD at $path" }

    // Wait for the handle to update its state to Connected before returning, so that the
    // provisioner doesn't think it needs to re-offer the device. This should happen almost
    // instantly.
    withTimeoutOrNull(2.seconds) { handle.stateFlow.first { it.connectedDevice == device } }
      ?: logger.warn("Device ${device.serialNumber} did not become connected!")

    return handle
  }

  fun refreshDevices() {
    avdScanner.runNow()
  }
}

/** The mutable state of a LocalEmulatorDeviceHandle, emitted by the internalStateFlow. */
private data class InternalState(
  val deviceState: DeviceState,
  val emulatorConsole: EmulatorConsole?,
  val avdInfo: AvdInfo,
  val pendingAvdInfo: AvdInfo?,
)

/**
 * A handle for a local AVD stored in the SDK's AVD directory. These are only created when reading an AVD off the disk; only devices that
 * have already been read from disk will be claimed.
 */
class LocalEmulatorDeviceHandle(
  private val context: LocalEmulatorContext,
  val refreshDevices: () -> Unit,
  override val scope: CoroutineScope,
  extensions: List<ExtensionProvider<LocalEmulatorDeviceHandle, *>> = emptyList(),
  initialAvdInfo: AvdInfo,
  initialDeviceProperties: LocalEmulatorProperties = context.disconnectedDeviceProperties(initialAvdInfo),
) : DeviceHandle {
  private val logger by context::logger
  private val clock by context::clock

  private val extensionRegistry = ExtensionRegistry(this, extensions)

  override fun <T : Extension> extension(extensionClass: Class<T>) = extensionRegistry.extension(extensionClass)

  private val messageChannel: Channel<LocalEmulatorMessage> = Channel()

  override val id = DeviceId(PLUGIN_ID, false, "path=${initialAvdInfo.dataFolderPath}")

  /**
   * The mutable state of the handle, maintained by an actor coroutine which reads from [messageChannel] serially, and emits the resulting
   * changes on this flow.
   */
  private val internalStateFlow: StateFlow<InternalState> =
    flow {
        var activeAvdInfo = initialAvdInfo
        var pendingAvdInfo: AvdInfo? = null
        var connectedDevice: ConnectedDevice? = null
        var emulatorConsole: EmulatorConsole? = null
        var emulatorConsolePort: Int? = null
        var connectedDeviceJobScope: CoroutineScope? = null
        var connectedDeviceState: com.android.adblib.DeviceState? = null
        var pendingTransition: TransitionRequest? = null
        var bootStatus = false
        var properties = initialDeviceProperties

        fun logName(): String {
          val port = emulatorConsolePort?.let { " ($it)" } ?: ""
          return "[${properties.title}$port]"
        }

        for (message in messageChannel) {
          logger.debug { "${logName()} Processing: $message" }
          when (message) {
            is AvdInfoUpdate -> {
              // First, apply any updates immediately that don't need a restart to take effect or
              // shouldn't trigger the AvdChangedError.
              if (!activeAvdInfo.isSameMetadata(message.avdInfo)) {
                activeAvdInfo = activeAvdInfo.copyMetadata(message.avdInfo)
                properties = properties.toBuilder().apply { setAvdInfo(activeAvdInfo) }.build()
              }
              activeAvdInfo = activeAvdInfo.updateInsignificantProperties(message.avdInfo)

              if (connectedDevice == null) {
                // Device is not running; immediately update if there's an actual change.
                if (activeAvdInfo != message.avdInfo) {
                  activeAvdInfo = message.avdInfo
                  properties = context.disconnectedDeviceProperties(activeAvdInfo)
                }
              } else if (pendingAvdInfo != null || activeAvdInfo != message.avdInfo) {
                pendingAvdInfo = message.avdInfo
              }
            }
            is ConnectedDeviceUpdate -> {
              connectedDevice = message.connectedDevice
              emulatorConsole?.close()
              emulatorConsole = message.emulatorConsole
              emulatorConsolePort = message.emulatorConsolePort
              if (pendingTransition?.transitionType == TransitionType.ACTIVATION) {
                pendingTransition.completion.complete(Unit)
                pendingTransition = null
              }
              // Spawn jobs to track boot status and device status
              connectedDeviceJobScope?.cancel()
              connectedDeviceJobScope = scope.createChildScope(isSupervisor = true)
              connectedDeviceJobScope.launch {
                message.connectedDevice
                  .bootStatusFlow()
                  .catch { e -> logger.warn(e, "${logName()} Failed to read boot status") }
                  .collect { messageChannel.send(BootStatusUpdate(it)) }
              }
              connectedDeviceJobScope.launch {
                message.connectedDevice.deviceInfoFlow
                  .onEach { messageChannel.send(ConnectedDeviceStateUpdate(it.deviceState)) }
                  .takeWhile { it.deviceState != com.android.adblib.DeviceState.DISCONNECTED }
                  .collect()
              }
            }
            is ConnectedDeviceStateUpdate -> {
              connectedDeviceState = message.deviceState
              if (connectedDeviceState == com.android.adblib.DeviceState.DISCONNECTED) {
                logger.debug { "${logName()} Device closed; disconnecting from console" }
                emulatorConsole?.close()
                emulatorConsole = null
                connectedDevice = null

                if (pendingTransition?.transitionType == TransitionType.DEACTIVATION) {
                  pendingTransition.completion.complete(Unit)
                  pendingTransition = null
                }
                bootStatus = false
                if (pendingAvdInfo != null) {
                  activeAvdInfo = pendingAvdInfo
                  pendingAvdInfo = null
                }
                properties = context.disconnectedDeviceProperties(activeAvdInfo)
              }
            }
            is BootStatusUpdate -> {
              // On a transition from not booted to booted, read the properties from the device.
              // bootStatus is always reset when connectedDevice becomes null, so connectedDevice
              // is guaranteed to become non-null before bootStatus becomes true
              val connectedDevice = connectedDevice
              if (connectedDevice != null && !bootStatus && message.bootStatus.isBooted) {
                // Spawn a job to do I/O with the device. Run on device scope so that if the
                // device disconnects, the job is cancelled.
                connectedDevice.scope.launch {
                  messageChannel.send(
                    DevicePropertiesUpdate(
                      connectedDevice,
                      runCatching { connectedDevice.deviceProperties().all().asMap() },
                      Resolution.readFromDevice(connectedDevice),
                    )
                  )
                }
              }
            }
            is DevicePropertiesUpdate -> {
              if (message.connectedDevice == connectedDevice) {
                bootStatus = true
                val newProperties = message.properties.getOrNull()
                if (newProperties == null) {
                  val e = message.properties.exceptionOrNull()
                  logger.warn(e, "Unable to read device properties")
                } else {
                  properties =
                    LocalEmulatorProperties.build(activeAvdInfo) {
                      readCommonProperties(newProperties)
                      populateDeviceInfoProto(PLUGIN_ID, connectedDevice.serialNumber, newProperties, randomConnectionId())
                      // Device type is not always reliably read from properties
                      deviceType = activeAvdInfo.toDeviceType()
                      density = newProperties[DevicePropertyNames.QEMU_SF_LCD_DENSITY]?.toIntOrNull()
                      resolution = message.resolution
                      disambiguator = emulatorConsolePort.toString()
                      wearPairingId = activeAvdInfo.dataFolderPath.toString().takeIf { isPairable() }
                      icon = context.deviceIcons.iconForDeviceType(deviceType)
                    }
                }
              }
            }
            is TransitionRequest -> {
              if (pendingTransition == null) {
                val transitionNecessary =
                  when (message.transitionType) {
                    TransitionType.ACTIVATION -> connectedDevice == null
                    TransitionType.DEACTIVATION -> connectedDevice != null
                  }
                if (transitionNecessary) {
                  pendingTransition = message
                  scope.launch { messageChannel.send(TransitionResult(runCatching { message.action() })) }
                  scheduleTimeoutCheck(message.timeout)
                } else {
                  // We are already in the desired state; this is a no-op
                  message.completion.complete(Unit)
                }
              } else {
                message.completion.completeExceptionally(
                  DeviceActionDisabledException(
                    "Device is already " +
                      when (pendingTransition.transitionType) {
                        TransitionType.ACTIVATION -> "activating"
                        TransitionType.DEACTIVATION -> "deactivating"
                      }
                  )
                )
              }
            }
            is TransitionResult -> {
              // Closure-based approaches (e.g. onFailure) inhibit smart-cast on pendingTransition
              val e = message.result.exceptionOrNull()
              if (e != null) {
                // Note that we only complete on exception; if it succeeded we still wait for the
                // state to change before we signal completion
                pendingTransition?.completion?.completeExceptionally(e)
                pendingTransition = null
              }
            }
            is CheckTimeout -> {
              if (pendingTransition != null && pendingTransition.timeout <= clock.now()) {
                val action =
                  when (pendingTransition.transitionType) {
                    TransitionType.ACTIVATION -> "connect"
                    TransitionType.DEACTIVATION -> "disconnect"
                  }
                pendingTransition.completion.completeExceptionally(
                  DeviceActionException("Emulator failed to $action within $CONNECTION_TIMEOUT_MINUTES minutes")
                )
                pendingTransition = null
              }
            }
          }

          emit(
            InternalState(
              if (connectedDevice == null) {
                Disconnected(
                  properties,
                  isTransitioning = pendingTransition != null,
                  status = if (pendingTransition != null) "Starting up" else "Offline",
                  error = activeAvdInfo.deviceError,
                )
              } else {
                Connected(
                  properties,
                  isTransitioning = !bootStatus || pendingTransition != null,
                  isReady = bootStatus && connectedDeviceState == com.android.adblib.DeviceState.ONLINE,
                  status =
                    when {
                      pendingTransition != null -> "Shutting down"
                      !bootStatus -> "Booting"
                      else -> "Connected"
                    },
                  connectedDevice,
                  error = activeAvdInfo.deviceError ?: pendingAvdInfo?.let { AvdChangedError },
                )
              },
              emulatorConsole,
              activeAvdInfo,
              pendingAvdInfo,
            )
          )
        }
      }
      .onCompletion { emulatorConsole?.close() }
      .stateIn(
        scope,
        SharingStarted.Eagerly,
        InternalState(Disconnected(initialDeviceProperties, error = initialAvdInfo.deviceError), null, initialAvdInfo, null),
      )

  override val stateFlow =
    internalStateFlow.map { it.deviceState }.stateIn(scope, SharingStarted.Eagerly, Disconnected(initialDeviceProperties))

  /** The currently active AvdInfo for the device. */
  val avdInfo: AvdInfo
    get() = internalStateFlow.value.avdInfo

  /**
   * The latest AvdInfo read from the disk for the device. If the on-disk AvdInfo is updated while the device is already running, the device
   * will continue to reflect the AvdInfo from its boot time.
   */
  val onDiskAvdInfo: AvdInfo
    get() = internalStateFlow.value.let { it.pendingAvdInfo ?: it.avdInfo }

  /** The emulator console is present when the device is connected. */
  val emulatorConsole: EmulatorConsole?
    get() = internalStateFlow.value.emulatorConsole

  private fun scheduleTimeoutCheck(timeout: Instant) {
    scope.launch {
      delay(timeout - clock.now())
      messageChannel.send(CheckTimeout)
    }
  }

  /**
   * Update the avdInfo if we're not currently running. If we are running, the old values are probably still in effect, but we will update
   * on the next scan after shutdown.
   */
  suspend fun updateAvdInfo(newAvdInfo: AvdInfo) {
    messageChannel.send(AvdInfoUpdate(newAvdInfo))
  }

  /** Notifies the handle that it has been connected. */
  suspend fun updateConnectedDevice(connectedDevice: ConnectedDevice, emulatorConsole: EmulatorConsole, emulatorConsolePort: Int) {
    messageChannel.send(ConnectedDeviceUpdate(connectedDevice, emulatorConsole, emulatorConsolePort))
  }

  /**
   * Initiates activation of the device. The supplied action is expected to launch the AVD and cause it to connect to ADB. The handle will
   * expect to be notified of the device's presence by the provisioner; this will complete the activation.
   *
   * @throws DeviceActionException if this does not occur within [CONNECTION_TIMEOUT]
   */
  suspend fun activate(action: suspend () -> Unit) {
    val request = TransitionRequest(TransitionType.ACTIVATION, clock.now() + CONNECTION_TIMEOUT, action)
    messageChannel.send(request)
    // Use the Deferred to receive exceptions from the actor.
    request.completion.await()
    // We still need to wait very briefly for the state to update after the Deferred is completed.
    // If completion was unexceptional, we will immediately transition to Connected.
    stateFlow.first { it is Connected }
  }

  /**
   * Initiates deactivation of the device. The supplied action is expected to terminate the AVD, causing its [ConnectedDevice] to enter the
   * DISCONNECTED state.
   *
   * @throws DeviceActionException if this does not occur within [CONNECTION_TIMEOUT]
   */
  suspend fun deactivate(action: suspend () -> Unit) {
    val request = TransitionRequest(TransitionType.DEACTIVATION, clock.now() + DISCONNECTION_TIMEOUT, action)
    messageChannel.send(request)
    request.completion.await()
    stateFlow.first { it is Disconnected }
  }

  /** Sets the phone that is paired to this device; if null, clears the paired phone. */
  fun updatePairedPhone(companion: LocalEmulatorDeviceHandle?) {
    updatePairedDevice(UserSettingsKey.PAIRED_PHONE_AVD_ID, companion)
  }

  /** Sets the glasses that are paired to this device; if null, clears the paired glasses. */
  fun updatePairedGlasses(companion: LocalEmulatorDeviceHandle?) {
    updatePairedDevice(UserSettingsKey.PAIRED_GLASSES_AVD_ID, companion)
  }

  private fun updatePairedDevice(key: String, companion: LocalEmulatorDeviceHandle?) {
    AvdBuilder.updateUserSettings(
      (state.properties as LocalEmulatorProperties).avdPath,
      mapOf(key to companion?.id?.toString()),
      logger.asILogger(),
    )
    refreshDevices()
  }
}

class LocalEmulatorContext(val logger: AdbLogger, val deviceIcons: DeviceIcons, val clock: Clock) {
  fun disconnectedDeviceProperties(avdInfo: AvdInfo): LocalEmulatorProperties =
    LocalEmulatorProperties.build(avdInfo) {
      populateDeviceInfoProto(PLUGIN_ID, null, emptyMap(), "")
      icon = deviceIcons.iconForDeviceType(deviceType)
    }
}

data class LocalEmulatorProperties(
  override val manufacturer: String?,
  override val model: String?,
  override val androidVersion: AndroidVersion?,
  override val abiList: List<Abi>,
  override val preferredAbi: String?,
  override val androidRelease: String?,
  override val disambiguator: String?,
  override val deviceType: DeviceType?,
  override val isVirtual: Boolean?,
  override val isRemote: Boolean?,
  override val isDebuggable: Boolean?,
  override val isResizable: Boolean?,
  override val wearPairingId: String?,
  override val pairedPhoneId: DeviceId?,
  override val pairedGlassesId: DeviceId?,
  override val resolution: Resolution?,
  override val density: Int?,
  override val icon: Icon,
  override val connectionType: ConnectionType?,
  override val deviceInfoProto: DeviceInfo,
  val avdName: String,
  val avdPath: Path,
  val displayName: String,
  val hasPlayStore: Boolean,
  val avdConfigProperties: ImmutableMap<String, String>,
  val isAiGlassesCompatible: Boolean,
) : DeviceProperties {

  override fun toBuilder(): Builder = Builder().apply { copyFrom(this@LocalEmulatorProperties) }

  override val title = displayName

  companion object {
    inline fun build(avdInfo: AvdInfo, block: Builder.() -> Unit): LocalEmulatorProperties =
      Builder()
        .apply {
          setAvdInfo(avdInfo)
          block()
        }
        .build()
  }

  class Builder : DeviceProperties.Builder() {
    var avdName: String? = null
    var avdPath: Path? = null
    var displayName: String? = null
    var hasPlayStore: Boolean = false
    val avdConfigProperties: MutableMap<String, String> = mutableMapOf()
    var isAiGlassesCompatible: Boolean = false

    fun copyFrom(properties: LocalEmulatorProperties) {
      super.copyFrom(properties)
      avdName = properties.avdName
      avdPath = properties.avdPath
      displayName = properties.displayName
      hasPlayStore = properties.hasPlayStore
      avdConfigProperties.clear()
      avdConfigProperties.putAll(properties.avdConfigProperties)
      isAiGlassesCompatible = properties.isAiGlassesCompatible
    }

    fun isPairable(): Boolean {
      val apiLevel = androidVersion?.apiLevel ?: return false
      return when (deviceType) {
        DeviceType.HANDHELD -> apiLevel >= 30 && hasPlayStore
        DeviceType.WEAR -> apiLevel >= 28
        else -> false
      }
    }

    fun setAvdInfo(avdInfo: AvdInfo) {
      isVirtual = true
      manufacturer = avdInfo.deviceManufacturer
      model = avdInfo.deviceName
      androidVersion = avdInfo.androidVersion
      androidRelease = SdkVersionInfo.getVersionString(avdInfo.androidVersion.apiLevel)
      abiList =
        avdInfo.systemImage?.let { (it.abiTypes + it.translatedAbiTypes).mapNotNull { Abi.getEnum(it) } }
          ?: listOfNotNull(Abi.getEnum(avdInfo.abiType))
      avdName = avdInfo.name
      avdPath = avdInfo.dataFolderPath
      displayName = avdInfo.displayName
      deviceType = avdInfo.toDeviceType()
      hasPlayStore = avdInfo.hasPlayStore()
      wearPairingId = avdInfo.id.takeIf { isPairable() }
      pairedPhoneId = avdInfo.userSettings[UserSettingsKey.PAIRED_PHONE_AVD_ID]?.let { DeviceId.fromString(it) }
      pairedGlassesId = avdInfo.userSettings[UserSettingsKey.PAIRED_GLASSES_AVD_ID]?.let { DeviceId.fromString(it) }
      density = avdInfo.density
      resolution = avdInfo.resolution
      isDebuggable = !avdInfo.hasPlayStore()
      isResizable = avdInfo.androidVersion.isAtLeast(AndroidVersion.MIN_RESIZABLE_DEVICE_API) && avdInfo.deviceName == "resizable"
      preferredAbi = avdInfo.userSettings[PREFERRED_ABI]
      avdConfigProperties.putAll(avdInfo.properties)
      isAiGlassesCompatible = avdInfo.isAiGlassesCompatibleDevice
    }

    override fun build() =
      LocalEmulatorProperties(
        manufacturer = manufacturer,
        model = model,
        androidVersion = androidVersion,
        abiList = abiList,
        preferredAbi = preferredAbi,
        androidRelease = androidRelease,
        disambiguator = disambiguator,
        deviceType = deviceType,
        isVirtual = isVirtual,
        isRemote = isRemote,
        isDebuggable = isDebuggable,
        isResizable = isResizable,
        wearPairingId = wearPairingId,
        resolution = resolution,
        density = density,
        icon = checkNotNull(icon),
        connectionType = connectionType,
        deviceInfoProto = deviceInfoProto.build(),
        avdName = checkNotNull(avdName),
        avdPath = checkNotNull(avdPath),
        displayName = checkNotNull(displayName),
        hasPlayStore = hasPlayStore,
        pairedPhoneId = pairedPhoneId,
        pairedGlassesId = pairedGlassesId,
        avdConfigProperties = avdConfigProperties.toImmutableMap(),
        isAiGlassesCompatible = isAiGlassesCompatible,
      )
  }
}

private sealed interface LocalEmulatorMessage

private data class AvdInfoUpdate(val avdInfo: AvdInfo) : LocalEmulatorMessage

private data class ConnectedDeviceUpdate(
  val connectedDevice: ConnectedDevice,
  val emulatorConsole: EmulatorConsole,
  val emulatorConsolePort: Int,
) : LocalEmulatorMessage

private data class ConnectedDeviceStateUpdate(val deviceState: com.android.adblib.DeviceState) : LocalEmulatorMessage

private data class BootStatusUpdate(val bootStatus: BootStatus) : LocalEmulatorMessage

private data class DevicePropertiesUpdate(
  val connectedDevice: ConnectedDevice,
  val properties: Result<Map<String, String>>,
  val resolution: Resolution?,
) : LocalEmulatorMessage

private data class TransitionRequest(
  val transitionType: TransitionType,
  val timeout: Instant,
  val action: suspend () -> Unit,
  val completion: CompletableDeferred<Unit> = CompletableDeferred(),
) : LocalEmulatorMessage

enum class TransitionType {
  ACTIVATION,
  DEACTIVATION,
}

private data class TransitionResult(val result: Result<Any>) : LocalEmulatorMessage

private object CheckTimeout : LocalEmulatorMessage

private val AvdInfo.density
  get() = properties[HardwareProperties.HW_LCD_DENSITY]?.toIntOrNull()

private val AvdInfo.resolution
  get() =
    properties[HardwareProperties.HW_LCD_WIDTH]?.toIntOrNull()?.let { width ->
      properties[HardwareProperties.HW_LCD_HEIGHT]?.toIntOrNull()?.let { height -> Resolution(width, height) }
    }

private val AvdInfo.deviceError
  get() =
    when (status) {
      AvdStatus.ERROR_DEVICE_MISSING -> null
      else -> errorMessage?.let { AvdDeviceError(status, it) }
    }

data class AvdDeviceError(val status: AvdStatus, override val message: String) : DeviceError {
  override val severity
    get() = DeviceError.Severity.ERROR
}

internal object AvdChangedError : DeviceError {
  override val severity = DeviceError.Severity.INFO
  override val message = "Changes will apply on restart"
}

/** We ignore these keys when deciding if the AVD properties have changed in a way that merits showing the AvdChangedError. */
private val insignificantKeys =
  setOf(
    ConfigKey.CHOSEN_SNAPSHOT_FILE,
    ConfigKey.FORCE_CHOSEN_SNAPSHOT_BOOT_MODE,
    ConfigKey.FORCE_COLD_BOOT_MODE,
    ConfigKey.FORCE_FAST_BOOT_MODE,
  )

internal fun AvdInfo.updateInsignificantProperties(newInfo: AvdInfo): AvdInfo {
  if (insignificantKeys.any { properties[it] != newInfo.properties[it] }) {
    return copy(properties = (properties - insignificantKeys) + newInfo.properties.filterKeys(insignificantKeys::contains))
  }
  return this
}

private fun AvdInfo.toDeviceType(): DeviceType {
  val tags = tags
  return when {
    SystemImageTags.isTvImage(tags) -> DeviceType.TV
    SystemImageTags.isAutomotiveImage(tags) -> DeviceType.AUTOMOTIVE
    SystemImageTags.isWearImage(tags) -> DeviceType.WEAR
    SystemImageTags.isDesktopImage(tags) -> DeviceType.DESKTOP
    SystemImageTags.isXrHeadsetImage(tags) -> DeviceType.XR_HEADSET
    SystemImageTags.isAiGlassesImage(tags) -> DeviceType.AI_GLASSES
    else -> DeviceType.HANDHELD
  }
}

private val LOCAL_EMULATOR_REGEX = "emulator-(\\d+)".toRegex()

private const val CONNECTION_TIMEOUT_MINUTES: Long = 5
private val CONNECTION_TIMEOUT = CONNECTION_TIMEOUT_MINUTES.minutes

private const val DISCONNECTION_TIMEOUT_MINUTES: Long = 1
private val DISCONNECTION_TIMEOUT = DISCONNECTION_TIMEOUT_MINUTES.minutes

fun AdbLogger.asILogger(): ILogger =
  object : ILogger {
    override fun error(t: Throwable?, msgFormat: String?, vararg args: Any?) {
      log(AdbLogger.Level.ERROR, t, msgFormat?.format(*args) ?: t?.message ?: "")
    }

    override fun warning(msgFormat: String, vararg args: Any?) {
      log(AdbLogger.Level.WARN, msgFormat.format(*args))
    }

    override fun info(msgFormat: String, vararg args: Any?) {
      logIf(AdbLogger.Level.INFO) { msgFormat.format(*args) }
    }

    override fun verbose(msgFormat: String, vararg args: Any?) {
      logIf(AdbLogger.Level.VERBOSE) { msgFormat.format(*args) }
    }
  }
