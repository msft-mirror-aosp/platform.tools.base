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
package com.android.sdklib.deviceprovisioner

import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceList
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.waitForDevice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class DeviceTypeTest {
  private val fakeSession = FakeAdbSession()

  @After
  fun tearDown() {
    runBlocking { fakeSession.closeAndJoin() }
  }

  @Test
  fun readFromFeatures_wear() = runBlockingWithTimeout {
    val serial = "emulator-5554"
    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(serial, DeviceState.ONLINE)), emptyList())
    fakeSession.deviceServices.configureShellCommand(
      DeviceSelector.fromSerialNumber(serial),
      command = "pm list features",
      stdout = "feature:android.hardware.type.watch\nfeature:android.foo.bar\n",
    )

    val device = fakeSession.connectedDevicesTracker.waitForDevice(serial)
    val deviceType = DeviceType.readFromFeatures(device)
    assertThat(deviceType).isEqualTo(DeviceType.WEAR)
  }

  @Test
  fun readFromFeatures_tv() = runBlockingWithTimeout {
    val serial = "emulator-5554"
    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(serial, DeviceState.ONLINE)), emptyList())
    fakeSession.deviceServices.configureShellCommand(
      DeviceSelector.fromSerialNumber(serial),
      command = "pm list features",
      stdout = "abcd\nefgh\nfeature:android.hardware.type.television\n",
    )

    val device = fakeSession.connectedDevicesTracker.waitForDevice(serial)
    val deviceType = DeviceType.readFromFeatures(device)
    assertThat(deviceType).isEqualTo(DeviceType.TV)
  }

  @Test
  fun readFromFeatures_automotive() = runBlockingWithTimeout {
    val serial = "emulator-5554"
    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(serial, DeviceState.ONLINE)), emptyList())
    fakeSession.deviceServices.configureShellCommand(
      DeviceSelector.fromSerialNumber(serial),
      command = "pm list features",
      stdout = "feature:android.hardware.type.automotive\n",
    )

    val device = fakeSession.connectedDevicesTracker.waitForDevice(serial)
    val deviceType = DeviceType.readFromFeatures(device)
    assertThat(deviceType).isEqualTo(DeviceType.AUTOMOTIVE)
  }

  @Test
  fun readFromFeatures_xr() = runBlockingWithTimeout {
    val serial = "emulator-5554"
    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(serial, DeviceState.ONLINE)), emptyList())
    fakeSession.deviceServices.configureShellCommand(
      DeviceSelector.fromSerialNumber(serial),
      command = "pm list features",
      stdout = "feature:android.software.xr.api.spatial\n",
    )

    val device = fakeSession.connectedDevicesTracker.waitForDevice(serial)
    val deviceType = DeviceType.readFromFeatures(device)
    assertThat(deviceType).isEqualTo(DeviceType.XR_HEADSET)
  }

  @Test
  fun readFromFeatures_aiGlasses() = runBlockingWithTimeout {
    val serial = "emulator-5554"
    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(serial, DeviceState.ONLINE)), emptyList())
    fakeSession.deviceServices.configureShellCommand(
      DeviceSelector.fromSerialNumber(serial),
      command = "pm list features",
      stdout = "feature:android.hardware.type.xr_peripheral\n",
    )

    val device = fakeSession.connectedDevicesTracker.waitForDevice(serial)
    val deviceType = DeviceType.readFromFeatures(device)
    assertThat(deviceType).isEqualTo(DeviceType.AI_GLASSES)
  }

  @Test
  fun readFromFeatures_unknown() = runBlockingWithTimeout {
    val serial = "emulator-5554"
    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(serial, DeviceState.ONLINE)), emptyList())
    fakeSession.deviceServices.configureShellCommand(
      DeviceSelector.fromSerialNumber(serial),
      command = "pm list features",
      stdout = "feature:android.hardware.camera\n",
    )

    val device = fakeSession.connectedDevicesTracker.waitForDevice(serial)
    val deviceType = DeviceType.readFromFeatures(device)
    assertThat(deviceType).isNull()
  }
}
