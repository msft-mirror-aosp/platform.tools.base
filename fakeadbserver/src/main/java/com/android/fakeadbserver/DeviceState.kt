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
package com.android.fakeadbserver

import com.android.fakeadbserver.services.Service
import com.android.fakeadbserver.services.ServiceManager
import com.android.fakeadbserver.statechangehubs.ClientStateChangeHandlerFactory
import com.android.fakeadbserver.statechangehubs.ClientStateChangeHub
import com.android.fakeadbserver.statechangehubs.StateChangeQueue
import com.android.sdklib.AndroidApiLevel
import com.google.common.collect.ImmutableMap
import java.net.Socket
import java.util.Collections
import java.util.TreeMap
import java.util.Vector
import java.util.function.Consumer
import java.util.stream.Collectors
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope

class DeviceState
internal constructor(
  private val mServer: FakeAdbServer,
  val deviceId: String,
  val manufacturer: String,
  val model: String,
  val buildVersionRelease: String,
  val buildVersionSdk: AndroidApiLevel,
  val cpuAbi: String,
  properties: Map<String, String>,
  val hostConnectionType: HostConnectionType,
  val transportId: Int,
  val isRoot: Boolean,
  val maxSpeedMbps: Long,
  val negotiatedSpeedMbps: Long,
) {

  val clientChangeHub = ClientStateChangeHub()
  @PublishedApi internal val fileSystemProvider: DeviceFileSystemProvider = DeviceFileSystemProvider()
  private val mLogcatMessages: MutableList<String> = ArrayList()

  /** PID -> [ProcessState] */
  private val mProcessStates: MutableMap<Int, ProcessState> = HashMap()
  private val mPortForwarders: MutableMap<Int, PortForwarder?> = HashMap()
  private val mReversePortForwarders: MutableMap<Int, PortForwarder?> = HashMap()
  val features: Set<String>
  val properties: Map<String, String>
  private var mDeviceStatus: DeviceStatus
  var delayStdout: Duration = Duration.ZERO
  var acceptsSyncServiceRequests = true
  val serviceManager: ServiceManager

  // Keep track of all AM commands invocation
  private val mAmLogs = Vector<String>()

  // Keep track of all PM commands invocation
  private val mPmLogs = Vector<String>()

  // Keep track of all cmd commands invocation
  private val mCmdLogs = Vector<String>()

  // Keep track of all ABB/ABB_EXEC commands invocation
  private val mAbbLogs = Vector<String>()

  // Keep track of all track-app commands invocation
  var trackAppInvocations: Int = 0
    private set

  // Keep track of all track-jdwp commands invocation
  var trackJdwpInvocations: Int = 0
    private set

  init {
    features = initFeatures(buildVersionSdk)
    this.properties = combinedProperties(deviceId, manufacturer, model, buildVersionRelease, buildVersionSdk, cpuAbi, properties)
    mDeviceStatus = DeviceStatus.OFFLINE
    serviceManager = ServiceManager(this)
  }

  internal constructor(
    server: FakeAdbServer,
    transportId: Int,
    config: DeviceStateConfig,
  ) : this(
    server,
    config.serialNumber,
    config.manufacturer,
    config.model,
    config.buildVersionRelease,
    config.buildVersionSdk,
    config.cpuAbi,
    config.properties,
    config.hostConnectionType,
    transportId,
    config.isRoot,
    config.maxSpeedMbps,
    config.negotiatedSpeedMbps,
  ) {
    fileSystemProvider.withFileSystem { fileSystem -> fileSystem.copyFrom(config.fileSystem) }
    mLogcatMessages.addAll(config.logcatMessages)
    mDeviceStatus = config.deviceStatus
    config.processes.forEach(Consumer { clientState: ProcessState -> mProcessStates[clientState.pid] = clientState })
  }

  fun stop() {
    clientChangeHub.stop()
    deviceCommandTracker.close()
  }

  private val deviceCommandTracker = DeviceCommandTracker(deviceId)

  val apiLevel: Int
    get() = buildVersionSdk.majorVersion

  var deviceStatus: DeviceStatus
    get() = mDeviceStatus
    set(status) {
      mDeviceStatus = status
      mServer.deviceChangeHub.deviceStatusChanged(this, status)
    }

  val deviceCapabilities = DeviceCapabilities.forApi(apiLevel)

  override fun toString(): String {
    return "${this::class.simpleName}(deviceId=$deviceId, deviceStatus=$deviceStatus, apiLevel=$apiLevel, transportId=$transportId)"
  }

  fun addLogcatMessage(message: String) {
    synchronized(mLogcatMessages) {
      mLogcatMessages.add(message)
      clientChangeHub.logcatMessageAdded(message)
    }
  }

  fun subscribeLogcatChangeHandler(handlerFactory: ClientStateChangeHandlerFactory): LogcatChangeHandlerSubscriptionResult? {
    synchronized(mLogcatMessages) {
      val queue = clientChangeHub.subscribe(handlerFactory) ?: return null
      return LogcatChangeHandlerSubscriptionResult(queue, ArrayList(mLogcatMessages))
    }
  }

  /** Provides thread-safe access to the [DeviceFileSystem] */
  inline fun <R> withFileSystem(block: (DeviceFileSystem) -> R): R {
    return fileSystemProvider.withFileSystem(block)
  }

  fun createFile(file: DeviceFileState) {
    return withFileSystem { fileSystem -> fileSystem.createFile(file) }
  }

  fun getFile(filepath: String): DeviceFileState? {
    return withFileSystem { fileSystem -> fileSystem.getFile(filepath) }
  }

  fun getDirectoryFiles(directoryPath: String): List<DeviceFileState> {
    return withFileSystem { fileSystem -> fileSystem.getDirectoryFiles(directoryPath) }
  }

  fun deleteFile(filepath: String) {
    return withFileSystem { fileSystem -> fileSystem.deleteFile(filepath) }
  }

  fun startClient(pid: Int, userId: Int, packageName: String, isWaiting: Boolean): ClientState {
    return startClient(pid = pid, userId = userId, processName = packageName, packageName = packageName, isWaiting = isWaiting)
  }

  fun startClient(pid: Int, userId: Int, processName: String, packageName: String, isWaiting: Boolean): ClientState {
    return startClient(pid = pid, userId = userId, uid = 0, processName = processName, packageName = packageName, isWaiting = isWaiting)
  }

  fun startClient(pid: Int, userId: Int, uid: Int, processName: String, packageName: String, isWaiting: Boolean): ClientState {
    synchronized(mProcessStates) {
      val clientState =
        ClientState(
          device = this,
          pid = pid,
          userId = userId,
          uid = uid,
          processName = processName,
          packageName = packageName,
          waitingForDebugger = isWaiting,
          architecture = cpuAbi,
        )
      mProcessStates[pid] = clientState
      clientChangeHub.clientListChanged()
      clientChangeHub.appProcessListChanged()
      return clientState
    }
  }

  fun stopClient(pid: Int) {
    synchronized(mProcessStates) {
      val processState = mProcessStates.remove(pid)
      if (processState is ClientState) {
        clientChangeHub.clientListChanged()
        clientChangeHub.appProcessListChanged()
        processState.stopJdwpSession()
      }
    }
  }

  fun getClient(pid: Int): ClientState? {
    synchronized(mProcessStates) {
      val processState = mProcessStates[pid]
      return if (processState is ClientState) {
        processState
      } else {
        null
      }
    }
  }

  fun startProfileableProcess(pid: Int, architecture: String, commandLine: String): ProfileableProcessState {
    return startProfileableProcess(
      pid = pid,
      architecture = architecture,
      userId = 0,
      uid = 10,
      processName = commandLine,
      packageName = commandLine,
    )
  }

  fun startProfileableProcess(
    pid: Int,
    architecture: String,
    userId: Int,
    uid: Int,
    processName: String,
    packageName: String,
  ): ProfileableProcessState {
    synchronized(mProcessStates) {
      val process =
        ProfileableProcessState(
          device = this,
          pid = pid,
          architecture = architecture,
          commandLine = processName,
          userId = userId,
          uid = uid,
          packageName = packageName,
        )
      mProcessStates[pid] = process
      clientChangeHub.appProcessListChanged()
      return process
    }
  }

  fun stopProfileableProcess(pid: Int) {
    synchronized(mProcessStates) {
      val process = mProcessStates.remove(pid)
      if (process is ProfileableProcessState) {
        clientChangeHub.appProcessListChanged()
      }
    }
  }

  fun getProfileableProcess(pid: Int): ProfileableProcessState? {
    synchronized(mProcessStates) {
      val process = mProcessStates[pid]
      return if (process is ProfileableProcessState) {
        process
      } else {
        null
      }
    }
  }

  fun stopClients(packageName: String) {
    synchronized(mProcessStates) {
      for (processState in mProcessStates.values) {
        val client = processState as? ClientState
        if (client != null && client.packageName == packageName) {
          stopClient(client.pid)
        }
      }
    }
  }

  val allPortForwarders: ImmutableMap<Int, PortForwarder?>
    get() {
      synchronized(mPortForwarders) {
        return ImmutableMap.copyOf(mPortForwarders)
      }
    }

  val allReversePortForwarders: ImmutableMap<Int, PortForwarder?>
    get() {
      synchronized(mReversePortForwarders) {
        return ImmutableMap.copyOf(mReversePortForwarders)
      }
    }

  fun addPortForwarder(forwarder: PortForwarder, noRebind: Boolean): Boolean {
    synchronized(mPortForwarders) {
      return if (noRebind) {
        (mPortForwarders.computeIfAbsent(forwarder.source.port) { port: Int? -> forwarder } == forwarder)
      } else {
        // Just overwrite the previous forwarder.
        mPortForwarders[forwarder.source.port] = forwarder
        true
      }
    }
  }

  fun addReversePortForwarder(forwarder: PortForwarder, noRebind: Boolean): Boolean {
    synchronized(mReversePortForwarders) {
      return if (noRebind) {
        (mReversePortForwarders.computeIfAbsent(forwarder.source.port) { port: Int? -> forwarder } == forwarder)
      } else {
        // Just overwrite the previous forwarder.
        mReversePortForwarders[forwarder.source.port] = forwarder
        true
      }
    }
  }

  fun removePortForwarder(hostPort: Int): Boolean {
    synchronized(mPortForwarders) {
      return mPortForwarders.remove(hostPort) != null
    }
  }

  fun removeReversePortForwarder(hostPort: Int): Boolean {
    synchronized(mReversePortForwarders) {
      return mReversePortForwarders.remove(hostPort) != null
    }
  }

  fun removeAllPortForwarders() {
    synchronized(mPortForwarders) { mPortForwarders.clear() }
  }

  fun removeAllReversePortForwarders() {
    synchronized(mReversePortForwarders) { mReversePortForwarders.clear() }
  }

  val clientListString: String
    get() {
      synchronized(mProcessStates) {
        return mProcessStates.values
          .stream()
          .filter { process: ProcessState? -> process is ClientState }
          .map { clientState: ProcessState -> Integer.toString(clientState.pid) }
          .collect(Collectors.joining("\n"))
      }
    }

  fun copyOfProcessStates(): List<ProcessState> {
    synchronized(mProcessStates) {
      return ArrayList(mProcessStates.values)
    }
  }

  internal val config: DeviceStateConfig
    get() =
      DeviceStateConfig(
        serialNumber = deviceId,
        fileSystem = DeviceFileSystem().also { fileSystemProvider.withFileSystem { fileSystem -> it.copyFrom(fileSystem) } },
        logcatMessages = ArrayList(mLogcatMessages),
        processes = ArrayList(mProcessStates.values),
        hostConnectionType = hostConnectionType,
        manufacturer = manufacturer,
        model = model,
        buildVersionRelease = buildVersionRelease,
        buildVersionSdk = buildVersionSdk,
        cpuAbi = cpuAbi,
        properties = properties,
        deviceStatus = mDeviceStatus,
        isRoot = isRoot,
        maxSpeedMbps = maxSpeedMbps,
        negotiatedSpeedMbps = negotiatedSpeedMbps,
      )

  fun setActivityManager(newActivityManager: Service?) {
    serviceManager.setActivityManager(newActivityManager!!)
  }

  fun addAmLog(cmd: String) {
    mAmLogs.add(cmd)
  }

  val amLogs: List<String>
    get() = mAmLogs.clone() as List<String>

  fun addPmLog(cmd: String) {
    mPmLogs.add(cmd)
  }

  val pmLogs: List<String>
    get() = mPmLogs.clone() as List<String>

  fun addCmdLog(cmd: String) {
    mCmdLogs.add(cmd)
  }

  val cmdLogs: List<String>
    get() = mCmdLogs.clone() as List<String>

  fun addAbbLog(cmd: String) {
    mAbbLogs.add(cmd)
  }

  val abbLogs: List<String>
    get() = mAbbLogs.clone() as List<String>

  fun addTrackAppInvocation() {
    trackAppInvocations++
  }

  fun addTrackJdwpInvocation() {
    trackJdwpInvocations++
  }

  internal inline fun <R> trackCommand(command: String, scope: CoroutineScope, socket: Socket, block: () -> R): R {
    return deviceCommandTracker.trackCommand(command, scope, socket, block)
  }

  /** The state of a device. */
  enum class DeviceStatus( //$NON-NLS-1$
    val state: String
  ) {
    ANY("any"),
    CONNECTING("connecting"),
    AUTHORIZING("authorizing"),
    NOPERMISSION("nopermission"),
    DETACHED("detached"),
    DEVICE("device"),
    HOST("host"),
    RESCUE("rescue"),
    UNRECOGNIZED("unrecognized"),
    BOOTLOADER("bootloader"), // $NON-NLS-1$

    /** bootloader mode with is-userspace = true though `adb reboot fastboot` */
    FASTBOOTD("fastbootd"), // $NON-NLS-1$
    OFFLINE("offline"), // $NON-NLS-1$
    ONLINE("device"), // $NON-NLS-1$
    RECOVERY("recovery"), // $NON-NLS-1$

    /** Device is in "sideload" state either through `adb sideload` or recovery menu */
    SIDELOAD("sideload"), // $NON-NLS-1$
    UNAUTHORIZED("unauthorized"), // $NON-NLS-1$
    DISCONNECTED("disconnected");

    companion object {

      /**
       * Returns a [DeviceStatus] from the string returned by `adb devices`.
       *
       * @param state the device state.
       * @return a {DeviceStatus} object or `null` if the state is unknown.
       */
      fun getState(state: String): DeviceStatus? {
        for (deviceStatus in values()) {
          if (deviceStatus.state == state) {
            return deviceStatus
          }
        }
        return null
      }
    }
  }

  enum class HostConnectionType {
    USB,
    LOCAL,
    NETWORK,
  }

  /**
   * This class represents the result of calling [subscribeLogcatChangeHandler]. This is needed to synchronize between adding the listener
   * and getting the correct lines from the logcat buffer.
   */
  class LogcatChangeHandlerSubscriptionResult(val mQueue: StateChangeQueue, val mLogcatContents: List<String>)

  data class DeviceCapabilities(
    val capabilities: List<String>,
    val vmCapabilities: List<String>,
    val frameworkCapabilities: List<String>,
    val vmInfo: VmInfo?,
  ) {

    data class VmInfo(val name: String, val version: String)

    companion object {
      private val api35VmCapabilities =
        """
        method-trace-profiling
        method-trace-profiling-streaming
        method-sample-profiling
        hprof-heap-dump
        hprof-heap-dump-streaming
        app_info
        """
          .trimIndent()
          .lines()

      private val api35FrameworkCapabilities =
        """
        opengl-tracing
        view-hierarchy
        support_boot_stages
        """
          .trimIndent()
          .lines()

      private val api35VmInfo = VmInfo(name = "Dalvik", version = "2.1.0")

      private val api36VmCapabilities = api35VmCapabilities

      private val api36FrameworkCapabilities = api35FrameworkCapabilities + "app_info"

      private val api36VmInfo = api35VmInfo

      fun forApi(apiLevel: Int): DeviceCapabilities? {
        return when {
          apiLevel <= 33 -> {
            null
          }
          apiLevel <= 34 -> {
            DeviceCapabilities(
              capabilities = listOf("start.suspend"),
              vmCapabilities = emptyList(),
              frameworkCapabilities = emptyList(),
              vmInfo = null,
            )
          }
          apiLevel <= 35 -> {
            DeviceCapabilities(
              capabilities = listOf("start.suspend"),
              vmCapabilities = api35VmCapabilities,
              frameworkCapabilities = api35FrameworkCapabilities,
              vmInfo = api35VmInfo,
            )
          }
          else -> {
            DeviceCapabilities(
              capabilities = listOf("start.suspend"),
              vmCapabilities = api36VmCapabilities,
              frameworkCapabilities = api36FrameworkCapabilities,
              vmInfo = api36VmInfo,
            )
          }
        }
      }
    }
  }

  companion object {
    private fun initFeatures(sdk: AndroidApiLevel): Set<String> {
      val features: MutableSet<String> = HashSet(mutableListOf("push_sync", "fixed_push_mkdir", "apex"))
      val api = sdk.majorVersion
      if (api >= 24) {
        features.add("cmd")
        features.add("shell_v2")
      }
      if (api >= 26) {
        features.add("stat_v2")
      }
      if (api >= 30) {
        features.add("abb")
        features.add("abb_exec")
        features.add("ls_v2")
      }
      if (api >= 31) {
        features.add("track_app")
      }
      if (api >= 34) {
        features.add("support_boot_stages")
      }
      if (api >= 36) {
        features.add("app_info")
      }
      return Collections.unmodifiableSet(features)
    }

    private fun combinedProperties(
      serialNumber: String,
      manufacturer: String,
      model: String,
      release: String,
      sdk: AndroidApiLevel,
      cpuAbi: String,
      properties: Map<String, String>,
    ): Map<String, String> {
      val combined: MutableMap<String, String> = TreeMap(properties)
      combined["ro.serialno"] = serialNumber
      combined["ro.product.manufacturer"] = manufacturer
      combined["ro.product.model"] = model
      combined["ro.build.version.release"] = release
      combined["ro.build.version.sdk"] = sdk.majorVersion.toString()
      if (sdk.majorVersion > 35) {
        combined["ro.build.version.sdk_full"] = sdk.toString()
      }
      combined["ro.product.cpu.abi"] = cpuAbi
      return combined
    }
  }
}
