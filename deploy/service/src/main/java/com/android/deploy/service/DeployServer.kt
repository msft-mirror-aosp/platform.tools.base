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

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.Client
import com.android.ddmlib.ClientData.DebuggerStatus
import com.android.ddmlib.IDevice
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
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.AdbClient
import com.android.tools.deployer.AdbInstaller
import com.android.tools.deployer.DeployMetric
import com.android.tools.deployer.DeployerRunner
import com.android.tools.deployer.Installer
import com.android.tools.idea.io.grpc.Server
import com.android.tools.idea.io.grpc.netty.NettyServerBuilder
import com.android.tools.idea.io.grpc.stub.StreamObserver
import com.android.tools.idea.protobuf.ByteString
import com.google.common.annotations.VisibleForTesting
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.min

class DeployServer : DeployServiceImplBase {

    private lateinit var myActiveBridge: AndroidDebugBridge
    private lateinit var myServer: Server
    private val myDeployRunner: DeployerRunner?
    private val myDeployerInteraction = DeployerInteraction()

    constructor() {
        myDeployRunner = DeployerRunner(
            File.createTempFile("deploy_cache", ".files"),
            File.createTempFile("deploy_db", ".db"),
            myDeployerInteraction
        )
    }

    /**
     * This function is responsible for starting a GRPC server and blocks until the server is
     * terminated.
     *
     * @param port to open grpc server on
     * @param adbPath full path to adb.exe required by [AndroidDebugBridge].
     */
    fun start(port: Int, adbPath: String) {
        AdbHelper.initAndroidDebugBridge()
        myActiveBridge = AndroidDebugBridge.createBridge(adbPath, false)!!
        val serverBuilder = NettyServerBuilder.forPort(port)
        serverBuilder.addService(this)
        myServer = serverBuilder.build()!!
        myServer.start()
        myServer.awaitTermination()
    }

    @VisibleForTesting
    constructor(bridge: AndroidDebugBridge, deployerRunner: DeployerRunner?) {
        myActiveBridge = bridge
        myDeployRunner = deployerRunner
    }

    override fun getDevices(
        request: DeviceRequest, responseObserver: StreamObserver<DeviceResponse>
    ) {
        val response = DeviceResponse.newBuilder()
        myActiveBridge.devices.forEach {
            response.addDevices(ddmDeviceToRpcDevice(it))
        }
        responseObserver.onNext(response.build())
        responseObserver.onCompleted()
    }

    override fun getClients(
        request: Service.ClientRequest, responseObserver: StreamObserver<ClientResponse>
    ) {
        val response = ClientResponse.newBuilder()
        myActiveBridge.devices.forEach { device ->
            if (!request.deviceId.isEmpty() && request.deviceId != device.serialNumber) {
                // We are looking for a particular device, but this is not that device.
                return@forEach
            }
            for (client in device.getClients()) {
                response.addClients(ddmClientToRpcClient(client))
            }
        }
        responseObserver.onNext(response.build())
        responseObserver.onCompleted()
    }

    override fun getDebugPort(
        request: DebugPortRequest, responseObserver: StreamObserver<DebugPortResponse>
    ) {
        val response = DebugPortResponse.newBuilder()
        val selectedDevice = getDeviceBySerial(request.deviceId)
        if (selectedDevice == null) {
            // TODO (gijosh): Respond with error.
            responseObserver.onNext(response.build())
            responseObserver.onCompleted()
            return
        }
        val selectedClient = selectedDevice.clients.firstOrNull { client ->
            client.clientData.pid == request.pid
        }
        if (selectedClient == null) {
            // TODO (gijosh): Respond with error.
            responseObserver.onNext(response.build())
            responseObserver.onCompleted()
            return
        }
        // This logic mirrors logic in the ConnectDebuggerTask used by Android Studio
        // When a client is created it will immediately start a debugging server. The
        // server then waits for a WAIT jdwp packet to indicate the client is
        // waiting for a debugger to attach. If this loop times out and returns early
        // the client may connect a debugger to a client that is not in this state.
        // The behavior at that point is undefined. Most of the time it will work,
        // some breakpoints may get missed.
        for (i in 0..GET_DEBUG_PORT_RETRY_LIMIT) {
            try { // Wait until we receive the WAIT packet.
                val status = selectedClient.clientData.debuggerConnectionStatus
                if (status == DebuggerStatus.WAITING) {
                    break
                }
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                // Sleep interrupted this is okay.
                break
            }
        }
        response.port = selectedClient.debuggerListenPort
        responseObserver.onNext(response.build())
        responseObserver.onCompleted()
    }

