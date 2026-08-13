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
package com.android.adblib

import com.android.adblib.AdbLibProperties.AM_SERVICE_RETRY_DELAY
import com.android.adblib.AdbLibProperties.AM_SERVICE_TIMEOUT
import com.android.adblib.AdbLibProperties.PM_SERVICE_RETRY_DELAY
import com.android.adblib.AdbLibProperties.PM_SERVICE_TIMEOUT
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Abstraction over a device currently connected to ADB. An instance of [ConnectedDevice] is valid as long as the device is connected to the
 * underlying ADB server, and becomes invalid as soon as the device is disconnected or ADB server is restarted.
 *
 * @see [AdbSession.connectedDevicesTracker]
 */
@IsThreadSafe
interface ConnectedDevice {

  /**
   * The [session][AdbSession] this device belongs to. When the session is [closed][AdbSession.close], this [ConnectedDevice] instance
   * becomes invalid.
   */
  val session: AdbSession

  /** Returns a [CoroutineScopeCache] associated to this [ConnectedDevice]. The cache is cleared when the device is disconnected. */
  val cache: CoroutineScopeCache

  /**
   * The [StateFlow] of [DeviceInfo] corresponding to change of state of the device. Once the device is disconnected, the
   * [DeviceInfo.deviceState] is always set to [DeviceState.DISCONNECTED].
   */
  val deviceInfoFlow: StateFlow<DeviceInfo>
}

/**
 * A [CoroutineScope] tied to this [ConnectedDevice] instance. The scope is cancelled when the device is disconnected, when the
 * [ConnectedDevice.session] is closed or when the ADB server is restarted.
 */
val ConnectedDevice.scope: CoroutineScope
  get() = cache.scope

/**
 * The "serial number" of this [device][ConnectedDevice], used to identify a device with the ADB server as long as the device is connected.
 */
val ConnectedDevice.serialNumber: String
  get() = deviceInfoFlow.value.serialNumber

/**
 * The [DeviceSelector] of this [device][ConnectedDevice], used to identify a device with the ADB server as long as the device is connected.
 */
val ConnectedDevice.selector: DeviceSelector
  get() = DeviceSelector.fromSerialNumber(serialNumber)

/** Whether the device is [DeviceState.ONLINE], i.e. ready to be used. */
val ConnectedDevice.isOnline: Boolean
  get() = deviceInfoFlow.value.deviceState == DeviceState.ONLINE

/**
 * Waits until the device is [DeviceState.ONLINE].
 *
 * @throws IOException if the device disconnects while waiting for the [DeviceState.ONLINE] state.
 */
suspend fun ConnectedDevice.waitUntilOnline() {
  return waitUntilState(DeviceState.ONLINE)
}

/**
 * Waits until the device state is [targetState].
 *
 * @throws IOException if the device disconnects while waiting for a state other than [DeviceState.DISCONNECTED].
 */
suspend fun ConnectedDevice.waitUntilState(targetState: DeviceState) {
  val reachedState = deviceInfoFlow.map { it.deviceState }.first { state -> state == targetState || state == DeviceState.DISCONNECTED }

  // If we stopped waiting because the device disconnected (and we were not waiting
  // for the disconnect), throw an exception.
  if (reachedState == DeviceState.DISCONNECTED && targetState != DeviceState.DISCONNECTED) {
    throw IOException("Device $serialNumber disconnected while waiting for '$targetState'")
  }
}

/** The current (or last known) [DeviceInfo] for this [ConnectedDevice]. */
val ConnectedDevice.deviceInfo: DeviceInfo
  get() = deviceInfoFlow.value

/** Shortcut to the [DeviceProperties] of this device. */
fun ConnectedDevice.deviceProperties(): DeviceProperties =
  session.deviceServices.deviceProperties(DeviceSelector.fromSerialNumber(serialNumber))

