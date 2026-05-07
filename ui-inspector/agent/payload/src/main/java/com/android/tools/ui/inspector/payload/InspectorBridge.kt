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

package com.android.tools.ui.inspector.payload

import androidx.inspection.Connection
import androidx.inspection.Inspector
import com.android.tools.ui.inspector.payload.appinspection.DelegatingConnection
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor
import com.android.tools.ui.inspector.payload.appinspection.handleCommandSuspend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Bridges communication between the server and a specific [Inspector], enabling persistence across host reconnections and ensuring
 * sequential command processing.
 */
internal class InspectorBridge(
  private val inspector: Inspector,
  private val connection: DelegatingConnection,
  private val scope: CoroutineScope,
  private val primaryExecutor: HandlerThreadExecutor,
) {
  private class CommandEnvelope(val command: ByteArray, val reply: CompletableDeferred<ByteArray>)

  private val channel = Channel<CommandEnvelope>(Channel.UNLIMITED)

  init {
    scope.launch {
      // Command handler coroutine. Handles commands sequentially.
      for (envelope in channel) {
        try {
          val response = inspector.handleCommandSuspend(envelope.command)
          envelope.reply.complete(response)
        } catch (e: Exception) {
          envelope.reply.completeExceptionally(e)
        }
      }
    }
  }

  /**
   * Updates the connection used by the inspector to send events. Since we re-use the inspectors across different connections, this is
   * required when a host reconnects and a new socket session is established.
   */
  internal fun updateConnection(newConnection: Connection?) {
    connection.activeConnection = newConnection
  }

  /** Sends a command to the inspector and waits for the response. */
  internal suspend fun sendCommand(command: ByteArray): ByteArray {
    val reply = CompletableDeferred<ByteArray>()
    channel.send(CommandEnvelope(command, reply))
    return reply.await()
  }

  /** Disposes the bridge and the underlying inspector. */
  internal fun dispose() {
    channel.close()
    inspector.onDispose()
    primaryExecutor.quitSafely()
  }
}
