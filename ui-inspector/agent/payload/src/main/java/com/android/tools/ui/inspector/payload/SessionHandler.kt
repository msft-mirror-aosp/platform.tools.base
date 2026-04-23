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

import android.util.Log
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.ui.inspector.common.FramingProtocol
import com.android.tools.ui.inspector.payload.appinspection.DelegatingConnection
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor
import com.android.tools.ui.inspector.payload.appinspection.createAppInspectionConnection
import com.android.tools.ui.inspector.payload.appinspection.createInspectorEnvironment
import com.android.tools.ui.inspector.payload.appinspection.loadInspectorDynamically
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Command
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.CreateInspectorCommand
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.CreateInspectorResponse
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.InspectorMessageCommand
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.InspectorMessageResponse
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Response
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.ShutdownResponse
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

private const val TAG = "studio.SessionHandler"

private const val THREAD_NAME_PREFIX = "ui_inspector_"

/**
 * Handles a single connection session with the host.
 *
 * @param inputStream The input stream to read commands from.
 * @param outputStream The output stream to write responses to.
 * @param crashListener Callback invoked when an unhandled exception occurs.
 * @param shutdownSignal Used to propagate the signal to terminate the server.
 * @param serverScope Scope tied to the life of the server.
 * @param inspectorBridges Map of active inspectors, shared across sessions.
 */
internal class SessionHandler(
  private val inputStream: InputStream,
  private val outputStream: OutputStream,
  private val crashListener: (Throwable) -> Unit,
  private val shutdownSignal: CompletableDeferred<Unit>,
  private val serverScope: CoroutineScope,
  private val inspectorBridges: MutableMap<InspectorId, InspectorBridge>,
) {

  /**
   * Reads and executes a continuous stream of commands from the host. Loops until the coroutine is cancelled or a shutdown command is
   * received.
   */
  internal suspend fun processCommands() = coroutineScope {
    try {
      while (isActive) {
        val commandBytes = FramingProtocol.readMessage(inputStream)
        val command = Command.parseFrom(commandBytes)
        val commandId = command.commandId

        val shouldTerminate = handleCommand(command, commandId)
        if (shouldTerminate) {
          break
        }
      }
    } catch (e: EOFException) {
      Log.i(TAG, "Client disconnected (EOF)")
    } catch (e: IOException) {
      Log.i(TAG, "Client connection lost: ${e.message}")
    } catch (e: Exception) {
      Log.e(TAG, "Error handling client session", e)
      crashListener(e)
    } finally {
      // Clear active connections in each inspector
      inspectorBridges.values.forEach { it.updateConnection(null) }
    }
  }

  /** Handles a single command and returns whether to terminate the session. Every command must receive a response. */
  private suspend fun handleCommand(command: Command, commandId: Int): Boolean {
    return try {
      when (command.specializedCase) {
        Command.SpecializedCase.INSPECTOR_MESSAGE -> {
          handleInspectorMessage(command.inspectorMessage, commandId)
          false
        }
        Command.SpecializedCase.CREATE_INSPECTOR -> {
          handleCreateInspector(command.createInspector, commandId)
          false
        }
        Command.SpecializedCase.SHUTDOWN -> {
          handleShutdown(commandId)
          true
        }
        else -> error("Unhandled top-level command: ${command.specializedCase}")
      }
    } catch (e: IOException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Error handling command", e)
      outputStream.replyError(commandId = commandId, errorMessage = "Error handling command: ${e.message}")
      false
    }
  }

  private suspend fun handleInspectorMessage(message: InspectorMessageCommand, commandId: Int) {
    val inspectorBridge = inspectorBridges[message.inspectorId] ?: error("Unknown inspector ID: ${message.inspectorId}")
    val responseBytes = inspectorBridge.sendCommand(message.payload.toByteArray())

    outputStream.reply(commandId = commandId) {
      val payload = ByteString.copyFrom(responseBytes)
      inspectorMessage = InspectorMessageResponse.newBuilder().setInspectorId(message.inspectorId).setPayload(payload).build()
    }
  }

  private fun handleCreateInspector(command: CreateInspectorCommand, commandId: Int) {
    val inspectorId = command.inspectorId
    val dexPath = command.dexPath

    val bridge = inspectorBridges[inspectorId]
    if (bridge != null) {
      // Update the bridge with the new session's connection to resume event sending.
      val newRealConnection = createAppInspectionConnection(inspectorId, outputStream, crashListener)
      bridge.updateConnection(newRealConnection)
    } else {
      val realConnection = createAppInspectionConnection(inspectorId, outputStream, crashListener)
      val delegatingConnection = DelegatingConnection(realConnection)

      val primaryExecutor = HandlerThreadExecutor("${THREAD_NAME_PREFIX}${inspectorId}", crashListener)
      val inspectorEnvironment = createInspectorEnvironment(primaryExecutor, crashListener)
      val inspector = loadInspectorDynamically(inspectorId, dexPath, delegatingConnection, inspectorEnvironment)
      val newBridge = InspectorBridge(inspector, delegatingConnection, serverScope, primaryExecutor)

      inspectorBridges[inspectorId] = newBridge
    }

    outputStream.reply(commandId = commandId) { createInspector = CreateInspectorResponse.newBuilder().build() }
  }

  private fun handleShutdown(commandId: Int) {
    outputStream.reply(commandId = commandId) { shutdown = ShutdownResponse.newBuilder().build() }
    shutdownSignal.complete(Unit)
  }
}

private fun OutputStream.reply(commandId: Int, initResponse: Response.Builder.() -> Unit = {}) {
  writeResponse(commandId = commandId, status = Response.Status.SUCCESS, initResponse = initResponse)
}

private fun OutputStream.replyError(commandId: Int, errorMessage: String) {
  writeResponse(commandId = commandId, status = Response.Status.ERROR, errorMessage = errorMessage)
}

private fun OutputStream.writeResponse(
  commandId: Int,
  status: Response.Status,
  errorMessage: String? = null,
  initResponse: Response.Builder.() -> Unit = {},
) {
  val responseBuilder = Response.newBuilder().setCommandId(commandId).setStatus(status)

  if (errorMessage != null) {
    responseBuilder.setErrorMessage(errorMessage)
  }

  responseBuilder.initResponse()
  FramingProtocol.writeMessage(this, responseBuilder.build().toByteArray())
}
