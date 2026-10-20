/*
 * Copyright (C) 2020 The Android Open Source Project
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
import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.DevicePropertyNames
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.deviceInfo
import com.android.adblib.deviceProperties
import com.android.adblib.isOnline
import com.android.adblib.serialNumber
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.getOrDefault
import com.android.adblib.tools.debugging.getOrNull
import com.android.adblib.tools.debugging.jdwpProcessTracker
import com.android.adblib.tools.debugging.jdwpProxySocketServer
import com.android.adblib.tools.debugging.properties
import com.android.adblib.tools.debugging.propertiesFlow
import com.android.adblib.tools.localConsoleAddress
import com.android.adblib.tools.openEmulatorConsole
import com.android.deploy.service.proto.DeployServiceGrpc.DeployServiceImplBase
import com.android.deploy.service.proto.Service
import com.android.deploy.service.proto.Service.ClientResponse
import com.android.deploy.service.proto.Service.DebugPortRequest
import com.android.deploy.service.proto.Service.DebugPortResponse
import com.android.deploy.service.proto.Service.DeviceRequest
import com.android.deploy.service.proto.Service.DeviceResponse
import com.android.deploy.service.proto.Service.InstallApkRequest
import com.android.deploy.service.proto.Service.InstallApkResponse
import com.android.deploy.service.proto.Service.NetworkTest
import com.android.deploy.service.proto.Service.ResumeProcessRequest
import com.android.deploy.service.proto.Service.ResumeProcessResponse
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.AdbInstaller
import com.android.tools.deployer.DeployerRunner
import com.android.tools.deployer.common.AdbClient
import com.android.tools.deployer.common.DeployMetric
import com.android.tools.deployer.common.DeviceHolder
import com.android.tools.deployer.common.Installer
import com.android.tools.idea.io.grpc.Server
import com.android.tools.idea.io.grpc.netty.NettyServerBuilder
import com.android.tools.idea.io.grpc.stub.StreamObserver
import com.android.tools.idea.protobuf.ByteString
import com.google.common.annotations.VisibleForTesting
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.Random
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class DeployServer : DeployServiceImplBase {

  private val mySession: AdbSession
  private lateinit var myServer: Server
  private val myDeployRunner: DeployerRunner?
  private val myDeployerInteraction = DeployerInteraction()

  constructor(session: AdbSession) {
    mySession = session
    myDeployRunner =
      DeployerRunner(File.createTempFile("deploy_cache", ".files"), File.createTempFile("deploy_db", ".db"), myDeployerInteraction)
    AdbHelper.startDeviceAndProcessTracking(mySession)
  }

  /**
   * This function is responsible for starting a GRPC server and blocks until the server is terminated.
   *
   * @param port to open grpc server on
   */
  fun runServer(port: Int) {
    val serverBuilder = NettyServerBuilder.forPort(port)
    serverBuilder.addService(this)
    myServer = serverBuilder.build()!!
    myServer.start()
    myServer.awaitTermination()
  }

  @VisibleForTesting
  constructor(deployerRunner: DeployerRunner?, session: AdbSession) {
    myDeployRunner = deployerRunner
    mySession = session
    AdbHelper.startDeviceAndProcessTracking(mySession)
  }

  override fun getDevices(request: DeviceRequest, responseObserver: StreamObserver<DeviceResponse>) {
    val response = DeviceResponse.newBuilder()
    runBlocking {
      mySession.connectedDevicesTracker.connectedDevices.value.forEach { response.addDevices(connectedDeviceToRpcDevice(it)) }
    }
    responseObserver.onNext(response.build())
    responseObserver.onCompleted()
  }

  override fun getClients(request: Service.ClientRequest, responseObserver: StreamObserver<ClientResponse>) {
    val response = ClientResponse.newBuilder()
    mySession.connectedDevicesTracker.connectedDevices.value.forEach { device ->
      if (!request.deviceId.isEmpty() && request.deviceId != device.serialNumber) {
        // We are looking for a particular device, but this is not that device.
        return@forEach
      }
      for (jdwpProcess in device.jdwpProcessTracker.processesFlow.value) {
        response.addClients(jdwpProcessToRpcClient(jdwpProcess))
      }
    }
    responseObserver.onNext(response.build())
    responseObserver.onCompleted()
  }

  override fun getDebugPort(request: DebugPortRequest, responseObserver: StreamObserver<DebugPortResponse>) {
    val response = DebugPortResponse.newBuilder()
    val selectedDevice = runBlocking { AdbHelper.getConnectedDevice(mySession, request.deviceId) }
    if (selectedDevice == null) {
      // TODO (gijosh): Respond with error.
      responseObserver.onNext(response.build())
      responseObserver.onCompleted()
      return
    }
    val selectedProcess = selectedDevice.jdwpProcessTracker.processesFlow.value.firstOrNull { process -> process.pid == request.pid }
    if (selectedProcess == null) {
      // TODO (gijosh): Respond with error.
      responseObserver.onNext(response.build())
      responseObserver.onCompleted()
      return
    }
    // This logic mirrors logic in the ConnectDebuggerTask used by Android Studio.
    // When a client is created it will immediately start a debugging server. We
    // then wait until adblib reports that the client is waiting for a debugger to
    // attach, which adblib learns from the JDWP WAIT packet or, on devices that
    // support it, from app info. If this wait times out and returns early the
    // client may connect a debugger to a client that is not in this state. The
    // behavior at that point is undefined. Most of the time it will work, some
    // breakpoints may get missed.
    val socketAddress = runBlocking {
      // Wait until adblib reports the process as waiting for a debugger.
      withTimeoutOrNull(WAIT_FOR_DEBUGGER_TIMEOUT_MS) {
        selectedProcess.propertiesFlow.first { it.isWaitingForDebugger.getOrDefault(false) }
      }
      withTimeoutOrNull(1000) {
        selectedProcess.jdwpProxySocketServer.proxyStatusFlow.first { !it.socketAddress.isEmpty }.socketAddress.getOrNull()
      }
    }
    response.port = socketAddress?.port ?: -1
    responseObserver.onNext(response.build())
    responseObserver.onCompleted()
  }

  override fun resumeProcess(request: ResumeProcessRequest, responseObserver: StreamObserver<ResumeProcessResponse>) {
    val response = ResumeProcessResponse.newBuilder()
    runBlocking {
      try {
        AdbHelper.resumeProcess(mySession, request.deviceId, request.pid)
        responseObserver.onNext(response.build())
        responseObserver.onCompleted()
      } catch (e: Exception) {
        responseObserver.onError(e)
      }
    }
  }

  override fun installApk(request: InstallApkRequest, responseObserver: StreamObserver<InstallApkResponse>) {
    val connectedDevice = runBlocking { AdbHelper.getConnectedDevice(mySession, request.deviceId) }
    if (connectedDevice == null) {
      responseObserver.onNext(
        InstallApkResponse.newBuilder()
          .setExitStatus(-1)
          .addMessage("Cannot find device with the given device id: ${request.deviceId}")
          .build()
      )
      responseObserver.onCompleted()
      return
    }
    myDeployerInteraction.setPromptResponses(request.promptResponseList)
    val logger = DeployLogger(DeployLogger.Level.ERROR)
    val arguments = mutableListOf<String>()
    arguments.add("install") // Required by the deployer runner.
    arguments.add(request.packageName)
    arguments.addAll(request.apkList) // The string[] blob is not documented after discussing with the engineers the format is:
    //   install <packageName> <baseApk> [additionalApks]...
    // For instance:
    // install com.example.myApp c:\Temp\myapp.apk
    val deviceHolder = DeviceHolder(connectedDevice)
    val exitCode = myDeployRunner!!.run(deviceHolder, mySession, arguments.toTypedArray<String>(), logger)
    responseObserver.onNext(
      InstallApkResponse.newBuilder()
        .setExitStatus(exitCode)
        .addAllMessage(myDeployerInteraction.messages)
        .addAllPrompt(myDeployerInteraction.prompts)
        .setLog(logger.toProto())
        .addAllMetric(convertToMetricsProto(myDeployRunner.metrics))
        .build()
    )
    responseObserver.onCompleted()
  }

  override fun runNetworkTest(request: Service.NetworkTestRequest, responseObserver: StreamObserver<Service.NetworkTestResponse>) {
    val connectedDevice = runBlocking { AdbHelper.getConnectedDevice(mySession, request.deviceId) }
    if (connectedDevice == null) {
      responseObserver.onNext(
        Service.NetworkTestResponse.newBuilder().setError("Cannot find device with the given device id: ${request.deviceId}").build()
      )
      responseObserver.onCompleted()
      return
    }
    val deviceHolder = DeviceHolder(connectedDevice)
    val logger = DeployLogger(DeployLogger.Level.ERROR)
    val adb = AdbClient(deviceHolder, logger, mySession)
    val metrics = mutableListOf<DeployMetric>()
    val installer: Installer = AdbInstaller(null, adb, metrics, logger, AdbInstaller.Mode.DAEMON)
    var response = Service.NetworkTestResponse.newBuilder()
    when (request.test.type) {
      NetworkTest.Type.BANDWIDTH -> response = doBandwidthTest(installer, request.test)
      NetworkTest.Type.PING -> response = doPingTest(installer, request.test)
      NetworkTest.Type.UNKNOWN,
      NetworkTest.Type.UNRECOGNIZED -> {}
    }

    responseObserver.onNext(response.setTest(request.test).build())
    responseObserver.onCompleted()
  }

  @VisibleForTesting
  fun doBandwidthTest(installer: Installer, testParams: NetworkTest): Service.NetworkTestResponse.Builder {
    val response = Service.NetworkTestResponse.newBuilder()
    try {
      val testSizeInBytes = testParams.numberOfBytes
      val bufferSize = min(MAX_BUFFER_SIZE, testSizeInBytes)
      var bytesSent = 0
      var bytesReceived = 0
      if (testParams.hostToDevice) { // Fill the buffer with random data making it harder to compress if compression is
        // enabled in grpc.
        val buffer = ByteBuffer.allocate(bufferSize)
        val random = Random()
        random.nextBytes(buffer.array())
        val data = ByteString.copyFrom(buffer)
        val startTime = System.nanoTime()
        while (bytesSent < testSizeInBytes) {
          val testRequest = Deploy.NetworkTestRequest.newBuilder().setData(data).setCurrentTimeNs(System.nanoTime()).build()
          val testResponse = installer.networkTest(testRequest)
          bytesSent += testRequest.serializedSize
          bytesReceived += testResponse.serializedSize
        }
        response.durationNs = System.nanoTime() - startTime
      } else {
        var startTime = System.nanoTime()
        while (bytesReceived < testSizeInBytes) {
          val testRequest =
            Deploy.NetworkTestRequest.newBuilder().setResponseDataSize(bufferSize).setCurrentTimeNs(System.nanoTime()).build()
          val testResponse = installer.networkTest(testRequest)
          startTime += testResponse.processingDurationNs
          bytesSent += testRequest.serializedSize
          bytesReceived += testResponse.serializedSize
        }
        response.durationNs = System.nanoTime() - startTime
      }
      response.sentBytes = bytesSent.toLong()
      response.receivedBytes = bytesReceived.toLong()
    } catch (ex: IOException) {
      response.error = ex.message
    }
    return response
  }

  @VisibleForTesting
  fun doPingTest(installer: Installer, testParams: NetworkTest): Service.NetworkTestResponse.Builder {
    val response = Service.NetworkTestResponse.newBuilder()
    try {
      if (testParams.hostToDevice) {
        val testRequest = Deploy.NetworkTestRequest.newBuilder().build()
        var startTime = System.nanoTime()
        val testResponse = installer.networkTest(testRequest)
        startTime += testResponse.processingDurationNs
        response.durationNs = System.nanoTime() - startTime
      } else {
        val testRequest = Deploy.NetworkTestRequest.getDefaultInstance()
        val startPingResponse = installer.networkTest(testRequest)
        val endPingResponse = installer.networkTest(testRequest)
        val startTime = startPingResponse.processingDurationNs + startPingResponse.currentTimeNs
        response.durationNs = endPingResponse.currentTimeNs - startTime
      }
    } catch (ex: IOException) {
      response.error = ex.message
    }
    return response
  }

  companion object {
    const val MAX_BUFFER_SIZE: Int = 1024 * 1024
    private const val WAIT_FOR_DEBUGGER_TIMEOUT_MS = 1000L
  }
}

