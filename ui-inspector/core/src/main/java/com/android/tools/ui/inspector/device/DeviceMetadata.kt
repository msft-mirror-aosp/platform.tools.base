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

package com.android.tools.ui.inspector.device

import com.android.adblib.DevicePropertyNames

/**
 * The codename of release builds in `ro.build.version.codename`, the value of `android.os.Build.VERSION.CODENAME` on them. Preview builds
 * report their own codename instead, e.g. "UpsideDownCake". Android Studio uses the same constant, `SdkConstants.CODENAME_RELEASE`.
 */
private const val RELEASE_CODENAME = "REL"

/** Matches `ro.build.version.sdk_full`, e.g. "36.1". */
private val FULL_API_LEVEL_REGEX = Regex("""(\d+)(?:\.(\d+))?""")

/**
 * Shell command printing, one per line, the device properties [parseDeviceMetadata] reads: the CPU ABI, the API level, the API level with
 * its minor version, and the codename. `getprop` prints an empty line for a property the device doesn't have.
 */
internal const val DEVICE_METADATA_SHELL_COMMAND =
  "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && " +
    "getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK} && " +
    "getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK_FULL} && " +
    "getprop ${DevicePropertyNames.RO_BUILD_VERSION_CODENAME}"

/** What the host needs to know about the device: [abi] picks the agent binary, and [version] is checked against the inspectors' needs. */
internal data class DeviceMetadata(val abi: String, val version: AndroidDeviceVersion)

/**
 * The Android version a device runs, e.g. API level 36.1 for Android 16 QPR2.
 *
 * @param apiLevel The API level.
 * @param minorApiLevel The minor version of the API level, 0 on devices that don't report one.
 * @param codename The codename of a preview build, e.g. "CinnamonBun". Null on release builds.
 */
internal data class AndroidDeviceVersion(val apiLevel: Int, val minorApiLevel: Int, val codename: String?)

/**
 * Parses the output of [DEVICE_METADATA_SHELL_COMMAND]. Like Android Studio, the API level is read from `ro.build.version.sdk_full`, which
 * has the minor version, and from `ro.build.version.sdk` on devices that don't have it (before Android 16).
 *
 * Unlike Android Studio, `ro.build.version.preview_sdk` is not read: Android Studio derives the API level of the new-style beta builds from
 * it, e.g. 37.2 from 3721.
 *
 * @throws IllegalStateException when the ABI or the API level is missing, or the API level is not a number.
 */
internal fun parseDeviceMetadata(output: String): DeviceMetadata {
  val lines = output.lines().map { it.trim() }
  val abi = lines.getOrNull(0)
  if (abi.isNullOrEmpty()) {
    throw IllegalStateException("Failed to retrieve device CPU ABI.")
  }
  val apiLevel = lines.getOrNull(1)?.toIntOrNull() ?: throw IllegalStateException("Failed to retrieve device SDK API level.")
  val codename = lines.getOrNull(3)?.takeIf { it.isNotEmpty() && it != RELEASE_CODENAME }
  val fullApiLevel =
    lines
      .getOrNull(2)
      ?.let { FULL_API_LEVEL_REGEX.matchEntire(it) }
      ?.let { match ->
        match.groupValues[1].toIntOrNull()?.let { major -> major to (match.groupValues[2].toIntOrNull() ?: 0) }
      }
  // When ro.build.version.sdk_full is missing (before Android 16) or malformed, use ro.build.version.sdk, without minor version.
  val (major, minor) = fullApiLevel ?: (apiLevel to 0)
  return DeviceMetadata(abi, AndroidDeviceVersion(apiLevel = major, minorApiLevel = minor, codename = codename))
}
