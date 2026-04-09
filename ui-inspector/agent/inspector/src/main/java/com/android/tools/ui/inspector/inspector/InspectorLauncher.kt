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

package com.android.tools.ui.inspector.inspector

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Entry point for the UI Inspector payload. Starts a Unix domain socket server to listen for commands from the host. */
object InspectorLauncher {
  private const val TAG = "studio.InspectorLauncher"

  /**
   * Starts the inspector server. The server accepts multiple sequential connections, allowing the host to reconnect to the running agent if
   * needed.
   */
  @JvmStatic
  fun start(pid: String) {
    CoroutineScope(Dispatchers.IO).launch { runServer(pid) }
  }

  private suspend fun runServer(pid: String) = coroutineScope {
    val socketName = "ui_inspector_$pid"

    try {
      LocalServerSocket(socketName).use { serverSocket ->
        Log.i(TAG, "Server listening on $socketName")

        while (isActive) {
          val socket = serverSocket.accept()
          handleClient(socket)
        }
      }
    } catch (e: java.io.IOException) {
      if (e.message?.contains("Address already in use") == true) {
        Log.i(TAG, "Server is already running on $socketName")
      } else {
        Log.e(TAG, "Error in server loop", e)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in server loop", e)
    }
  }

  private fun handleClient(socket: LocalSocket) {
    Log.i(TAG, "Client connected!")
    try {
      socket.use { s ->
        val inputStream = s.inputStream
        val outputStream = s.outputStream

        val buffer = ByteArray(1024)
        val read = inputStream.read(buffer)
        if (read > 0) {
          val message = String(buffer, 0, read)
          Log.i(TAG, "Received message: $message")

          val response = "Hello from agent!"
          outputStream.write(response.toByteArray())
          outputStream.flush()
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error handling client", e)
    }
  }
}