/**
 * Returns a [WithDeviceScopeContext] used to invoke the [action] coroutine using the [CoroutineScope] of this [ConnectedDevice].
 * * Optionally call [withRetry][WithDeviceScopeContext.withRetry] to set a predicate to invoke as retry policy
 * * Optionally call [withFinally][WithDeviceScopeContext.withFinally] to set an action to invoke before
 *   [execute][WithDeviceScopeContext.execute] terminates (exceptionally or not), i.e. when [action] throws an exception not handled by
 *   [withRetry][WithDeviceScopeContext.withRetry], or when the [withRetry][WithDeviceScopeContext.withRetry] predicate throws an exception,
 *   or when the [ConnectedDevice.scope] is cancelled.
 * * Call [execute][WithDeviceScopeContext.execute] to start the execution of [action], using the previously applied
 *   [withRetry][WithDeviceScopeContext.withRetry] and [withFinally][WithDeviceScopeContext.withFinally] lamdbas.
 *
 * Example:
 *
 *  ```
 *      device.withScopeContext {
 *          device.trackJdwp().collect {
 *             (...)
 *          }
 *      }.withRetry { throwable ->
 *          delay(2_000)
 *          true // try again
 *      }.withFinally {
 *          (...)
 *      }.execute()
 *  ```
 */
fun ConnectedDevice.withScopeContext(action: suspend CoroutineScope.() -> Unit): WithDeviceScopeContext {
  return WithDeviceScopeContext(this, action)
}

class WithDeviceScopeContext(private val device: ConnectedDevice, private val action: suspend CoroutineScope.() -> Unit) {

  private val logger = adbLogger(device.session).withDevicePrefix(device)

  private var retryPredicate: suspend (Throwable) -> Boolean = { false }
  private var finallyAction: () -> Unit = {}

  /**
   * Sets the [predicate] to invoke when the block passed to [execute] throws a [Throwable] and the device is still connected. This is a
   * suspending function, so [delay] can be safely used.
   */
  fun withRetry(predicate: suspend (Throwable) -> Boolean): WithDeviceScopeContext {
    this.retryPredicate = predicate
    return this
  }

  /**
   * Sets the [action] to invoke before [execute] terminates (exceptionally or not), i.e.
   * * either when [action] throws an exception not handled by [withRetry],
   * * or when the [withRetry] predicate throws an exception,
   * * or when the [ConnectedDevice.scope] is cancelled
   */
  fun withFinally(action: () -> Unit): WithDeviceScopeContext {
    this.finallyAction = action
    return this
  }

  /**
   * Executes the coroutine [action] in the context of the [scope][ConnectedDevice.scope] of the [ConnectedDevice], retrying on failure for
   * as long as the device scope is [active][CoroutineScope.isActive].
   */
  suspend fun execute() {
    try {
      withContext(device.scope.coroutineContext) {
        var shouldRetry = true
        while (shouldRetry) {
          ensureActive()
          try {
            action()
            // If action succeeds, we don't need to retry anymore
            shouldRetry = false
          } catch (t: Throwable) {
            logger.verbose(t) { "Exception occurred during execution of service: $t" }
            runCatching { retryPredicate(t) }
              .onSuccess {
                shouldRetry = it
                if (!shouldRetry) {
                  throw t
                }
              }
              .onFailure { throwable ->
                logger.debug(throwable) { "Retry predicate failed with an exception, " + "rethrowing original exception '$t'" }
                t.addSuppressed(throwable)
                throw t
              }
          }
        }
      }
    } finally {
      logger.debug { "End of retry loop, calling finally (device scope=${device.scope})" }
      finallyAction()
    }
  }
}

private val ShellManagerKey = CoroutineScopeCache.Key<ShellManager>("ShellManager")

/** The [ShellManager] instance for executing shell commands on this [ConnectedDevice] */
val ConnectedDevice.shell: ShellManager
  get() {
    return cache.getOrPut(ShellManagerKey) { ShellManager(this) }
  }

private val ActivityManagerKey = CoroutineScopeCache.Key<ActivityManager>("ActivityManager")

/** The [ActivityManager] instance for managing activities on this [ConnectedDevice] */
val ConnectedDevice.activityManager: ActivityManager
  get() {
    return cache.getOrPut(ActivityManagerKey) { ActivityManager(this) }
  }

private val PackageManagerKey = CoroutineScopeCache.Key<PackageManager>("PackageManager")

/** The [PackageManager] instance for managing packages on this [ConnectedDevice] */
val ConnectedDevice.packageManager: PackageManager
  get() {
    return cache.getOrPut(PackageManagerKey) { PackageManager(this) }
  }

private val FileSystemManagerKey = CoroutineScopeCache.Key<FileSystemManager>("FileSystemManager")

/** The [FileSystemManager] instance for managing files on this [ConnectedDevice] */
val ConnectedDevice.fileSystem: FileSystemManager
  get() {
    return cache.getOrPut(FileSystemManagerKey) { FileSystemManager(this) }
  }

private val ReverseForwardManagerKey = CoroutineScopeCache.Key<ReverseForwardManager>("ReverseForwardManager")

