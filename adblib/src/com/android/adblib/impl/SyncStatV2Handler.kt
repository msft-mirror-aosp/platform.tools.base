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

import com.android.adblib.FileStatV2
import kotlinx.coroutines.withContext

/**
 * Implementation of the `STA2` protocol of the `SYNC` command
 *
 * See
 * [SYNC.TXT](https://cs.android.com/android/platform/superproject/+/fbe41e9a47a57f0d20887ace0fc4d0022afd2f5f:packages/modules/adb/SYNC.TXT)
 */
internal class SyncStatV2Handler(private val connection: SyncConnection) {

  private val syncRequestId: String = "STA2"

  /** Execute a "STA2" sync request */
  suspend fun statV2(remoteFilePath: String): FileStatV2 {
    return withContext(connection.session.ioDispatcher) {
      connection.startSyncRequest(syncRequestId, remoteFilePath)
      connection.readFileStatV2(syncRequestId)
    }
  }
}
