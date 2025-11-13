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
package com.android.adblib.tools.aiglasses.cli

import com.android.adblib.connectedDevicesTracker
import com.android.adblib.tools.aiglasses.AiGlassesPairing
import com.android.adblib.tools.createStandaloneSession
import com.android.adblib.waitForDevice
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A simple tool for pairing AI glasses to a phone; also a lightweight way of testing the
 * functionality of [AiGlassesPairing].
 */
object AiGlassesCli {
  @Throws(Exception::class)
  @JvmStatic
  fun main(args: Array<String>) {
    if (args.size < 2) {
      println("Usage: [phone serial] [glasses serial]")
      return
    }

    val phoneSerial = args[0]
    val glassesSerial = args[1]

    val session = createStandaloneSession()
    runBlocking { AiGlassesPairing(session).pair(phoneSerial, glassesSerial) }
  }

  suspend fun AiGlassesPairing.pair(phoneSerial: String, glassesSerial: String) {
    val phone =
      withTimeoutOrNull(5.seconds) { session.connectedDevicesTracker.waitForDevice(phoneSerial) }
        ?: run {
          println("Device $phoneSerial not connected")
          return
        }

    val glasses =
      withTimeoutOrNull(5.seconds) { session.connectedDevicesTracker.waitForDevice(glassesSerial) }
        ?: run {
          println("Device $glassesSerial not connected")
          return
        }

    val pairedBluetoothDeviceCount = glasses.getPairedBluetoothDeviceCount()
    if (pairedBluetoothDeviceCount != null && pairedBluetoothDeviceCount > 0) {
      println("Glasses already paired; factory reset (wipe data) to pair a new device")
      return
    }

    if (!phone.hasGlassesCompanionApp()) {
      println("Glasses companion app not detected")
      return
    }

    val glassesBluetoothAddress =
      glasses.getBluetoothAddress()
        ?: run {
          println("Failed to retrieve glasses bluetooth address")
          return
        }

    phone.pairToGlasses(glassesBluetoothAddress, true).collect { pairingState ->
      println("Pairing state: $pairingState")
    }
  }
}