private fun convertToMetricsProto(metrics: List<DeployMetric>): List<Service.DeployMetric> {
  return metrics
    .map { metric: DeployMetric ->
      Service.DeployMetric.newBuilder()
        .setName(metric.name)
        .setStatus(if (metric.hasStatus()) metric.status else "")
        .setStartNs(metric.startTimeNs)
        .setEndNs(metric.endTimeNs)
        .build()
    }
    .toList()
}

private val LOCAL_EMULATOR_REGEX = "emulator-(\\d+)".toRegex()
private const val DEVICE_NAME_SEPARATOR = "-"
private val AVD_NAME_KEY = CoroutineScopeCache.Key<String?>("DeployServer.avdName")

/**
 * How long [getDevices][DeployServer.getDevices] waits for a device's properties or AVD name, matching the initial `IDevice.getProperty()`
 * timeout of ddmlib. Fetching continues in the background after the timeout, and later calls return the cached result.
 */
private val DEVICE_INFO_TIMEOUT = 5.seconds

private suspend fun connectedDeviceToRpcDevice(device: ConnectedDevice): Service.Device {
  val properties =
    if (device.isOnline) {
      runCatching { withTimeoutOrNull(DEVICE_INFO_TIMEOUT) { device.deviceProperties().allReadonly() } }.getOrNull() ?: emptyMap()
    } else {
      emptyMap()
    }

  // Try abilist first (implemented in L onwards); otherwise, fall back to abi and abi2.
  val abis =
    properties[DevicePropertyNames.RO_PRODUCT_CPU_ABILIST]?.split(",")
      ?: listOfNotNull(properties[DevicePropertyNames.RO_PRODUCT_CPU_ABI], properties[DevicePropertyNames.RO_PRODUCT_CPU_ABI2])
  val isEmulator = LOCAL_EMULATOR_REGEX.matches(device.serialNumber)
  val avdName = if (isEmulator) device.avdName() else null
  val modelOrEmpty = properties[DevicePropertyNames.RO_PRODUCT_MODEL] ?: ""
  return Service.Device.newBuilder()
    .addAllAbis(abis)
    .setAvd(avdName ?: "")
    .setDevice(humanizedName(device.serialNumber, properties, isEmulator, avdName))
    .setIsEmulator(isEmulator)
    .setModel(modelOrEmpty)
    .setSerialNumber(device.serialNumber)
    .setStatus(device.deviceInfo.deviceState.state)
    .build()
}

