/*
 * Copyright (C) 2024 The Android Open Source Project
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
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.tools.testutils.waitForOnlineConnectedDevice
import com.android.ddmlib.AndroidDebugBridge
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class ConnectedDeviceExtensionsTest {

    private val fakeAdbRule = FakeAdbServerProviderRule()
    private val useAdbLibAndroidDebugBridgeRule =
        UseAdbLibAndroidDebugBridgeRule { fakeAdbRule.adbSession }
    private val initAndroidDebugBridgeRule =
        InitAndroidDebugBridgeRule { fakeAdbRule.fakeAdb.port }
    @get:Rule
    val ruleChain = RuleChain.outerRule(fakeAdbRule)
        .around(useAdbLibAndroidDebugBridgeRule)
        .around(initAndroidDebugBridgeRule)!!

    private val fakeAdb get() = fakeAdbRule.fakeAdb

    @Before
    fun setUp() {
        AndroidDebugBridge.createBridge() ?: error("Couldn't create a bridge")
    }

    @Test
    fun testAssociatedIDevice(): Unit = runBlocking {
        // Setup
        val serialNumber = "serial123"
        val device = createConnectedDevice(serialNumber)

        // Act
        // There is a slight delay between when a [ConnectedDevice] starts to be tracked by `adblib`
        // and when `adblib-ddmlibcompatibility` layer exposes it in `AndroidDebugBridge.devices`.
        yieldUntil {
            device.associatedIDevice() != null
        }
        assertEquals("serial123", device.associatedIDevice()?.serialNumber)

        // Act: disconnect device and assert `associatedIDevice` starts returning `null`
        fakeAdb.disconnectDevice(serialNumber)
        yieldUntil {
            device.associatedIDevice() == null
        }
        assertEquals(com.android.adblib.DeviceState.DISCONNECTED, device.deviceInfoFlow.value.deviceState)
    }

    private suspend fun createConnectedDevice(
        serialNumber: String,
        sdk: AndroidApiLevel = AndroidApiLevel(29)
    ): ConnectedDevice {
        val fakeDevice =
            fakeAdb.connectDevice(
                serialNumber,
                "Google",
                "Pixel",
                "versionX",
                sdk,
                DeviceState.HostConnectionType.USB
            )
        fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
        return fakeAdbRule.adbSession.waitForOnlineConnectedDevice(serialNumber)
    }
}
