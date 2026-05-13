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
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Command
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.InspectorMessageResponse
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Response
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.ShutdownCommand
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class CommandSenderTest {

  private val executor = Executors.newSingleThreadExecutor()
  private val dispatcher = executor.asCoroutineDispatcher()

  @Test
  fun testSendMessage() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort

    coroutineScope {
      val serverTask =
        async(dispatcher) {
          serverSocket.accept().use { clientSocket ->
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            val requestBytes = FramingProtocol.readMessage(input)
            val request = Command.parseFrom(requestBytes)

            val response = Response.newBuilder().setCommandId(request.commandId).setStatus(Response.Status.SUCCESS).build()

            FramingProtocol.writeMessage(output, response.toByteArray())
          }
        }

      CommandSender("localhost", port).use { sender ->
        val command = Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build()
        val response = sender.sendMessage(command)

        assertThat(response.status).isEqualTo(Response.Status.SUCCESS)
        assertThat(response.commandId).isEqualTo(1) // First command should have ID 1
      }

      serverTask.await()
    }
    serverSocket.close()
  }

  @Test
  fun testSendMessage_IdMismatch() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort

    coroutineScope {
      async(dispatcher) {
        serverSocket.accept().use { clientSocket ->
          val input = clientSocket.getInputStream()
          val output = clientSocket.getOutputStream()

          val requestBytes = FramingProtocol.readMessage(input)
          val request = Command.parseFrom(requestBytes)

          // Send back wrong ID
          val response = Response.newBuilder().setCommandId(request.commandId + 1).setStatus(Response.Status.SUCCESS).build()

          FramingProtocol.writeMessage(output, response.toByteArray())
        }
      }

      CommandSender("localhost", port).use { sender ->
        val command = Command.newBuilder().setShutdown(ShutdownCommand.getDefaultInstance()).build()
        try {
          sender.sendMessage(command)
          fail("Expected IllegalArgumentException due to ID mismatch")
        } catch (e: IllegalArgumentException) {
          assertThat(e.message).contains("Received response for wrong command")
        }
      }
    }
    serverSocket.close()
  }

  @Test
  fun testSendInspectorCommand() = runTest {
    val serverSocket = ServerSocket(0)
    val port = serverSocket.localPort

    coroutineScope {
      async(dispatcher) {
        serverSocket.accept().use { clientSocket ->
          val input = clientSocket.getInputStream()
          val output = clientSocket.getOutputStream()

          val requestBytes = FramingProtocol.readMessage(input)
          val request = Command.parseFrom(requestBytes)

          val inspectorMsg = request.inspectorMessage
          val responsePayload = inspectorMsg.payload.toStringUtf8().reversed()

          val responseEnvelope =
            InspectorMessageResponse.newBuilder()
              .setInspectorId(inspectorMsg.inspectorId)
              .setPayload(ByteString.copyFromUtf8(responsePayload))
              .build()

          val response =
            Response.newBuilder()
              .setCommandId(request.commandId)
              .setStatus(Response.Status.SUCCESS)
              .setInspectorMessage(responseEnvelope)
              .build()

          FramingProtocol.writeMessage(output, response.toByteArray())
        }
      }

      CommandSender("localhost", port).use { sender ->
        val payload = "hello".toByteArray()
        val responseBytes = sender.sendInspectorCommand("my-inspector", payload)

        assertThat(String(responseBytes)).isEqualTo("olleh")
      }
    }
    serverSocket.close()
  }
}
