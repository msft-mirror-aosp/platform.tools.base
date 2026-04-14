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

import android.net.LocalSocket
import android.util.Log
import com.android.tools.ui.inspector.common.FramingProtocol

/** Handles a single connection from the host (the client) to the UI Inspector agent. */
class ClientHandler(private val socket: LocalSocket) {
  companion object {
    private const val TAG = "studio.ClientHandler"
  }

  fun handle() {
    Log.i(TAG, "Client connected!")
    try {
      socket.use { s ->
        val inputStream = s.inputStream
        val outputStream = s.outputStream

        // Read framed message
        val payload = FramingProtocol.readMessage(inputStream)
        val message = String(payload, Charsets.UTF_8)
        Log.i(TAG, "Received message: $message")

        // Send framed response
        val response = "Hello from agent!".toByteArray(Charsets.UTF_8)
        FramingProtocol.writeMessage(outputStream, response)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error handling client", e)
    }
  }
}
