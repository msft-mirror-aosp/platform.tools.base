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
import com.android.ddmlib.ClientData
import com.android.ddmlib.IDevice
import com.android.deploy.service.proto.Service
import com.android.deploy.service.proto.Service.ClientResponse
import com.android.deploy.service.proto.Service.DebugPortRequest
import com.android.deploy.service.proto.Service.DebugPortResponse
import com.android.deploy.service.proto.Service.DeviceResponse
import com.android.deploy.service.proto.Service.InstallApkRequest
import com.android.deploy.service.proto.Service.InstallApkResponse
import com.android.deploy.service.proto.Service.NetworkTest
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.DeployMetric
import com.android.tools.deployer.DeployerRunner
import com.android.tools.idea.io.grpc.stub.StreamObserver
import com.android.utils.ILogger
import com.google.common.truth.Truth
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

class DeployServerTest {

    var myPackageName: String? = "com.example.myapp"

    var myProcessName: String? = "com.example.myapp:process"

    @Test
    fun getDevices() {
        val bridge = Mockito.mock(AndroidDebugBridge::class.java)
        val devices = arrayOf(mockDevice("1234", IDevice.DeviceState.ONLINE))
        Mockito.`when`<Array<IDevice>>(bridge.getDevices()).thenReturn(devices)
        val server = DeployServer(bridge, null)
        val response = FakeStreamObserver<DeviceResponse>()
        server.getDevices(Service.DeviceRequest.getDefaultInstance(), response)
        Truth.assertThat(response.response).isNotNull()
        Truth.assertThat(response.response!!.devicesCount).isEqualTo(devices.size)
        Truth.assertThat(response.response!!.getDevices(0).serialNumber)
            .isEqualTo(devices[0].getSerialNumber())
    }

    @Test
    fun getClientsForAllDevices() {
        val bridge = Mockito.mock<AndroidDebugBridge>(AndroidDebugBridge::class.java)
        val devices =
            arrayOf(
                mockDevice("1234", IDevice.DeviceState.ONLINE),
                mockDevice("5678", IDevice.DeviceState.ONLINE)
            )
        Mockito.`when`<Array<IDevice>>(bridge.getDevices()).thenReturn(devices)
        val server = DeployServer(bridge, null)
        val response = FakeStreamObserver<ClientResponse>()
        val request = Service.ClientRequest.newBuilder().build()
        server.getClients(request, response)
        Truth.assertThat(response.response).isNotNull()
        Truth.assertThat(response.response!!.clientsCount).isEqualTo(2)
        for (i in 0..1) {
            Truth.assertThat(response.response!!.getClients(i).pid).isEqualTo(1234)
            Truth.assertThat(response.response!!.getClients(i).name).isEqualTo(myPackageName)
            Truth.assertThat(response.response!!.getClients(i).description)
                .isEqualTo(myProcessName)
        }
    }

    @Test
    fun getClientsForOneDevice() {
        val bridge = Mockito.mock<AndroidDebugBridge>(AndroidDebugBridge::class.java)
        val devices =
            arrayOf(
                mockDevice("1234", IDevice.DeviceState.ONLINE),
                mockDevice("5678", IDevice.DeviceState.ONLINE)
            )
        Mockito.`when`<Array<IDevice>>(bridge.getDevices()).thenReturn(devices)
        val server = DeployServer(bridge, null)
        val response = FakeStreamObserver<ClientResponse>()
        val request =
            Service.ClientRequest.newBuilder().setDeviceId("1234").build()
        server.getClients(request, response)
        Truth.assertThat(response.response).isNotNull()
        Truth.assertThat(response.response!!.clientsCount).isEqualTo(1)
        Truth.assertThat(response.response!!.getClients(0).pid).isEqualTo(1234)
        Truth.assertThat(response.response!!.getClients(0).name).isEqualTo(myPackageName)
        Truth.assertThat(response.response!!.getClients(0).description).isEqualTo(myProcessName)
    }

