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

package com.android.tools.ui.inspector

import com.android.adblib.AdbHostServices
import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.shellAsText

/**
 * Discovers and prints the serial number of all currently connected Android devices.
 *
 * @param adbSession The [AdbSession] to communicate with the local ADB server.
 */
suspend fun doListDevices(adbSession: AdbSession) {
  val devices = adbSession.hostServices.devices(AdbHostServices.DeviceInfoFormat.SHORT_FORMAT)
  if (devices.isEmpty()) {
    println("No devices connected.")
  } else {
    devices.forEach { println(it.serialNumber) }
  }
}

/**
 * Scans running processes on the device and prints the package names of all debuggable applications.
 *
 * @param adbSession The [AdbSession] to communicate with the local ADB server.
 * @param serial The serial number of the target device.
 */
suspend fun doListPackages(adbSession: AdbSession, serial: String) {
  val selector = DeviceSelector.fromSerialNumber(serial)
  // List all active processes on the device. The last column contains the process/package name.
  val psOutput = adbSession.deviceServices.shellAsText(selector, "ps -A").stdout.trim()
  if (psOutput.isEmpty()) {
    println("No processes found.")
    return
  }

  // Parse package names
  val candidatePackages =
    psOutput
      .split("\n")
      .drop(1)
      .map { line -> line.split("\\s+".toRegex()).last() }
      .filter { it.contains(".") && !it.startsWith("/") }
      .distinct()

  val debuggablePackages = mutableListOf<String>()
  candidatePackages.forEach { pkg ->
    try {
      // An application is debuggable if run-as succeeds for its package name.
      val runAsResult = adbSession.deviceServices.shellAsText(selector, "run-as $pkg id")
      if (runAsResult.exitCode == 0) {
        debuggablePackages.add(pkg)
      }
    } catch (ignored: Exception) {}
  }

  if (debuggablePackages.isEmpty()) {
    println("No debuggable packages found.")
  } else {
    debuggablePackages.sorted().forEach { println(it) }
  }
}
