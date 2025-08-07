/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.adblib.AdbDeviceSyncServices.ListOptions
import com.android.adblib.DirectoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Implementation of the `LIST` protocol of the `SYNC` command
 *
 * See [SYNC.TXT](https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT)
 */
internal class SyncListHandler(private val connection: SyncConnection) {

    private val syncRequestId: String = "LIST"

    /**
     * See [SYNC.TXT](https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT)
     *
     * ```
     * LIST:
     * Lists files in the directory specified by the remote filename. The server will
     * respond with zero or more directory entries or "dents".
     *
     * The directory entries will be returned in the following form
     * 1. A four-byte sync response id "DENT"
     * 2. A four-byte integer representing file mode.
     * 3. A four-byte integer representing file size.
     * 4. A four-byte integer representing last modified time.
     * 5. A four-byte integer representing file name length.
     * 6. length number of bytes containing an utf-8 string representing the file
     *    name.
     *
     * When a sync response "DONE" is received the listing is done.
     * ```
     */
    fun list(remoteFilePath: String, options: ListOptions): Flow<DirectoryEntry> = flow {
        // Note: adb daemon implementation lives at:
        // https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/daemon/file_sync_service.cpp;l=186
        connection.startSyncRequest(syncRequestId, remoteFilePath)
        while (true) {
            val entry = connection.readDirectoryEntry(syncRequestId) ?: break
            when {
                options.skipDotEntries && (entry.fileName == "." || entry.fileName == "..") -> {
                    // Skip "." and ".." according to option setting
                }

                else -> {
                    emit(entry)
                }
            }
        }
    }
}