    @Test
    fun getClients_NullProcessAndPackageName() {
        myPackageName = null
        myProcessName = null

        val bridge = Mockito.mock(AndroidDebugBridge::class.java)
        val devices: Array<IDevice> = arrayOf(mockDevice("1234", IDevice.DeviceState.ONLINE))
        Mockito.`when`<Array<IDevice>>(bridge.getDevices()).thenReturn(devices)
        val server = DeployServer(bridge, null)
        val response = FakeStreamObserver<ClientResponse>()
        val request =
            Service.ClientRequest.newBuilder().setDeviceId("1234").build()
        server.getClients(request, response)
        Truth.assertThat(response.response).isNotNull()
        Truth.assertThat(response.response!!.clientsCount).isEqualTo(1)
        Truth.assertThat(response.response!!.getClients(0).pid).isEqualTo(1234)
        Truth.assertThat(response.response!!.getClients(0).name).isEmpty()
        Truth.assertThat(response.response!!.getClients(0).description).isEmpty()
    }

    @Test
    fun getDebugPort() {
        val bridge = Mockito.mock(AndroidDebugBridge::class.java)
        val devices = arrayOf(mockDevice("1234", IDevice.DeviceState.ONLINE))
        Mockito.`when`<Array<IDevice>>(bridge.getDevices()).thenReturn(devices)
        val server = DeployServer(bridge, null)
        val response = FakeStreamObserver<DebugPortResponse>()
        val request =
            DebugPortRequest.newBuilder().setDeviceId("1234").setPid(1234).build()
        server.getDebugPort(request, response)
        Truth.assertThat(response.response).isNotNull()
        Truth.assertThat(response.response!!.port).isEqualTo(4321)
    }

    @Test
    fun installApkNoDevice() {
        val bridge = Mockito.mock(AndroidDebugBridge::class.java)
        val devices = arrayOf(mockDevice("1234", IDevice.DeviceState.ONLINE))
        Mockito.`when`<Array<IDevice>>(bridge.getDevices()).thenReturn(devices)
        val server = DeployServer(bridge, null)
        val response = FakeStreamObserver<InstallApkResponse>()
        val request =
            InstallApkRequest.newBuilder().setDeviceId("4321").build()
        server.installApk(request, response)
        Truth.assertThat(response.response).isNotNull()
        Truth.assertThat(response.response!!.exitStatus).isEqualTo(-1)
        Truth.assertThat(response.response!!.messageCount).isEqualTo(1)
        Truth.assertThat(response.response!!.getMessage(0)).isNotEmpty()
    }

    @Test
    fun installApk() {
        val apkPath = "/fake/path.apk"
        val packageName = "com.example.app"
        val bridge = Mockito.mock(AndroidDebugBridge::class.java)
        val devices = arrayOf(mockDevice("1234", IDevice.DeviceState.ONLINE))
        Mockito.`when`<Array<IDevice>>(bridge.getDevices()).thenReturn(devices)
        val runner = Mockito.mock(DeployerRunner::class.java)
        val deviceCaptor = ArgumentCaptor.forClass(IDevice::class.java)
        val argsCaptor = ArgumentCaptor.forClass(Array<String>::class.java)
        Mockito.`when`(runner.run(deviceCaptor.capture(), argsCaptor.capture(), any()))
            .thenReturn(0)
        val metrics = ArrayList<DeployMetric>()
        metrics.add(DeployMetric("Test", 1, 2))
        Mockito.`when`(runner.metrics).thenReturn(metrics)
        val server = DeployServer(bridge, runner)
        val response = FakeStreamObserver<InstallApkResponse>()
        val request =
            InstallApkRequest.newBuilder()
                .setDeviceId("1234")
                .addApk(apkPath)
                .setPackageName(packageName)
                .build()
        server.installApk(request, response)
        Truth.assertThat(response.response).isNotNull()
        Truth.assertThat(response.response!!.getExitStatus()).isEqualTo(0)
        Truth.assertThat(deviceCaptor.getValue()).isEqualTo(devices[0])
        val args: Array<String> = argsCaptor.getValue()!!
        Truth.assertThat(args[0]).isEqualTo("install")
        Truth.assertThat(args[1]).isEqualTo(packageName)
        Truth.assertThat(args[2]).isEqualTo(apkPath)
        Truth.assertThat(response.response!!.metricCount).isEqualTo(1)
        val actualMetric = response.response!!.getMetric(0)
        Truth.assertThat(actualMetric.name).isEqualTo(metrics[0].name)
        Truth.assertThat(actualMetric.startNs).isEqualTo(metrics[0].startTimeNs)
        Truth.assertThat(actualMetric.endNs).isEqualTo(metrics[0].getEndTimeNs())
    }