/**
 * Returns the AVD name of this local emulator, or `null` if the emulator is not online, its console could not provide the AVD name, or the
 * console did not answer within [DEVICE_INFO_TIMEOUT].
 *
 * Like ddmlib, the emulator console is queried at most once per device connection: the result is kept in the device cache, which is cleared
 * when the device disconnects.
 */
private suspend fun ConnectedDevice.avdName(): String? {
  val consolePort = LOCAL_EMULATOR_REGEX.matchEntire(serialNumber)?.groupValues?.get(1)?.toIntOrNull() ?: return null
  if (!isOnline) {
    return null
  }
  return withTimeoutOrNull(DEVICE_INFO_TIMEOUT) {
    cache.getOrPutSuspending(AVD_NAME_KEY) {
      try {
        session.openEmulatorConsole(localConsoleAddress(consolePort)).use { console -> console.avdName() }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
    }
  }
}

/**
 * Returns the same device name as ddmlib's `IDevice.getName()`, which this service used to report, so that clients keep seeing the same
 * value: `"<avd name> [<serial number>]"` for emulators (or just the serial number if the AVD name is not known), and
 * `"<manufacturer>-<model>-<serial number>"` for other devices.
 *
 * Note: This logic matches ddmlib's `IDeviceSharedImpl.constructName()`.
 */
private fun humanizedName(
  serialNumber: String,
  properties: Map<String, String>,
  isEmulator: Boolean,
  avdName: String?,
): String {
  // TODO: Consider reading the AVD name from the `ro.boot.qemu.avd_name` device property (`ro.kernel.qemu.avd_name` on older
  //  emulators) instead of querying the emulator console. This method would then only need device properties, and could be moved to
  //  adblib as a `ConnectedDevice` extension.
  if (isEmulator) {
    return if (avdName != null) "$avdName [$serialNumber]" else serialNumber
  }
  val manufacturer = cleanupStringForDisplay(properties[DevicePropertyNames.RO_PRODUCT_MANUFACTURER])
  val model = cleanupStringForDisplay(properties[DevicePropertyNames.RO_PRODUCT_MODEL])
  // Omit the manufacturer if the model already starts with it, e.g. "samsung_sm_g991b-<serial>" rather than
  // "samsung-samsung_sm_g991b-<serial>".
  val modelStartsWithManufacturer =
    manufacturer != null && model != null && model.uppercase(Locale.US).startsWith(manufacturer.uppercase(Locale.US))
  return listOfNotNull(manufacturer.takeUnless { modelStartsWithManufacturer }, model, serialNumber).joinToString(DEVICE_NAME_SEPARATOR)
}

/** Match ddmlib's `IDeviceSharedImpl.cleanupStringForDisplay()` used by `IDevice.getName()` (see [humanizedName]). */
private fun cleanupStringForDisplay(s: String?): String? {
  if (s == null) {
    return null
  }
  val sb = StringBuilder(s.length)
  for (c in s) {
    if (Character.isLetterOrDigit(c)) {
      sb.append(Character.toLowerCase(c))
    } else {
      sb.append('_')
    }
  }
  return sb.toString()
}

private fun jdwpProcessToRpcClient(process: JdwpProcess): Service.Client {
  val properties = process.properties
  val packageName = properties.packageName.getOrNull()
  val description = properties.processName.getOrNull()

  val builder = Service.Client.newBuilder()
  builder.pid = process.pid
  if (packageName != null) {
    builder.name = packageName
  }
  if (description != null) {
    builder.description = description
  }
  return builder.build()
}
