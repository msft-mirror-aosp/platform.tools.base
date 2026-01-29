/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.adblib.impl

import com.android.adblib.AppProcessEntry
import com.android.adblib.InstructionSet
import com.android.server.adb.protos.AppProcessesProto
import java.nio.ByteBuffer

/** Parser of list of process entries as sent by the `track-app` service. */
internal class AppProcessEntryListParser {

  fun parse(buffer: ByteBuffer): List<AppProcessEntry> {
    val result = mutableListOf<AppProcessEntry>()

    // Special case of <no devices>
    if (!buffer.hasRemaining()) {
      return result
    }

    val appProcesses = AppProcessesProto.AppProcesses.parseFrom(buffer)
    appProcesses.processList.forEach {
      result.add(
        AppProcessEntry(
          pid = it.pid.toInt(),
          debuggable = it.debuggable,
          profileable = it.profileable,
          instructionSet = InstructionSet.fromString(it.architecture),
          //
          // Note: The fields below are set only when the `app_info` feature is
          //       supported by the device.
          // See https://android-review.googlesource.com/q/topic:%22app_info%22
          //
          userId = if (it.hasUserId()) it.userId else null,
          processName = if (it.hasProcessName()) it.processName else null,
          // Unfortunately, the "package names" field of the protobuf definition
          // is "repeated", implying **not** "optional", so the list will always be set
          // to a value (empty list) even if `app_info` is not supported by the device.
          // Since we want to be consistent wrt to `null` values for fields not
          // supported when `app_info` is not supported, we use `userId` as
          // a surrogate, as it seems pretty clear that `userId` will always be
          // set when `packageNames` is set.
          packageNames = if (it.hasUserId()) it.packageNamesList.toList() else null,
          waitingForDebugger = if (it.hasWaitingForDebugger()) it.waitingForDebugger else null,
          uid = if (it.hasUid()) it.uid else null,
        )
      )
    }

    return result
  }
}