/** The [ReverseForwardManager] instance for managing "reverse forward" connections associated to this device. */
val ConnectedDevice.reverseForward: ReverseForwardManager
  get() {
    return cache.getOrPut(ReverseForwardManagerKey) { ReverseForwardManager(this) }
  }

/** Manages "reverse forward" connections of a given [ConnectedDevice] */
class ReverseForwardManager(val device: ConnectedDevice) {
  /**
   * Returns the list of active [reverse forwards][ReverseSocketInfo] of this [device].
   *
   * @see AdbDeviceServices.reverseListForward
   */
  suspend fun list(): ListWithErrors<ReverseSocketInfo> {
    return device.session.deviceServices.reverseListForward(device.selector)
  }

  /**
   * Creates a "reverse forward" socket connection from [remote] to [local] on this [device]
   *
   * @see AdbDeviceServices.reverseForward
   */
  suspend fun add(remote: SocketSpec, local: SocketSpec, rebind: Boolean = false): String? {
    return device.session.deviceServices.reverseForward(device.selector, remote, local, rebind)
  }

  /**
   * Closes the "reverse forward" socket connection identified by [remote] on this [device]
   *
   * @see AdbDeviceServices.reverseKillForward
   */
  suspend fun kill(remote: SocketSpec) {
    device.session.deviceServices.reverseKillForward(device.selector, remote)
  }

  /**
   * Closes all "reverse forward" socket connection on this [device]
   *
   * @see AdbDeviceServices.reverseKillForwardAll
   */
  suspend fun killAll() {
    device.session.deviceServices.reverseKillForwardAll(device.selector)
  }
}

/**
 * Access to various `am` services for a given [ConnectedDevice].
 *
 * See [am command](https://developer.android.com/tools/adb#am)
 */
class ActivityManager(val device: ConnectedDevice) {

  private val session: AdbSession
    get() = device.session

  private val logger = adbLogger(session).withDevicePrefix(device)

  /**
   * Uses `adb shell am crash` to crash an app.
   *
   * Note that `am crash` command is available on API level >= 26.
   *
   * @throws AdbActivityManagerException if the `am` command failed or is not supported
   * @throws IOException if there was an issue communicating with the device
   * @see AdbActivityManagerServices.crash
   * @see isCrashSupported
   */
  suspend fun crash(packageName: String) {
    mapTimeoutToAdbException("crash $packageName") {
      runAmCommandWhenServiceIsReady(
        amCommandName = "crash",
        timeout = device.session.property(AM_SERVICE_TIMEOUT),
        retryDelay = device.session.property(AM_SERVICE_RETRY_DELAY),
      ) {
        device.session.activityManagerServices.crash(device.selector, packageName)
      }
    }
  }

  /**
   * Uses `adb shell am gc` to trigger garbage collection on a process.
   *
   * Note: You can use the [capabilities] method to check if the `gc` command is supported by the `am` implementation on the device. This
   * method will throw an [AdbActivityManagerException] if the `gc` command is not supported.
   *
   * @throws AdbActivityManagerException if the `am` command failed or is not supported
   * @throws IOException if there was an issue communicating with the device
   * @see AdbActivityManagerServices.gc
   */
  suspend fun gc(pid: Int) {
    mapTimeoutToAdbException("gc $pid") {
      runAmCommandWhenServiceIsReady(
        amCommandName = "gc",
        timeout = device.session.property(AM_SERVICE_TIMEOUT),
        retryDelay = device.session.property(AM_SERVICE_RETRY_DELAY),
      ) {
        device.session.activityManagerServices.gc(device.selector, pid)
      }
    }
  }

  /**
   * Uses `adb shell am force-stop` to terminate an app.
   *
   * @throws AdbActivityManagerException if the `am` command failed or is not supported
   * @throws IOException if there was an issue communicating with the device
   * @see AdbActivityManagerServices.forceStop
   */
  suspend fun forceStop(packageName: String) {
    mapTimeoutToAdbException("force-stop $packageName") {
      runAmCommandWhenServiceIsReady(
        amCommandName = "force-stop",
        timeout = device.session.property(AM_SERVICE_TIMEOUT),
        retryDelay = device.session.property(AM_SERVICE_RETRY_DELAY),
      ) {
        device.session.activityManagerServices.forceStop(device.selector, packageName)
      }
    }
  }

