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
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.AgentMessage
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Command
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.InspectorMessageCommand
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Response
import com.google.protobuf.ByteString
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exception thrown when the UI Inspector agent crashes on the device. */
class InspectorCrashException(errorMessage: String, val stackTrace: String) :
  RuntimeException("UI Inspector agent crashed: $errorMessage\nAgent Stack Trace:\n$stackTrace")

/**
 * Sends messages to the UI Inspector agent running on the device and receives responses. Uses the shared FramingProtocol for message
 * framing.
 */
class CommandSender(host: String, port: Int) : AutoCloseable {

  private val socket = Socket(host, port)
  private val outputStream = socket.getOutputStream()
  private val inputStream = socket.getInputStream()

  private val nextCommandId = AtomicInteger(1)

  /**
   * Sends a command and waits for a response.
   *
   * TODO: For now, keep the communication synchronous (wait for next message) but validate the ID. Future refactoring will introduce a
   *   background reader thread for true multiplexing.
   */
  suspend fun sendMessage(command: Command): Response =
    withContext(Dispatchers.IO) {
      val commandId = nextCommandId.getAndIncrement()
      val commandWithId = command.toBuilder().setCommandId(commandId).build()

      // Send framed message
      val payload = commandWithId.toByteArray()
      FramingProtocol.writeMessage(outputStream, payload)

      // Read framed response
      val responseBytes = FramingProtocol.readMessage(inputStream)
      val agentMessage = AgentMessage.parseFrom(responseBytes)

      if (agentMessage.hasEvent()) {
        val event = agentMessage.event
        if (event.hasCrash()) {
          val crash = event.crash
          throw InspectorCrashException(crash.errorMessage, crash.stackTrace)
        }
        error("Received unexpected event: ${event.specializedCase}")
      }

      val response = agentMessage.response
      require(response.commandId == commandId) { "Received response for wrong command. Expected: $commandId, Got: ${response.commandId}" }

      response
    }

  /** Sends a command targeted at a specific inspector and returns the unwrapped response payload. */
  suspend fun sendInspectorCommand(inspectorId: String, payload: ByteArray): ByteArray =
    withContext(Dispatchers.IO) {
      val envelope = InspectorMessageCommand.newBuilder().setInspectorId(inspectorId).setPayload(ByteString.copyFrom(payload)).build()
      val command = Command.newBuilder().setInspectorMessage(envelope).build()
      val response = sendMessage(command)

      if (response.status != Response.Status.SUCCESS) {
        error("Agent Command Failed: ${response.errorMessage}")
      }

      val responseEnvelope = response.inspectorMessage
      if (responseEnvelope.inspectorId != inspectorId) {
        error("Received response for wrong inspector. Expected: $inspectorId, Got: ${responseEnvelope.inspectorId}")
      }
      responseEnvelope.payload.toByteArray()
    }

  override fun close() {
    socket.close()
  }
}
