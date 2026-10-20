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

import com.android.adblib.ConnectedDevice
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.tools.debugging.jdwpProcessTracker
import com.android.adblib.tools.debugging.propertiesFlow
import com.android.adblib.waitForDevice
import com.android.adblib.waitUntilState
import com.android.deploy.service.proto.Service
import com.android.deploy.service.proto.Service.ClientResponse
import com.android.deploy.service.proto.Service.DebugPortRequest
import com.android.deploy.service.proto.Service.DebugPortResponse
import com.android.deploy.service.proto.Service.DeviceResponse
import com.android.deploy.service.proto.Service.InstallApkRequest
import com.android.deploy.service.proto.Service.InstallApkResponse
import com.android.deploy.service.proto.Service.NetworkTest
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.DeployerRunner
import com.android.tools.deployer.common.DeployMetric
import com.android.tools.deployer.common.DeviceHolder
import com.android.tools.idea.io.grpc.stub.StreamObserver
import com.google.common.truth.Truth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

class DeployServerTest {

  @get:Rule val fakeAdbRule = FakeAdbServerProviderRule()

  var myPackageName: String? = "com.example.myapp"

  var myProcessName: String? = "com.example.myapp:process"

  @Test
  fun getDevices() {
    val server = DeployServer(null, fakeAdbRule.adbSession)
    val serial = "device-1234"
    connectDevice(serial)
    val response = FakeStreamObserver<DeviceResponse>()
    server.getDevices(Service.DeviceRequest.getDefaultInstance(), response)
    Truth.assertThat(response.response).isNotNull()
    Truth.assertThat(response.response!!.devicesCount).isEqualTo(1)
    val device = response.response!!.getDevices(0)
    Truth.assertThat(device.serialNumber).isEqualTo(serial)
    Truth.assertThat(device.status).isEqualTo("device")
    Truth.assertThat(device.model).isEqualTo("Pixel")
    Truth.assertThat(device.device).isEqualTo("google-pixel-device-1234")
    Truth.assertThat(device.avd).isEmpty()
    Truth.assertThat(device.isEmulator).isFalse()
    Truth.assertThat(device.abisList).containsExactly("x86_64")
  }

  @Test
  fun getDevices_modelStartsWithManufacturer() {
    val server = DeployServer(null, fakeAdbRule.adbSession)
    connectDevice("device-1234", manufacturer = "Samsung", model = "Samsung SM-G991B")
    val response = FakeStreamObserver<DeviceResponse>()
    server.getDevices(Service.DeviceRequest.getDefaultInstance(), response)
    Truth.assertThat(response.response).isNotNull()
    Truth.assertThat(response.response!!.devicesCount).isEqualTo(1)
    Truth.assertThat(response.response!!.getDevices(0).device).isEqualTo("samsung_sm_g991b-device-1234")
  }

  @Test
  fun getDevices_emulator() {
    val server = DeployServer(null, fakeAdbRule.adbSession)
    val consolePort =
      fakeAdbRule.fakeAdb.fakeAdbServer.connectEmulatorConsole(avdName = "Pixel_6_API_33", avdPath = "/avds/Pixel_6_API_33.avd").get().port
    val serial = "emulator-$consolePort"
    connectDevice(serial)
    val response = FakeStreamObserver<DeviceResponse>()
    server.getDevices(Service.DeviceRequest.getDefaultInstance(), response)
    Truth.assertThat(response.response).isNotNull()
    Truth.assertThat(response.response!!.devicesCount).isEqualTo(1)
    val device = response.response!!.getDevices(0)
    Truth.assertThat(device.serialNumber).isEqualTo(serial)
    Truth.assertThat(device.status).isEqualTo("device")
    Truth.assertThat(device.avd).isEqualTo("Pixel_6_API_33")
    Truth.assertThat(device.device).isEqualTo("Pixel_6_API_33 [$serial]")
    Truth.assertThat(device.isEmulator).isTrue()
  }

  @Test
  fun getClientsForAllDevices() {
    val server = DeployServer(null, fakeAdbRule.adbSession)
    val pid = 1234
    connectDeviceWithClient("device-1234", pid)
    connectDeviceWithClient("device-5678", pid)
    val response = FakeStreamObserver<ClientResponse>()
    val request = Service.ClientRequest.newBuilder().build()
    server.getClients(request, response)
    Truth.assertThat(response.response).isNotNull()
    Truth.assertThat(response.response!!.clientsCount).isEqualTo(2)
    for (i in 0..1) {
      Truth.assertThat(response.response!!.getClients(i).pid).isEqualTo(pid)
      Truth.assertThat(response.response!!.getClients(i).name).isEqualTo(myPackageName)
      Truth.assertThat(response.response!!.getClients(i).description).isEqualTo(myProcessName)
    }
  }

  @Test
  fun getClientsForOneDevice() {
    val server = DeployServer(null, fakeAdbRule.adbSession)
    val pid = 1234
    connectDeviceWithClient("device-1234", pid)
    connectDeviceWithClient("device-5678", pid)
    val response = FakeStreamObserver<ClientResponse>()
    val request = Service.ClientRequest.newBuilder().setDeviceId("device-1234").build()
    server.getClients(request, response)
    Truth.assertThat(response.response).isNotNull()
    Truth.assertThat(response.response!!.clientsCount).isEqualTo(1)
    Truth.assertThat(response.response!!.getClients(0).pid).isEqualTo(pid)
    Truth.assertThat(response.response!!.getClients(0).name).isEqualTo(myPackageName)
    Truth.assertThat(response.response!!.getClients(0).description).isEqualTo(myProcessName)
  }