  /**
   * Returns various device/run-time capabilities in [AmCapabilitiesResult] using the `adb shell am capabilities` command.
   *
   * Note: This method retries the command if the device is not ready, see [AdbActivityManagerServices.capabilities] for a detailed
   * description of error conditions.
   *
   * Note: The [AmCapabilitiesResult] is stored in the [ConnectedDevice.cache] if successfully retrieved.
   *
   * @throws AdbActivityManagerException if the `am` command failed or is not supported
   * @throws IOException if there was an issue communicating with the device
   * @see AdbActivityManagerServices.capabilities
   * @see isCapabilitiesSupported
   */
  suspend fun capabilities(): AmCapabilitiesResult {
    return device.cache.getOrPutSuspending(capabilitiesKey) {
      mapTimeoutToAdbException("capabilities") {
        runAmCommandWhenServiceIsReady(
          amCommandName = "capabilities",
          timeout = device.session.property(AM_SERVICE_TIMEOUT),
          retryDelay = device.session.property(AM_SERVICE_RETRY_DELAY),
        ) {
          logger.debug { "Retrieving device capabilities from activity manager" }
          device.session.activityManagerServices.capabilities(device.selector)
        }
      }
    }
  }

  /** Returns the result of the [amCommand]. If necessary, waits for device to come online or for the activity service to start running. */
  private suspend fun <R> runAmCommandWhenServiceIsReady(
    amCommandName: String,
    timeout: Duration,
    retryDelay: Duration,
    amCommand: suspend () -> R,
  ): R {
    return session.withErrorTimeout(timeout) {
      device.waitUntilOnline()
      device.waitUntilServiceIsReady("activity", retryDelay)

      try {
        amCommand()
      } catch (cause: AdbActivityManagerException) {
        if (cause.isCommandNotSupported) {
          logger.debug { "`am $amCommandName' is not supported" }
        }
        throw cause
      }
    }
  }

  companion object {
    private val capabilitiesKey = CoroutineScopeCache.Key<AmCapabilitiesResult>("capabilitiesKey")
  }
}

/**
 * Returns `true` if the device supports the `am capabilities` command.
 *
 * @see AdbActivityManagerServices.capabilities
 */
suspend fun ActivityManager.isCapabilitiesSupported(): Boolean {
  return device.deviceProperties().api() >= 34
}

/**
 * Returns `true` if the device supports the `am crash` command.
 *
 * @see AdbActivityManagerServices.crash
 */
suspend fun ActivityManager.isCrashSupported(): Boolean {
  return device.deviceProperties().api() >= 26
}

/**
 * Returns `true` if the device supports the `am gc` command.
 *
 * @see AdbActivityManagerServices.gc
 */
suspend fun ActivityManager.isGcSupported(): Boolean {
  return isCapabilitiesSupported() && capabilities().capabilities.contains("gc")
}

/**
 * Access to various `pm` services for a given [ConnectedDevice].
 *
 * See [pm command](https://developer.android.com/tools/adb#pm)
 */
class PackageManager(val device: ConnectedDevice) {

  private val session: AdbSession
    get() = device.session

  private val logger = adbLogger(session).withDevicePrefix(device)

  /**
   * Uses `adb shell pm uninstall` to uninstall an app.
   *
   * Note: This method retries the command if the device is not ready, see [runPmCommandWhenServiceIsReady] for a detailed description of
   * error conditions.
   *
   * @throws AdbPackageManagerException if the `pm` command failed
   * @throws IOException if there was an issue communicating with the device
   * @see AdbPackageManagerServices.uninstall
   */
  suspend fun uninstall(packageName: String) {
    mapTimeoutToAdbException("uninstall $packageName") {
      try {
        runPmCommandWhenServiceIsReady(
          timeout = device.session.property(PM_SERVICE_TIMEOUT),
          retryDelay = device.session.property(PM_SERVICE_RETRY_DELAY),
        ) {
          device.session.packageManagerServices.uninstall(device.selector, packageName)
        }
      } catch (cause: AdbPackageManagerException) {
        if (cause.isCommandNotSupported) {
          // This should never happen as `pm uninstall` command is supported since at least API 16.
          logger.warn("`pm uninstall' is not supported on this device")
        }
        throw cause
      }
    }
  }

