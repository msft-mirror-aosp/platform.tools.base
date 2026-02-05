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

/**
 * Implementation of the `QUIT` protocol of the `SYNC` command
 *
 * See
 * [SYNC.TXT](https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT)
 */
internal class SyncQuitHandler(private val connection: SyncConnection) {

  private val syncRequestId: String = "QUIT"

  suspend fun quit() {
    // See
    // https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/client/file_sync_client.cpp;l=256
    // Send "QUIT" then read channel until EOF
    connection.startSyncRequest(syncRequestId, "")
    connection.readForOrderlyShutdown()
  }
}
