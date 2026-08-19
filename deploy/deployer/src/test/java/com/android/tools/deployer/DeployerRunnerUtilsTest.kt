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
package com.android.tools.deployer

import com.android.adblib.AdbServerConfiguration
import com.android.adblib.serialNumber
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import com.android.utils.StdLogger
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeployerRunnerUtilsTest {

  @get:Rule val fakeAdbRule = FakeAdbServerProviderRule()

  private fun connectDevice(serialNumber: String): DeviceState {
    val deviceState =
      fakeAdbRule.fakeAdb.fakeAdbServer
        .connectDevice(
          deviceId = serialNumber,
          manufacturer = "Google",
          deviceModel = "Pixel",
          release = "30",
          sdk = AndroidApiLevel(30),
          cpuAbi = "x86_64",
          properties = emptyMap(),
          hostConnectionType = DeviceState.HostConnectionType.USB,
          maxSpeedMbps = 0,
          negotiatedSpeedMbps = 0,
        )
        .get()
    deviceState.deviceStatus = DeviceState.DeviceStatus.ONLINE
    return deviceState
  }

  @Test
  fun testWaitForConnectedDevices_emptyDeviceSerials_returnsWhenFirstDeviceConnected() = runBlocking {
    val session = fakeAdbRule.adbSession

    connectDevice("device_1")

    val devices = waitForConnectedDevices(session, emptyList(), 5000)
    assertNotNull(devices)
    assertEquals(1, devices.size)
    assertEquals("device_1", devices.first().serialNumber)
  }

  @Test
  fun testWaitForConnectedDevices_emptyDeviceSerials_returnsOnlyOneDeviceWhenMultipleConnected() = runBlocking {
    val session = fakeAdbRule.adbSession

    connectDevice("device_1")
    connectDevice("device_2")

    val devices = waitForConnectedDevices(session, emptyList(), 5000)
    assertNotNull(devices)
    assertEquals(1, devices.size)
  }

  @Test
  fun testWaitForConnectedDevices_matchingDeviceSerials_returnsWhenAllDevicesConnected() = runBlocking {
    val session = fakeAdbRule.adbSession

    connectDevice("device_1")
    connectDevice("device_2")

    val devices = waitForConnectedDevices(session, listOf("device_1", "device_2"), 5000)
    assertNotNull(devices)
    assertEquals(2, devices.size)
  }

  @Test
  fun testWaitForConnectedDevices_extraDevicesConnected_returnsOnlyMatchingDevices() = runBlocking {
    val session = fakeAdbRule.adbSession

    connectDevice("device_1")
    connectDevice("device_2")
    connectDevice("device_3")

    val devices = waitForConnectedDevices(session, listOf("device_1", "device_2"), 5000)
    assertNotNull(devices)
    assertEquals(2, devices.size)
    assertEquals(setOf("device_1", "device_2"), devices.map { it.serialNumber }.toSet())
  }

  @Test
  fun testWaitForConnectedDevices_missingDeviceSerial_timesOut() = runBlocking {
    val session = fakeAdbRule.adbSession

    connectDevice("device_1")

    val devices = waitForConnectedDevices(session, listOf("device_1", "device_2"), 100)
    assertTrue(devices.isEmpty())
  }

  @Test
  fun testCreateAdbServerControllerAndSession_connectsToAdbServer() = runBlocking {
    connectDevice("device_1")

    val fakeAdbPath = Paths.get("/tmp/fake_adb").toAbsolutePath()
    val config =
      AdbServerConfiguration(
        adbPath = fakeAdbPath,
        serverPort = fakeAdbRule.fakeAdb.port,
        isUserManaged = false,
        isUnitTest = true,
        envVars = emptyMap(),
      )
    createAndStartAdbServerController(config, StdLogger.Level.VERBOSE).use { controller ->
      createAdbSession(controller, StdLogger.Level.VERBOSE).use { session ->
        val devices = waitForConnectedDevices(session, emptyList(), 5000)
        assertEquals(1, devices.size)
      }
    }
  }

  @Test
  fun testCreateAdbServerController_withNullExecutablePath_connectsToAdbServer() = runBlocking {
    connectDevice("device_1")

    createAndStartAdbServerControllerBlocking(
        adbExecutablePath = null,
        adbServerPort = fakeAdbRule.fakeAdb.port,
        logLevel = StdLogger.Level.VERBOSE,
      )
      .use { controller ->
        createAdbSession(controller, StdLogger.Level.VERBOSE).use { session ->
          val devices = waitForConnectedDevices(session, emptyList(), 5000)
          assertEquals(1, devices.size)
        }
      }
  }
}