  /**
   * Uses `adb shell pm clear` to clear the app data.
   *
   * Note: This method retries the command if the device is not ready, see [runPmCommandWhenServiceIsReady] for a detailed description of
   * error conditions.
   *
   * @throws AdbPackageManagerException if the `pm` command failed
   * @throws IOException if there was an issue communicating with the device
   * @see AdbPackageManagerServices.clear
   */
  suspend fun clear(packageName: String) {
    mapTimeoutToAdbException("clear $packageName") {
      try {
        runPmCommandWhenServiceIsReady(
          timeout = device.session.property(PM_SERVICE_TIMEOUT),
          retryDelay = device.session.property(PM_SERVICE_RETRY_DELAY),
        ) {
          device.session.packageManagerServices.clear(device.selector, packageName)
        }
      } catch (cause: AdbPackageManagerException) {
        if (cause.isCommandNotSupported) {
          // This should never happen as `pm clear` command is supported since at least API 16.
          logger.warn("`pm clear' is not supported on this device")
        }
        throw cause
      }
    }
  }

  /** Returns the result of the [pmCommand]. If necessary, waits for device to come online or for the package service to start running. */
  private suspend fun <R> runPmCommandWhenServiceIsReady(timeout: Duration, retryDelay: Duration, pmCommand: suspend () -> R): R {
    return session.withErrorTimeout(timeout) {
      device.waitUntilOnline()
      device.waitUntilServiceIsReady("package", retryDelay)

      pmCommand()
    }
  }
}

/** Wait until the [serviceName] is ready on the device. */
private suspend fun ConnectedDevice.waitUntilServiceIsReady(serviceName: String, retryDelay: Duration) {
  val logger = adbLogger(session).withDevicePrefix(this)
  while (true) {
    val checkOutput = shell.executeAsText("service check $serviceName")
    if (checkOutput.stdout.contains("Service $serviceName: found")) {
      break
    } else if (checkOutput.stdout.contains("Service $serviceName: not found")) {
      logger.debug { "'$serviceName' service is not running, retry after delay" }
      delay(retryDelay.toSafeMillis())
    } else {
      throw IOException("Unexpected output from 'service check $serviceName': ${checkOutput.stdout}")
    }
  }
}

/**
 * Wrap TimeoutException in IOException.
 *
 * When the timeout is an implementation detail, and callers are only expected to handle generic I/O errors we should rethrow
 * TimeoutException as IOException.
 */
private inline fun <R> mapTimeoutToAdbException(commandDescription: String, block: () -> R): R {
  return try {
    block()
  } catch (e: TimeoutException) {
    throw AdbIOTimeoutException("Operation timed out executing `$commandDescription`", e)
  }
}

/** Manages file transfer for a given [ConnectedDevice] */
class FileSystemManager(val device: ConnectedDevice) {

  /**
   * Opens a [AdbDeviceSyncServices] session on this [device] for performing one or more file transfer operation in the given [block].
   *
   * @see AdbDeviceServices.sync
   */
  suspend inline fun <R> withSyncServices(block: (AdbDeviceSyncServices) -> R): R {
    return device.session.deviceServices.withSyncServices(device.selector, block)
  }

  /**
   * Copies the contents of [sourceChannel] to the [remoteFilePath] of this [device].
   *
   * @see AdbDeviceServices.syncSend
   */
  suspend fun sendFile(
    sourceChannel: AdbInputChannel,
    remoteFilePath: String,
    remoteFileMode: RemoteFileMode,
    remoteFileTime: FileTime? = null,
    progress: SyncProgress? = null,
    bufferSize: Int = SYNC_DATA_MAX,
  ) {
    device.session.deviceServices.syncSend(
      device.selector,
      sourceChannel,
      remoteFilePath,
      remoteFileMode,
      remoteFileTime,
      progress,
      bufferSize,
    )
  }

  /**
   * Copies the contents of [sourcePath] to the [remoteFilePath] of this [device].
   *
   * @see AdbDeviceServices.syncSend
   */
  suspend fun sendFile(
    sourcePath: Path,
    remoteFilePath: String,
    remoteFileMode: RemoteFileMode,
    remoteFileTime: FileTime? = null,
    progress: SyncProgress? = null,
    bufferSize: Int = SYNC_DATA_MAX,
  ) {
    device.session.deviceServices.syncSend(
      device.selector,
      sourcePath,
      remoteFilePath,
      remoteFileMode,
      remoteFileTime,
      progress,
      bufferSize,
    )
  }

