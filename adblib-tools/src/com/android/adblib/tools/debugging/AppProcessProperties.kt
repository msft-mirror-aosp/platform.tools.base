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
package com.android.adblib.tools.debugging

import com.android.adblib.AdbDeviceServices
import com.android.adblib.AdbFeatures
import com.android.adblib.AppProcessEntry
import com.android.adblib.InstructionSet

/**
 * List of known properties corresponding to a [AppProcess] instance.
 *
 * @see AppProcessEntry
 */
data class AppProcessProperties(
  /**
   * The process ID.
   *
   * Note: This is the only property that is guaranteed to be valid, all other properties are instances of [OptionalValue].
   */
  val pid: Int,

  /** Whether a JDWP debugger can attach to the process (see [AdbDeviceServices.jdwp]) */
  val debuggable: OptionalValue<Boolean> = OptionalValue.empty(),

  /** Whether profiling tools can profile the process */
  val profileable: OptionalValue<Boolean> = OptionalValue.empty(),

  /**
   * The Android ABI the process is executing with, as defined at [abis](https://developer.android.com/ndk/guides/abis). Examples:
   * "arm64-v8a", "x86_64"
   */
  val instructionSet: OptionalValue<InstructionSet> = OptionalValue.empty(),

  /**
   * The Android User ID
   *
   * Note: Only ever set if [AdbFeatures.APP_INFO] is supported by the device (API 36+)
   */
  val userId: OptionalValue<Long> = OptionalValue.empty(),

  /**
   * The process name
   *
   * Note: only ever set if [AdbFeatures.APP_INFO] is supported by the device (API 36+)
   */
  val processName: OptionalValue<String> = OptionalValue.empty(),

  /**
   * The list of packages this process hosts, typically only one for "regular" Android Applications
   *
   * Note: only ever set if [AdbFeatures.APP_INFO] is supported by the device (API 36+)
   */
  val packageNames: OptionalValue<List<String>> = OptionalValue.empty(),

  /**
   * Whether the JDWP process is waiting for a JDWP debugger to attach
   *
   * Note: Only ever set if [AdbFeatures.APP_INFO] is supported by the device (API 36+)
   */
  val waitingForDebugger: OptionalValue<Boolean> = OptionalValue.empty(),

  /**
   * The Android `uid`, i.e. the results of calling `getuid()` in the process. The `uid` is a unique identifier for a given
   * [user ID][userId] and [package name][packageNames], i.e. 2 packages with the same name but different [userId] values will have
   * different [uid] values.
   *
   * Note: Only ever set if [AdbFeatures.APP_INFO] is supported by the device (API 36+)
   */
  val uid: OptionalValue<Long> = OptionalValue.empty(),
)
