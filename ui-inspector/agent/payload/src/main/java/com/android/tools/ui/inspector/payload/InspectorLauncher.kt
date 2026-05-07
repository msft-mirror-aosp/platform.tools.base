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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Entry point for the UI Inspector payload. Starts a Unix domain socket server to listen for commands from the host. */
object InspectorLauncher {
  private const val TAG = "studio.Inspector"
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var job: Job? = null

  @JvmStatic
  @JvmOverloads
  fun start(pid: String, startServer: suspend (String) -> Unit = ::startServer) {
    synchronized(this) {
      if (job?.isActive == true) {
        Log.i(TAG, "Inspector server is already running.")
        return
      }
      job =
        scope.launch {
          try {
            startServer(pid)
          } catch (t: Throwable) {
            // Catching Throwable prevents any unhandled exception or error in the agent
            // from bringing down the entire application process.
            Log.e(TAG, "Uncaught exception in inspector", t)
          }
        }
    }
  }
}
