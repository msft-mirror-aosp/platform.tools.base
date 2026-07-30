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
package com.android.tools.deployer.common

import com.android.adblib.AdbSession
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.ddmlibcompatibility.testutils.InitAndroidDebugBridgeRule
import com.android.adblib.ddmlibcompatibility.testutils.UseAdbLibAndroidDebugBridgeRule
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.testingutils.TestingAdbSessionHost
import com.android.adblib.waitForDevice
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import com.android.sdklib.AndroidVersion
import com.android.tools.deployer.devices.DeviceId
import com.android.tools.deployer.rules.ApiLevel
import java.util.Optional
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(ApiLevel::class)
class DeviceHolderMigrationTest {

  @JvmField @ApiLevel.Init var deviceId: DeviceId? = null

  val fakeAdbRule = FakeAdbServerProviderRule()

  private val useAdbLibAndroidDebugBridgeRule = UseAdbLibAndroidDebugBridgeRule { fakeAdbRule.adbSession }

  private val initAndroidDebugBridgeRule = InitAndroidDebugBridgeRule(alsoCreateBridge = true) { fakeAdbRule.fakeAdb.port }

  @get:Rule
  val ruleChain: RuleChain = RuleChain.outerRule(fakeAdbRule).around(useAdbLibAndroidDebugBridgeRule).around(initAndroidDebugBridgeRule)

  @Test
  fun testVersionConsistency() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolderLegacy = createDeviceHolder(iDevice, useConnectedDevice = false)
    val deviceHolderNew = createDeviceHolder(iDevice, useConnectedDevice = true)

    // Act/Assert: Both should return the same version
    Assert.assertEquals(deviceHolderLegacy.version, deviceHolderNew.version)
    Assert.assertEquals(deviceId!!.api(), deviceHolderNew.version.apiLevel)
  }

  @Test
  fun testVersionFailureReturnsDefault() = runBlocking {
    val iDevice = connectAndGetDevice()
    val deviceHolder = createDeviceHolder(iDevice, useConnectedDevice = true)

    // Disconnect the device to cause failures
    fakeAdbRule.fakeAdb.disconnectDevice(iDevice.serialNumber)

    // Act / Assert
    Assert.assertEquals(AndroidVersion.DEFAULT.apiLevel, deviceHolder.version.apiLevel)
  }

  private suspend fun connectAndGetDevice(serialNumber: String = "serial_123"): IDevice {
    connectDevice(serialNumber)
    val bridge = AndroidDebugBridge.getBridge() ?: throw IllegalStateException("Bridge not initialized")
    yieldUntil { bridge.devices.any { it.serialNumber == serialNumber } }
    return bridge.devices.first { it.serialNumber == serialNumber }
  }

  private fun connectDevice(serialNumber: String): DeviceState {
    val deviceId = this.deviceId ?: throw IllegalStateException("deviceId not initialized")
    val deviceState =
      fakeAdbRule.fakeAdb.connectDevice(
        deviceId = serialNumber,
        manufacturer = "Google",
        deviceModel = "Pixel",
        release = deviceId.api().toString(),
        sdk = AndroidApiLevel(deviceId.api()),
        hostConnectionType = DeviceState.HostConnectionType.USB,
        maxSpeedMbps = 0,
        negotiatedSpeedMbps = 0,
      )
    return deviceState
  }

  private suspend fun createDeviceHolder(iDevice: IDevice, useConnectedDevice: Boolean): DeviceHolder {
    return if (useConnectedDevice) {
      val session = fakeAdbRule.adbSession
      val connectedDevice = session.connectedDevicesTracker.waitForDevice(iDevice.serialNumber)
      enableUseConnectedDevice(session)
      DeviceHolder(iDevice, Optional.of(connectedDevice), session)
    } else {
      DeviceHolder(iDevice, null)
    }
  }

  private fun enableUseConnectedDevice(session: AdbSession) {
    (session.host as TestingAdbSessionHost).setPropertyValue(DeployerProperties.USE_CONNECTED_DEVICE, true)
  }
}
