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

import android.net.LocalServerSocket
import android.util.Log
import com.android.tools.ui.inspector.common.ProtocolConstants
import java.io.IOException
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
    CoroutineScope(Dispatchers.IO).launch {
      try {
        runServer(pid)
      } catch (t: Throwable) {
        // Catching Throwable prevents any unhandled exception or error in the agent
        // from bringing down the entire application process.
        Log.e(TAG, "Uncaught exception in inspector", t)
      }
    }
  }

  private suspend fun runServer(pid: String) = coroutineScope {
    val socketName = ProtocolConstants.getSocketName(pid)

    try {
      LocalServerSocket(socketName).use { serverSocket ->
        Log.i(TAG, "Server listening on $socketName")

        while (isActive) {
          val socket = serverSocket.accept()
          ClientHandler(socket).handle()
        }
      }
    } catch (e: IOException) {
      if (e.message?.contains("Address already in use") == true) {
        Log.i(TAG, "Server is already running on $socketName")
      } else {
        Log.e(TAG, "Error in server loop", e)
      }
    }
  }
}
