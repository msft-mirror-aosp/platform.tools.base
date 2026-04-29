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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

internal typealias InspectorId = String

private const val TAG = "studio.Server"
private val INACTIVITY_TIMEOUT = 5.minutes

/** Handles the server socket listener and connection lifecycle for the UI Inspector. */
internal suspend fun startServer(pid: String) = supervisorScope {
  val socketName = ProtocolConstants.getSocketName(pid)

  /** Completes when a request to shutdown the server is received. */
  val shutdownSignal = CompletableDeferred<Unit>()

  /** Flags whether the server stopped due to an inactivity timeout. */
  val timedOut = AtomicBoolean(false)
  var timeoutJob: Job? = null

  // TODO: report crashes to host
  val crashListener: (Throwable) -> Unit = { throwable -> Log.e(TAG, "Uncaught exception in inspector", throwable) }

  /**
   * Holds the bridges to active inspectors. This map is shared across connection sessions to allow inspectors to maintain state across host
   * reconnections.
   */
  val inspectorBridges = ConcurrentHashMap<InspectorId, InspectorBridge>()

  /** Wraps [block] with an inactivity timeout that cancels the scope if it expires. */
  suspend fun <T> withInactivityTimeout(block: suspend () -> T): T {
    timeoutJob?.cancel()
    timeoutJob = launch {
      delay(INACTIVITY_TIMEOUT)
      Log.i(TAG, "Inactivity timeout reached, stopping server")
      timedOut.set(true)
      cancel()
    }
    try {
      return block()
    } finally {
      timeoutJob?.cancel()
    }
  }

  try {
    LocalServerSocket(socketName).use { serverSocket ->
      Log.i(TAG, "Server listening on $socketName")

      launch {
        // Centralized cleanup coroutine. Terminates the server on cancellation.
        try {
          awaitCancellation()
        } finally {
          try {
            // Terminate the server
            serverSocket.close()
          } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket on cleanup", e)
          }

          // Dispose all inspectors
          inspectorBridges.values.forEach { it.dispose() }
        }
      }

      launch {
        // Shutdown signal listener coroutine
        shutdownSignal.await()
        cancel()
      }

      while (isActive) {
        withInactivityTimeout { withContext(Dispatchers.IO) { serverSocket.accept() } }
          .use { socket ->
            SessionHandler(
                inputStream = socket.inputStream,
                outputStream = socket.outputStream,
                crashListener = crashListener,
                shutdownSignal = shutdownSignal,
                serverScope = this,
                inspectorBridges = inspectorBridges,
              )
              .processCommands()
          }
      }
    }
  } catch (e: IOException) {
    if (timedOut.get()) {
      Log.i(TAG, "Server stopped due to inactivity timeout")
    } else if (shutdownSignal.isCompleted) {
      Log.i(TAG, "Server stopped due to shutdown signal")
    } else if (e.message?.contains("Address already in use") == true) {
      Log.i(TAG, "Server is already running on $socketName")
    } else {
      Log.e(TAG, "Error in server loop", e)
    }
  }
}
