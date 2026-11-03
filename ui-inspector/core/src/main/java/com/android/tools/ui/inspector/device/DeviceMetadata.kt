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
import com.android.sdklib.AndroidVersion
import com.android.sdklib.AndroidVersionUtil

/** The preview SDK version of beta and canary builds, e.g. 3721 for 37.2 beta 1. adblib has no constant for it. */
private const val RO_BUILD_VERSION_PREVIEW_SDK = "ro.build.version.preview_sdk"

/** Matches the API levels Android Studio reads (`AndroidApiLevel.fromString`), e.g. "36" or "36.1". */
private val API_LEVEL_REGEX = Regex("""(\d+)(?:\.(\d+))?""")

/**
 * Shell command printing, one per line, the device properties [parseDeviceMetadata] reads: the CPU ABI, the API level, the API level with
 * its minor version, the codename and the preview SDK version. `getprop` prints an empty line for a property the device doesn't have.
 */
internal const val DEVICE_METADATA_SHELL_COMMAND =
  "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && " +
    "getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK} && " +
    "getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK_FULL} && " +
    "getprop ${DevicePropertyNames.RO_BUILD_VERSION_CODENAME} && " +
    "getprop $RO_BUILD_VERSION_PREVIEW_SDK"

/** What the host needs to know about the device: [abi] picks the agent binary, and [version] is checked against the inspectors' needs. */
internal data class DeviceMetadata(val abi: String, val version: AndroidVersion)

/**
 * Parses the output of [DEVICE_METADATA_SHELL_COMMAND]. The Android version is read like Android Studio reads it, with
 * [AndroidVersionUtil.androidVersionFromDeviceProperties]: the API level comes from `ro.build.version.sdk_full`, which has the minor
 * version, and from `ro.build.version.sdk` on devices that don't have it (before Android 16), and the API level of the new-style beta
 * builds comes from `ro.build.version.preview_sdk`, e.g. 37.2 from 3721.
 *
 * Unlike Android Studio, which then uses `AndroidVersion.DEFAULT` or fails, a malformed `ro.build.version.sdk_full` is ignored, so the API
 * level comes from `ro.build.version.sdk`, and an invalid codename is ignored. Also unlike Android Studio, `ro.build.version.sdk` must be
 * an API level even when `ro.build.version.sdk_full` is one.
 *
 * @throws IllegalStateException when the ABI is missing, or `ro.build.version.sdk` is missing or not an API level.
 */
internal fun parseDeviceMetadata(output: String): DeviceMetadata {
  val lines = output.lines().map { it.trim() }
  val abi = lines.getOrNull(0)
  if (abi.isNullOrEmpty()) {
    throw IllegalStateException("Failed to retrieve device CPU ABI.")
  }
  val apiLevel = lines.getOrNull(1)?.takeIf { it.isApiLevel() } ?: throw IllegalStateException("Failed to retrieve device SDK API level.")
  val fullApiLevel = lines.getOrNull(2)?.takeIf { it.isApiLevel() }
  val codename = lines.getOrNull(3)?.takeIf { AndroidVersion.PREVIEW_PATTERN_RELAXED.matcher(it).matches() }
  val previewSdk = lines.getOrNull(4)?.takeIf { it.isNotEmpty() }
  val properties = buildMap {
    put(DevicePropertyNames.RO_BUILD_VERSION_SDK, apiLevel)
    fullApiLevel?.let { put(DevicePropertyNames.RO_BUILD_VERSION_SDK_FULL, it) }
    codename?.let { put(DevicePropertyNames.RO_BUILD_VERSION_CODENAME, it) }
    previewSdk?.let { put(RO_BUILD_VERSION_PREVIEW_SDK, it) }
  }
  val version =
    AndroidVersionUtil.androidVersionFromDeviceProperties(properties)
      ?: throw IllegalStateException("Failed to retrieve device SDK API level.")
  return DeviceMetadata(abi, version)
}

/** Whether this is an API level that `AndroidApiLevel.fromString` reads without failing: a major and an optional minor version, as Ints. */
private fun String.isApiLevel(): Boolean {
  val match = API_LEVEL_REGEX.matchEntire(this) ?: return false
  val (major, minor) = match.destructured
  return major.toIntOrNull() != null && (minor.isEmpty() || minor.toIntOrNull() != null)
}