    @Test
    fun testBandwidthTestToDevice() {
        val bytesToSend = DeployServer.MAX_BUFFER_SIZE * 5
        val bridge = Mockito.mock(AndroidDebugBridge::class.java)
        val devices = arrayOf(mockDevice("1234", IDevice.DeviceState.ONLINE))
        Mockito.`when`<Array<IDevice>>(bridge.getDevices()).thenReturn(devices)
        val runner = Mockito.mock(DeployerRunner::class.java)
        val server = DeployServer(bridge, runner)
        val request =
            NetworkTest.newBuilder()
                .setType(NetworkTest.Type.BANDWIDTH)
                .setHostToDevice(true)
                .setNumberOfBytes(bytesToSend)
                .build()
        val installer = FakeInstaller()
        val response = server.doBandwidthTest(installer, request).build()
        val requestList: MutableList<Deploy.NetworkTestRequest> = installer.capturedNetworkRequest
        Truth.assertThat(requestList).hasSize(5)
        Truth.assertThat(requestList[0].data).hasSize(DeployServer.MAX_BUFFER_SIZE)
        Truth.assertThat(requestList[0].currentTimeNs).isGreaterThan(0L)
        Truth.assertThat(response.sentBytes).isGreaterThan(bytesToSend.toLong())
        Truth.assertThat(response.durationNs).isGreaterThan(0L)
    }

    @Test
    fun testBandwidthTestToHost() {
        val bytesToReceive = 10
        val bridge = Mockito.mock(AndroidDebugBridge::class.java)
        val devices = arrayOf(mockDevice("1234", IDevice.DeviceState.ONLINE))
        Mockito.`when`<Array<IDevice>>(bridge.getDevices()).thenReturn(devices)
        val runner = Mockito.mock(DeployerRunner::class.java)
        val server = DeployServer(bridge, runner)
        val request =
            NetworkTest.newBuilder()
                .setType(NetworkTest.Type.BANDWIDTH)
                .setHostToDevice(false)
                .setNumberOfBytes(bytesToReceive)
                .build()
        val installer = FakeInstaller()
        val response = server.doBandwidthTest(installer, request).build()
        val requestList: MutableList<Deploy.NetworkTestRequest> = installer.capturedNetworkRequest
        Truth.assertThat(requestList).hasSize(5)
        Truth.assertThat(requestList[0].currentTimeNs).isGreaterThan(0L)
        Truth.assertThat(response.receivedBytes).isAtLeast(bytesToReceive.toLong())
        Truth.assertThat(response.durationNs).isGreaterThan(0L)
    }

    private fun mockDevice(serial: String, state: IDevice.DeviceState): IDevice {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.getSerialNumber()).thenReturn(serial)
        Mockito.`when`(device.getState()).thenReturn(state)
        Mockito.`when`(device.getName()).thenReturn(serial)
        Mockito.`when`(device.isEmulator()).thenReturn(false)
        Mockito.`when`<MutableList<String>>(device.getAbis())
            .thenReturn(mutableListOf<String>("armeabi"))
        val clients: Array<Client> = arrayOf(mockClient(myPackageName, myProcessName))
        Mockito.`when`(device.getClients()).thenReturn(clients)
        return device
    }

    private fun mockClient(clientName: String?, clientDescription: String?): Client {
        val client = Mockito.mock(Client::class.java)
        val clientData = Mockito.mock(ClientData::class.java)
        Mockito.`when`(client.getClientData()).thenReturn(clientData)
        Mockito.`when`(clientData.pid).thenReturn(1234)
        Mockito.`when`<String>(clientData.packageName).thenReturn(clientName)
        Mockito.`when`<String>(clientData.processName).thenReturn(clientDescription)
        Mockito.`when`(client.getDebuggerListenPort()).thenReturn(4321)
        return client
    }

    internal inner class FakeStreamObserver<T> : StreamObserver<T> {

        var response: T? = null
            private set

        override fun onNext(t: T) {
            this.response = t
        }

        override fun onError(throwable: Throwable) {}

        override fun onCompleted() {}
    }
}
