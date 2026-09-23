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
import com.android.adblib.DeviceState
import com.android.adblib.shellAsText
import com.android.tools.ui.inspector.device.PackageUid
import com.android.tools.ui.inspector.device.TOP_ACTIVITY_SHELL_COMMAND
import com.android.tools.ui.inspector.device.UidResolver
import com.android.tools.ui.inspector.device.parseTopActivityProcesses

/**
 * Returns the serial number of the only online device.
 *
 * @throws IllegalStateException when there is not exactly one online device.
 */
suspend fun resolveSoleOnlineDevice(adbSession: AdbSession): String {
  val devices = adbSession.hostServices.devices(AdbHostServices.DeviceInfoFormat.SHORT_FORMAT)
  val onlineDevices = devices.filter { it.deviceState == DeviceState.ONLINE }
  return when {
    onlineDevices.size == 1 -> onlineDevices.single().serialNumber
    devices.isEmpty() -> throw IllegalStateException("No connected devices found.")
    onlineDevices.isEmpty() -> {
      val states = devices.sortedBy { it.serialNumber }.joinToString { "${it.serialNumber} (${it.deviceStateString})" }
      throw IllegalStateException("No online devices found. Connected devices: $states.")
    }
    else -> {
      val serials = onlineDevices.map { it.serialNumber }.sorted().joinToString()
      throw IllegalStateException("Multiple online devices found: $serials.")
    }
  }
}

/**
 * Returns the package name of the app hosting the top (foreground) activity on the device [serial].
 *
 * @throws IllegalStateException when the foreground app cannot be determined unambiguously.
 */
suspend fun resolveForegroundPackage(adbSession: AdbSession, serial: String): String {
  val selector = DeviceSelector.fromSerialNumber(serial)
  val uidResolver = UidResolver(adbSession, selector)
  val foregroundUids = queryForegroundUids(adbSession, selector, uidResolver)
  if (foregroundUids.isEmpty()) {
    // dumpsys reported no process hosting a top activity: nothing is in the foreground to resolve (locked or transitioning screen).
    throw foregroundAppResolutionException()
  }
  val packagesByUid = uidResolver.allPackageUids().groupBy { it.uid }
  // Distinct UIDs always resolve to distinct packages: several foreground UIDs means several apps are in the foreground (split screen).
  val packages = foregroundUids.map { uid -> singlePackageForUid(uid, packagesByUid) }
  return when {
    packages.size == 1 -> packages.single()
    else -> throw IllegalStateException("Multiple foreground apps found: ${packages.sorted().joinToString()}.")
  }
}

/** Returns the UID of each app currently hosting a top (foreground) activity: one normally, several in split screen. */
private suspend fun queryForegroundUids(adbSession: AdbSession, selector: DeviceSelector, uidResolver: UidResolver): List<Int> {
  val output = adbSession.deviceServices.shellAsText(selector, TOP_ACTIVITY_SHELL_COMMAND).stdout
  return parseTopActivityProcesses(output)
    .map { it.pid }
    .distinct()
    .map { pid ->
      // A PID from the top-activity snapshot that no longer resolves to a live process means the foreground is mid-transition; resolving
      // from the remaining processes could pick the wrong app.
      uidResolver.processUid(pid) ?: throw foregroundAppResolutionException()
    }
    .distinct()
}

/**
 * Returns the single package that owns [uid], looked up in [packagesByUid]. Throws when no package owns the UID, or when several share it
 * (legacy sharedUserId): a UID alone cannot tell which of them is in the foreground.
 */
private fun singlePackageForUid(uid: Int, packagesByUid: Map<Int, List<PackageUid>>): String {
  val candidates = packagesByUid[uid].orEmpty().map { it.packageName }.distinct().sorted()
  return when {
    candidates.isEmpty() -> throw foregroundAppResolutionException()
    candidates.size > 1 ->
      throw IllegalStateException("The foreground process UID matches multiple packages: ${candidates.joinToString()}.")
    else -> candidates.single()
  }
}

private fun foregroundAppResolutionException() =
  IllegalStateException("Could not determine the foreground app. Unlock the device and bring the target app to the foreground.")
