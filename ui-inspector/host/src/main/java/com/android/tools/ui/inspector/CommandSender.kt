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

import com.android.tools.ui.inspector.common.FramingProtocol
import com.android.tools.ui.inspector.protocol.ViewInspectorProtocol.Command
import com.android.tools.ui.inspector.protocol.ViewInspectorProtocol.Response
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends messages to the UI Inspector agent running on the device and receives responses. Uses the shared FramingProtocol for message
 * framing.
 */
class CommandSender(host: String, port: Int) : AutoCloseable {

  private val socket = Socket(host, port)
  private val outputStream = socket.getOutputStream()
  private val inputStream = socket.getInputStream()

  /** Sends a command and waits for a response. */
  suspend fun sendMessage(command: Command): Response =
    withContext(Dispatchers.IO) {
      // Send framed message
      val payload = command.toByteArray()
      FramingProtocol.writeMessage(outputStream, payload)

      // Read framed response
      val responseBytes = FramingProtocol.readMessage(inputStream)
      Response.parseFrom(responseBytes)
    }

  override fun close() {
    socket.close()
  }
}
