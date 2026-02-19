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
package com.android.adblib

import kotlinx.coroutines.flow.StateFlow

data class ConnectionStatus(
  /** `true` if a connection to the ADB server is established. */
  val isConnected: Boolean,

  /**
   * A value that changes each time the connection status changes. This is useful for distinguishing a new connection from a previous one
   * after, for example, a disconnection and reconnection.
   */
  val connectionId: Int,
)

/** Tracks changes to the status of the connection to the ADB server. */
@IsThreadSafe
interface ConnectionStatusTracker {

  /** The [session][AdbSession] this [ConnectionStatusTracker] belongs to */
  val session: AdbSession

  /**
   * The [StateFlow] of current connection status. The flow remains active as long as the [session] is active. Once the session is closed,
   * the flow value changes to a disconnected status and never changes again.
   */
  val connectionStatus: StateFlow<ConnectionStatus>
}
