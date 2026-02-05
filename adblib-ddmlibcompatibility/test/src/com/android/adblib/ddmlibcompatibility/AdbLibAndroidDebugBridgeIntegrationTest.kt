/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.adblib.ddmlibcompatibility

import com.android.adblib.ddmlibcompatibility.testutils.InitAndroidDebugBridgeRule
import com.android.adblib.ddmlibcompatibility.testutils.UseAdbLibAndroidDebugBridgeRule
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice.PROP_DEVICE_DENSITY
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * Tests in this class rely on a lot of real (i.e. not fake) classes such as `AdbServerControllerImpl` and `AdbLibIDeviceManager` and make
 * downstream calls to static methods like `AndroidDebugBridge.deviceConnected`. We also call `AndroidDebugBridge.preInit` method to replace
 * the default AndroidDebugBridge implementation, and so extra care should be given to restore AndroidDebugBridge to a state usable by other
 * tests.
 */
class AdbLibAndroidDebugBridgeIntegrationTest {

  private val fakeAdbRule = FakeAdbServerProviderRule()

  private val useAdbLibAndroidDebugBridgeRule = UseAdbLibAndroidDebugBridgeRule { fakeAdbRule.adbSession }

  private val initAndroidDebugBridgeRule = InitAndroidDebugBridgeRule { fakeAdbRule.fakeAdb.port }

  @get:Rule val ruleChain = RuleChain.outerRule(fakeAdbRule).around(useAdbLibAndroidDebugBridgeRule).around(initAndroidDebugBridgeRule)!!

  @Test
  fun createBridge() = runBlocking {
    // Act: create bridge
    val bridgeInstance = AndroidDebugBridge.createBridge(10, TimeUnit.SECONDS) ?: error("Bridge was null")

    val fakeDevice =
      fakeAdbRule.fakeAdb.fakeAdbServer
        .connectDevice(
          "device1",
          "test1",
          "test2",
          "model",
          AndroidApiLevel(30),
          "x86_64",
          mapOf(Pair(PROP_DEVICE_DENSITY, "120")),
          DeviceState.HostConnectionType.USB,
        )
        .get()
    fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE

    yieldUntil { bridgeInstance.devices.isNotEmpty() }

    // Assert
    assertEquals(bridgeInstance, AndroidDebugBridge.getBridge())
    assertEquals("device1", bridgeInstance.devices[0].serialNumber)

    // Act: disconnect bridge
    AndroidDebugBridge.disconnectBridge(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
    yieldUntil { bridgeInstance.devices.isEmpty() }

    assertNull(AndroidDebugBridge.getBridge())

    // Act: recreate bridge
    val recreatedBridgeInstance = AndroidDebugBridge.createBridge(10, TimeUnit.SECONDS) ?: error("Bridge was null")
    yieldUntil { recreatedBridgeInstance.devices.isNotEmpty() }

    // Assert
    assertNotEquals(bridgeInstance, recreatedBridgeInstance)
    assertEquals(recreatedBridgeInstance, AndroidDebugBridge.getBridge())
    assertEquals("device1", recreatedBridgeInstance.devices[0].serialNumber)

    // Act: terminate bridge
    AndroidDebugBridge.terminate()
    assertNull(AndroidDebugBridge.getBridge())
  }
}