  @Test
  fun getClients_NullProcessAndPackageName() {
    myPackageName = null
    myProcessName = null

    val server = DeployServer(null, fakeAdbRule.adbSession)
    val pid = 1234
    connectDeviceWithClient("device-1234", pid)
    val response = FakeStreamObserver<ClientResponse>()
    val request = Service.ClientRequest.newBuilder().setDeviceId("device-1234").build()
    server.getClients(request, response)
    Truth.assertThat(response.response).isNotNull()
    Truth.assertThat(response.response!!.clientsCount).isEqualTo(1)
    Truth.assertThat(response.response!!.getClients(0).pid).isEqualTo(pid)
    Truth.assertThat(response.response!!.getClients(0).name).isEmpty()
    Truth.assertThat(response.response!!.getClients(0).description).isEmpty()
  }

  @Test
  fun getDebugPort() {
    val server = DeployServer(null, fakeAdbRule.adbSession)
    val pid = 1234
    connectDeviceWithClient("device-1234", pid)
    val response = FakeStreamObserver<DebugPortResponse>()
    val request = DebugPortRequest.newBuilder().setDeviceId("device-1234").setPid(pid).build()
    server.getDebugPort(request, response)
    Truth.assertThat(response.response).isNotNull()
    Truth.assertThat(response.response!!.port).isGreaterThan(0)
  }

  @Test
  fun installApkNoDevice() {
    val server = DeployServer(null, fakeAdbRule.adbSession)
    val presentDeviceId = "device-1234"
    val missingDeviceId = "device-4321"
    connectDevice(presentDeviceId)
    val response = FakeStreamObserver<InstallApkResponse>()
    val request = InstallApkRequest.newBuilder().setDeviceId(missingDeviceId).build()
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
    val runner = Mockito.mock(DeployerRunner::class.java)
    val deviceHolderCaptor = ArgumentCaptor.forClass(DeviceHolder::class.java)
    val argsCaptor = ArgumentCaptor.forClass(Array<String>::class.java)
    Mockito.`when`(runner.run(deviceHolderCaptor.capture(), any(), argsCaptor.capture(), any())).thenReturn(0)
    val metrics = ArrayList<DeployMetric>()
    metrics.add(DeployMetric("Test", 1, 2))
    Mockito.`when`(runner.metrics).thenReturn(metrics)
    val server = DeployServer(runner, fakeAdbRule.adbSession)
    val deviceId = "device-1234"
    connectDevice(deviceId)
    val response = FakeStreamObserver<InstallApkResponse>()
    val request = InstallApkRequest.newBuilder().setDeviceId(deviceId).addApk(apkPath).setPackageName(packageName).build()
    server.installApk(request, response)
    Truth.assertThat(response.response).isNotNull()
    Truth.assertThat(response.response!!.getExitStatus()).isEqualTo(0)
    Truth.assertThat(deviceHolderCaptor.getValue().serialNumber).isEqualTo(deviceId)
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
    val runner = Mockito.mock(DeployerRunner::class.java)
    val server = DeployServer(runner, fakeAdbRule.adbSession)
    val request = NetworkTest.newBuilder().setType(NetworkTest.Type.BANDWIDTH).setHostToDevice(true).setNumberOfBytes(bytesToSend).build()
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
    val runner = Mockito.mock(DeployerRunner::class.java)
    val server = DeployServer(runner, fakeAdbRule.adbSession)
    val request =
      NetworkTest.newBuilder().setType(NetworkTest.Type.BANDWIDTH).setHostToDevice(false).setNumberOfBytes(bytesToReceive).build()
    val installer = FakeInstaller()
    val response = server.doBandwidthTest(installer, request).build()
    val requestList: MutableList<Deploy.NetworkTestRequest> = installer.capturedNetworkRequest
    Truth.assertThat(requestList).hasSize(5)
    Truth.assertThat(requestList[0].currentTimeNs).isGreaterThan(0L)
    Truth.assertThat(response.receivedBytes).isAtLeast(bytesToReceive.toLong())
    Truth.assertThat(response.durationNs).isGreaterThan(0L)
  }

  private fun connectDevice(
    serialNumber: String,
    deviceStatus: DeviceState.DeviceStatus = DeviceState.DeviceStatus.ONLINE,
    manufacturer: String = "Google",
    model: String = "Pixel",
  ): Pair<ConnectedDevice, DeviceState> {
    val deviceState =
      fakeAdbRule.fakeAdb.connectDevice(
        deviceId = serialNumber,
        manufacturer = manufacturer,
        deviceModel = model,
        release = "31",
        sdk = AndroidApiLevel(31),
        hostConnectionType = DeviceState.HostConnectionType.USB,
        maxSpeedMbps = 0,
        negotiatedSpeedMbps = 0,
      )
    deviceState.deviceStatus = deviceStatus
    val connectedDevice = runBlocking {
      val targetState = com.android.adblib.DeviceState.parseState(deviceStatus.state)
      fakeAdbRule.adbSession.connectedDevicesTracker.waitForDevice(serialNumber).also { it.waitUntilState(targetState) }
    }
    return Pair(connectedDevice, deviceState)
  }

  private fun connectDeviceWithClient(serialNumber: String, pid: Int): DeviceState {
    val (connectedDevice, deviceState) = connectDevice(serialNumber)
    deviceState.startClient(pid, 0, myProcessName ?: "", myPackageName ?: "", true)
    // DeployServer only reports what adblib already knows, so wait until adblib has seen the process and its handshake reply (which
    // carries the process and package names).
    runBlocking {
      val process =
        connectedDevice.jdwpProcessTracker.processesFlow.mapNotNull { processes -> processes.firstOrNull { it.pid == pid } }.first()
      process.propertiesFlow.first { !it.vmIdentifier.isEmpty }
    }
    return deviceState
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