  /**
   * Copies the contents of the [remoteFilePath] of this [device] to [destinationChannel].
   *
   * @see AdbDeviceServices.syncRecv
   */
  suspend fun receiveFile(
    remoteFilePath: String,
    destinationChannel: AdbOutputChannel,
    progress: SyncProgress? = null,
    bufferSize: Int = SYNC_DATA_MAX,
  ) {
    device.session.deviceServices.syncRecv(device.selector, remoteFilePath, destinationChannel, progress, bufferSize)
  }

  /**
   * Copies the contents of the [remoteFilePath] of this [device] to [destinationPath].
   *
   * @see AdbDeviceServices.syncRecv
   */
  suspend fun receiveFile(remoteFilePath: String, destinationPath: Path, progress: SyncProgress? = null, bufferSize: Int = SYNC_DATA_MAX) {
    device.session.deviceServices.syncRecv(device.selector, remoteFilePath, destinationPath, progress, bufferSize)
  }
}

/** Manages execution of shell commands for a given [ConnectedDevice] */
class ShellManager(val device: ConnectedDevice) {

  /**
   * Returns a [ShellCommand] instance for executing an arbitrary shell command.
   *
   * @see AdbDeviceServices.shellCommand
   */
  fun command(command: String): ShellCommand<*> {
    return device.session.deviceServices.shellCommand(device.selector, command)
  }

  /**
   * Executes a shell [command] on the device, and returns the result of the execution as a [ShellCommandOutput].
   *
   * Note: This method should be used only for commands that output a relatively small amount of text.
   *
   * @see AdbDeviceServices.shellAsText
   */
  suspend fun executeAsText(
    command: String,
    stdinChannel: AdbInputChannel? = null,
    commandTimeout: Duration = INFINITE_DURATION,
    bufferSize: Int = device.session.property(AdbLibProperties.DEFAULT_SHELL_BUFFER_SIZE),
  ): ShellCommandOutput {
    return device.session.deviceServices.shellAsText(device.selector, command, stdinChannel, commandTimeout, bufferSize)
  }

  /**
   * Executes a shell [command] on the device, and returns the result of the execution as a [Flow] of [ShellCommandOutputElement].
   *
   * @see AdbDeviceServices.shellAsLines
   */
  fun executeAsLines(
    command: String,
    stdinChannel: AdbInputChannel? = null,
    commandTimeout: Duration = INFINITE_DURATION,
    bufferSize: Int = device.session.property(AdbLibProperties.DEFAULT_SHELL_BUFFER_SIZE),
  ): Flow<ShellCommandOutputElement> {
    return device.session.deviceServices.shellAsLines(device.selector, command, stdinChannel, commandTimeout, bufferSize)
  }

  /**
   * Executes a shell [command] on the device, and returns the result of the execution as a [Flow] of [BatchShellCommandOutputElement].
   *
   * @see AdbDeviceServices.shellAsLineBatches
   */
  fun executeAsLineBatches(
    command: String,
    stdinChannel: AdbInputChannel? = null,
    commandTimeout: Duration = INFINITE_DURATION,
    bufferSize: Int = device.session.property(AdbLibProperties.DEFAULT_SHELL_BUFFER_SIZE),
  ): Flow<BatchShellCommandOutputElement> {
    return device.session.deviceServices.shellAsLineBatches(device.selector, command, stdinChannel, commandTimeout, bufferSize)
  }
}

/**
 * Returns the list of features supported by both the [device] and the ADB server.
 *
 * See [AdbFeatures] for a (subset of the) list of possible features.
 */
suspend fun ConnectedDevice.availableFeatures(): Set<String> {
  return session.hostServices.availableFeatures(selector)
}

/**
 * Whether an [AdbFeatures] is supported by both the [device] and the ADB server.
 *
 * See [AdbFeatures] for a (subset of the) list of possible features.
 */
suspend fun ConnectedDevice.hasAvailableFeature(feature: String): Boolean {
  return session.hostServices.hasAvailableFeature(selector, feature)
}

/**
 * Returns `true` if the device supports running commands as root, i.e. if the `adbd` daemon on the device is running with root permissions.
 */
suspend fun ConnectedDevice.isRoot(): Boolean {
  val result = session.deviceServices.shellAsText(selector, "echo \$USER_ID")
  val userID = result.stdout.trim { it <= ' ' }
  return userID == "0"
}

fun AdbLogger.withDevicePrefix(device: ConnectedDevice): AdbLogger {
  return withPrefix("${device.session} - $device - ")
}

fun AdbLogger.withProcessPrefix(device: ConnectedDevice, pid: Int): AdbLogger {
  return withPrefix("${device.session} - $device - pid=$pid - ")
}
