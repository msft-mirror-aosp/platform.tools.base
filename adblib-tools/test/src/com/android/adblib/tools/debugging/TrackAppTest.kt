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
package com.android.adblib.tools.debugging

import com.android.adblib.testingutils.CoroutineTestUtils
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.tools.testutils.waitForOnlineConnectedDevice
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TrackAppTest {

  @JvmField @Rule val fakeAdbRule = FakeAdbServerProviderRule()

  private val fakeAdb
    get() = fakeAdbRule.fakeAdb

  private val hostServices
    get() = fakeAdbRule.adbSession.hostServices

  @Test
  fun testIsAppInfoSupported_returnsTrue_onApi36() =
    CoroutineTestUtils.runBlockingWithTimeout {
      // Prepare
      val deviceID = "1234"
      val fakeDevice = fakeAdb.connectDevice(deviceID, "test1", "test2", "model", AndroidApiLevel(36), DeviceState.HostConnectionType.USB)
      val connectedDevice = hostServices.session.waitForOnlineConnectedDevice(fakeDevice.deviceId)

      // Act / Assert
      assertTrue(connectedDevice.isAppInfoSupported())
    }

  @Test
  fun testIsAppInfoSupported_returnsFalse_onApi36_whenNotSupportedByCapabilities() =
    CoroutineTestUtils.runBlockingWithTimeout {
      // Prepare
      val deviceID = "1234"
      val fakeDevice = fakeAdb.connectDevice(deviceID, "test1", "test2", "model", AndroidApiLevel(36), DeviceState.HostConnectionType.USB)
      val defaultCapabilities = fakeDevice.deviceCapabilities
      fakeDevice.deviceCapabilities = defaultCapabilities?.copy(vmCapabilities = defaultCapabilities.vmCapabilities - "app_info")
      val connectedDevice = hostServices.session.waitForOnlineConnectedDevice(fakeDevice.deviceId)

      // Act / Assert
      assertFalse(connectedDevice.isAppInfoSupported())
    }

  @Test
  fun testIsAppInfoSupported_returnsFalse_onApi35() =
    CoroutineTestUtils.runBlockingWithTimeout {
      // Prepare
      val deviceID = "1234"
      val fakeDevice = fakeAdb.connectDevice(deviceID, "test1", "test2", "model", AndroidApiLevel(35), DeviceState.HostConnectionType.USB)
      val connectedDevice = hostServices.session.waitForOnlineConnectedDevice(fakeDevice.deviceId)

      // Act / Assert: Not supported, because `app_info` is not supported
      assertFalse(connectedDevice.isAppInfoSupported())
    }

  @Test
  fun testIsAppInfoSupported_returnsFalse_onApi30() =
    CoroutineTestUtils.runBlockingWithTimeout {
      // Prepare
      val deviceID = "1234"
      val fakeDevice = fakeAdb.connectDevice(deviceID, "test1", "test2", "model", AndroidApiLevel(30), DeviceState.HostConnectionType.USB)
      val connectedDevice = hostServices.session.waitForOnlineConnectedDevice(fakeDevice.deviceId)

      // Act / Assert: Not supported, because `track_app` is not supported
      assertFalse(connectedDevice.isAppInfoSupported())
    }
}
