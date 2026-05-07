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
package com.android.adblib.ddmlibcompatibility.debugging

import com.android.adblib.ConnectedDevice
import com.android.adblib.ddmlibcompatibility.testutils.InitAndroidDebugBridgeRule
import com.android.adblib.ddmlibcompatibility.testutils.UseAdbLibAndroidDebugBridgeRule
import com.android.adblib.ddmlibcompatibility.testutils.createConnectedDevice
import com.android.adblib.deviceInfo
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.ddmlib.AndroidDebugBridge
import com.android.fakeadbserver.DeviceState
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class IDeviceExtensionsTest {

  private val fakeAdbRule = FakeAdbServerProviderRule()
  private val useAdbLibAndroidDebugBridgeRule = UseAdbLibAndroidDebugBridgeRule { fakeAdbRule.adbSession }
  private val initAndroidDebugBridgeRule = InitAndroidDebugBridgeRule { fakeAdbRule.fakeAdb.port }
  @get:Rule val ruleChain = RuleChain.outerRule(fakeAdbRule).around(useAdbLibAndroidDebugBridgeRule).around(initAndroidDebugBridgeRule)!!

  private val fakeAdb
    get() = fakeAdbRule.fakeAdb

  private lateinit var bridge: AndroidDebugBridge

  @Before
  fun setUp() {
    bridge = AndroidDebugBridge.createBridge() ?: error("Couldn't create a bridge")
  }

  @Test
  fun quickRetrieveAvdData_succeeds() = runBlockingWithTimeout {
    // Prepare
    val avdName = "myAvd-36"
    val avdPath = Path.of("/android/avds/myAvd-36.avd").toString()
    val emulatorConsole = fakeAdb.fakeAdbServer.connectEmulatorConsole(avdName = avdName, avdPath = avdPath).get()
    val consolePort = emulatorConsole.port
    val serialNumber = "emulator-$consolePort"

    val (connectedDevice, _) = fakeAdbRule.createConnectedDevice(serialNumber, DeviceState.DeviceStatus.ONLINE)
    val adblibIDeviceWrapper = createAdblibIDeviceWrapper(connectedDevice, bridge)

    // Act
    val avdData = adblibIDeviceWrapper.quickRetrieveAvdData()

    // Assert
    assertNotNull(avdData)
    assertEquals(avdName, avdData?.name)
    assertEquals(avdPath, avdData?.path)
  }

  @Test
  fun quickRetrieveAvdData_returnsNull_ifOffline() = runBlockingWithTimeout {
    // Prepare
    val (connectedDevice, _) = fakeAdbRule.createConnectedDevice("emulator-5554", DeviceState.DeviceStatus.OFFLINE)
    val adblibIDeviceWrapper = createAdblibIDeviceWrapper(connectedDevice, bridge)

    // Act
    val avdData = adblibIDeviceWrapper.quickRetrieveAvdData()

    // Assert
    assertNull(avdData)
  }

  @Test
  fun quickRetrieveAvdData_returnsNull_onFailure() = runBlockingWithTimeout {
    // Prepare
    val avdName = "myAvd-36"
    val avdPath = Path.of("/android/avds/myAvd-36.avd").toString()
    val emulatorConsole = fakeAdb.fakeAdbServer.connectEmulatorConsole(avdName = avdName, avdPath = avdPath).get()
    val consolePort = emulatorConsole.port
    val serialNumber = "emulator-$consolePort"
    // Require authentication to force a failure
    emulatorConsole.authRequired = true

    val (connectedDevice, _) = fakeAdbRule.createConnectedDevice(serialNumber, DeviceState.DeviceStatus.ONLINE)
    val adblibIDeviceWrapper = createAdblibIDeviceWrapper(connectedDevice, bridge)

    // Act
    val avdData = adblibIDeviceWrapper.quickRetrieveAvdData()

    // Assert
    assertNull(avdData)
  }

  @Test
  fun quickRetrieveAvdData_returnsCachedData_ifPreviouslyRetrievedAndOffline() = runBlockingWithTimeout {
    // Prepare
    val avdName = "myAvd-36"
    val avdPath = Path.of("/android/avds/myAvd-36.avd").toString()
    val emulatorConsole = fakeAdb.fakeAdbServer.connectEmulatorConsole(avdName = avdName, avdPath = avdPath).get()
    val consolePort = emulatorConsole.port
    val serialNumber = "emulator-$consolePort"

    val (connectedDevice, fakeDevice) = fakeAdbRule.createConnectedDevice(serialNumber, DeviceState.DeviceStatus.ONLINE)
    val adblibIDeviceWrapper = createAdblibIDeviceWrapper(connectedDevice, bridge)

    // First retrieval succeeds
    val avdData1 = adblibIDeviceWrapper.quickRetrieveAvdData()
    assertNotNull(avdData1)

    // Transition to offline
    fakeDevice.deviceStatus = DeviceState.DeviceStatus.OFFLINE
    // Wait for the state to be reflected in IDevice
    yieldUntil { !adblibIDeviceWrapper.isOnline }

    // Act: Second retrieval should return cached data even though the device is now offline
    val avdData2 = adblibIDeviceWrapper.quickRetrieveAvdData()

    // Assert
    assertNotNull(avdData2)
    assertEquals(avdName, avdData2?.name)
    assertEquals(avdPath, avdData2?.path)
  }

  private fun createAdblibIDeviceWrapper(connectedDevice: ConnectedDevice, bridge: AndroidDebugBridge): AdblibIDeviceWrapper {
    return AdblibIDeviceWrapper(connectedDevice, bridge, deviceState = { connectedDevice.deviceInfo.deviceState })
  }
}
