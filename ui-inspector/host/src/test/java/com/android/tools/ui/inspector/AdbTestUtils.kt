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

import com.android.adblib.AdbDeviceServices
import com.android.adblib.AdbDeviceSyncServices
import com.android.adblib.AdbInputChannel
import com.android.adblib.AdbOutputChannel
import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.DirectoryEntry
import com.android.adblib.DirectoryEntryV2
import com.android.adblib.FileStat
import com.android.adblib.FileStatV2
import com.android.adblib.RemoteFileMode
import com.android.adblib.SyncProgress
import com.android.adblib.testing.FakeAdbDeviceServices
import com.android.adblib.testing.FakeAdbSession
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.flow.Flow

/**
 * A custom [AdbSession] for testing that allows overriding [deviceServices].
 *
 * This is needed because [FakeAdbSession] does not allow overriding [deviceServices] directly with a custom implementation.
 */
class TestAdbSession(val delegate: FakeAdbSession, override val deviceServices: AdbDeviceServices) : AdbSession by delegate

/**
 * A custom [AdbDeviceServices] for testing that provides a fake implementation of [sync].
 *
 * This is needed because the official [FakeAdbDeviceServices] in `adblib` does not implement [sync].
 *
 * It also records calls to `syncSend` for verification in tests.
 */
class TestAdbDeviceServices(val delegate: FakeAdbDeviceServices) : AdbDeviceServices by delegate {
  data class SyncSendParams(val remoteFilePath: String, val remoteFileMode: RemoteFileMode)

  val recordedSyncSends = mutableListOf<SyncSendParams>()

  override suspend fun sync(device: DeviceSelector, readAheadBufferSize: Int, writeBackBufferSize: Int): AdbDeviceSyncServices {
    return object : AdbDeviceSyncServices {
      override suspend fun send(
        sourceChannel: AdbInputChannel,
        remoteFilePath: String,
        remoteFileMode: RemoteFileMode,
        remoteFileTime: FileTime?,
        progress: SyncProgress?,
        bufferSize: Int,
      ) {
        recordedSyncSends.add(SyncSendParams(remoteFilePath, remoteFileMode))
      }

      override suspend fun shutdown() {}

      override suspend fun recv(remoteFilePath: String, destinationChannel: AdbOutputChannel, progress: SyncProgress?, bufferSize: Int) =
        TODO()

      override suspend fun stat(remoteFilePath: String): FileStat? = TODO()

      override fun list(remoteFilePath: String, options: AdbDeviceSyncServices.ListOptions): Flow<DirectoryEntry> = TODO()

      override suspend fun statV2(remoteFilePath: String, options: AdbDeviceSyncServices.StatV2Options): FileStatV2 = TODO()

      override fun listV2(remoteFilePath: String, options: AdbDeviceSyncServices.ListV2Options): Flow<DirectoryEntryV2> = TODO()

      override fun close() {}
    }
  }
}
