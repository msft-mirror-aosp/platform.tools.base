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
package com.android.adblib.ddmlibcompatibility.testutils

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.fakeadbserver.DeviceState
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

suspend fun DeviceState.waitForOnlineDevice(
    timeout: Duration = Duration.ofSeconds(5)
): IDevice {
    val bridge =
        AndroidDebugBridge.getBridge()
            ?: throw AssertionError("No bridge found. `AndroidDebugBridge.createBridge()` should have been called.")

    return withTimeout(timeout.toMillis()) {
        while (true) {
            val device = bridge.devices.find {
                it.isOnline && it.serialNumber == deviceId
            }

            if (device != null) {
                // When the device is found, return it from this withTimeout block
                return@withTimeout device
            }

            delay(20)
        }

        @Suppress("UNREACHABLE_CODE")
        error("This point should not be reached")
    }
}