    override fun installApk(
        request: InstallApkRequest, responseObserver: StreamObserver<InstallApkResponse>
    ) {
        val device = getDeviceBySerial(request.deviceId)
        if (device == null) {
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
        val exitCode = myDeployRunner!!.run(device, arguments.toTypedArray<String>(), logger)
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

    override fun runNetworkTest(
        request: Service.NetworkTestRequest,
        responseObserver: StreamObserver<Service.NetworkTestResponse>
    ) {
        val device = getDeviceBySerial(request.deviceId)
        val logger = DeployLogger(DeployLogger.Level.ERROR)
        val adb = AdbClient(device, logger)
        val metrics = mutableListOf<DeployMetric>()
        val installer: Installer =
            AdbInstaller(null, adb, metrics, logger, AdbInstaller.Mode.DAEMON)
        var response = Service.NetworkTestResponse.newBuilder()
        when (request.test.type) {
            NetworkTest.Type.BANDWIDTH -> response = doBandwidthTest(installer, request.test)
            NetworkTest.Type.PING -> response = doPingTest(installer, request.test)
            NetworkTest.Type.UNKNOWN, NetworkTest.Type.UNRECOGNIZED -> {}
        }

        responseObserver.onNext(response.setTest(request.test).build())
        responseObserver.onCompleted()
    }

    @VisibleForTesting
    fun doBandwidthTest(
        installer: Installer,
        testParams: NetworkTest
    ): Service.NetworkTestResponse.Builder {
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
                    val testRequest =
                        Deploy.NetworkTestRequest.newBuilder()
                            .setData(data)
                            .setCurrentTimeNs(System.nanoTime())
                            .build()
                    val testResponse = installer.networkTest(testRequest)
                    bytesSent += testRequest.serializedSize
                    bytesReceived += testResponse.serializedSize
                }
                response.durationNs = System.nanoTime() - startTime
            } else {
                var startTime = System.nanoTime()
                while (bytesReceived < testSizeInBytes) {
                    val testRequest =
                        Deploy.NetworkTestRequest.newBuilder()
                            .setResponseDataSize(bufferSize)
                            .setCurrentTimeNs(System.nanoTime())
                            .build()
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
    fun doPingTest(
        installer: Installer,
        testParams: NetworkTest
    ): Service.NetworkTestResponse.Builder {
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
                val startTime =
                    startPingResponse.processingDurationNs + startPingResponse.currentTimeNs
                response.durationNs = endPingResponse.currentTimeNs - startTime
            }
        } catch (ex: IOException) {
            response.error = ex.message
        }
        return response
    }

    private fun getDeviceBySerial(serial: String): IDevice? {
        return myActiveBridge.devices.firstOrNull { device ->
            device.serialNumber == serial
        }
    }

    companion object {
        const val MAX_BUFFER_SIZE: Int = 1024 * 1024
        const val GET_DEBUG_PORT_RETRY_LIMIT = 10
    }
}

private fun convertToMetricsProto(metrics: List<DeployMetric>): List<Service.DeployMetric> {
    return metrics.map { metric: DeployMetric ->
            Service.DeployMetric.newBuilder()
                .setName(metric.name)
                .setStatus(if (metric.hasStatus()) metric.status else "")
                .setStartNs(metric.startTimeNs)
                .setEndNs(metric.endTimeNs)
                .build()
        }.toList()
}

private fun ddmDeviceToRpcDevice(device: IDevice): Service.Device {
    val avdOrEmpty = device.avdName ?: ""
    val modelOrEmpty = device.getProperty(IDevice.PROP_DEVICE_MODEL) ?: ""
    return Service.Device.newBuilder()
        .addAllAbis(device.abis)
        .setAvd(avdOrEmpty)
        .setDevice(device.name)
        .setIsEmulator(device.isEmulator())
        .setModel(modelOrEmpty)
        .setSerialNumber(device.serialNumber)
        .setStatus(device.state.state)
        .build()
}

private fun ddmClientToRpcClient(client: Client): Service.Client {
    val packageName = client.clientData.packageName
    val description = client.clientData.processName

    val builder = Service.Client.newBuilder()
    builder.pid = client.clientData.pid
    if (packageName != null) {
        builder.name = packageName
    }
    if (description != null) {
        builder.description = description
    }
    return builder.build()
}

