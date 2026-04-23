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
import com.android.tools.ui.inspector.inspectors.view.ViewInspector
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor
import com.android.tools.ui.inspector.payload.appinspection.InspectorMessenger
import com.android.tools.ui.inspector.payload.appinspection.createAppInspectionConnection
import com.android.tools.ui.inspector.payload.appinspection.createInspectorEnvironment
import java.io.EOFException

/** Handles a single connection from the host to a UI Inspector agent. */
class ClientHandler(private val socket: LocalSocket) {
  companion object {
    private const val TAG = "studio.ClientHandler"
    private const val THREAD_NAME = "ui_inspector_thread"
  }

  fun handle() {
    val crashListener: (Throwable) -> Unit = { throwable ->
      // TODO: Temporary crash listener. Eventually we want to report these crashes to the host.
      Log.e(TAG, "Uncaught exception in inspector", throwable)
    }

    // TODO: once we have more than one inspector, differentiate thread name
    val primaryExecutor = HandlerThreadExecutor(THREAD_NAME, crashListener)

    try {
      socket.use { s ->
        val inputStream = s.inputStream
        val outputStream = s.outputStream

        val connection = createAppInspectionConnection(outputStream, crashListener)
        val environment = createInspectorEnvironment(primaryExecutor, crashListener)
        val inspector = ViewInspector(connection, environment)
        val messenger = InspectorMessenger(outputStream, inspector, crashListener)

        try {
          while (true) {
            val payload = FramingProtocol.readMessage(inputStream)
            messenger.handleCommand(payload)
          }
        } catch (e: EOFException) {
          Log.i(TAG, "Client disconnected (EOF)")
        } catch (e: IllegalStateException) {
          Log.e(TAG, "Framing protocol error", e)
          crashListener(e)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error handling client", e)
      crashListener(e)
    } finally {
      primaryExecutor.quitSafely()
    }
  }
}
