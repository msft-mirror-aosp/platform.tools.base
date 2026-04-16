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

package com.android.tools.ui.inspector.payload.appinspection

import android.os.Handler
import android.util.Log
import androidx.inspection.ArtTooling
import androidx.inspection.Connection
import androidx.inspection.InspectorEnvironment
import androidx.inspection.InspectorExecutors
import com.android.tools.ui.inspector.common.FramingProtocol
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val TAG = "studio.AppInspectionUtils"

/** Creates a [Connection] that writes events to the given [OutputStream] using [FramingProtocol]. */
internal fun createAppInspectionConnection(outputStream: OutputStream, crashListener: (Throwable) -> Unit): Connection {
  return object : Connection() {
    override fun sendEvent(event: ByteArray) {
      try {
        FramingProtocol.writeMessage(outputStream, event)
      } catch (e: IOException) {
        Log.e(TAG, "IO error sending event", e)
      } catch (e: Exception) {
        Log.e(TAG, "Unexpected error sending event", e)
        crashListener(e)
      }
    }
  }
}

/** Executor for disk I/O operations. We use a fixed thread pool of size 4, matching the behavior of AppInspectionService. */
private val IoExecutor = Executors.newFixedThreadPool(4)

internal fun createInspectorEnvironment(primaryExecutor: HandlerThreadExecutor, crashListener: (Throwable) -> Unit): InspectorEnvironment {
  return object : InspectorEnvironment {
    override fun artTooling(): ArtTooling {
      // TODO: Implement ArtTooling methods to support finding instances and setting hooks.
      return object : ArtTooling {
        override fun <T> findInstances(clazz: Class<T>): List<T> = emptyList()

        override fun registerEntryHook(originClass: Class<*>, originMethod: String, entryHook: ArtTooling.EntryHook) {}

        override fun <T> registerExitHook(originClass: Class<*>, originMethod: String, exitHook: ArtTooling.ExitHook<T>) {}
      }
    }

    override fun executors(): InspectorExecutors {
      return object : InspectorExecutors {
        override fun handler(): Handler = primaryExecutor.handler

        override fun primary(): Executor = primaryExecutor

        override fun io(): Executor = createDelegateExecutor(IoExecutor, crashListener)
      }
    }
  }
}

/** Creates an executor that delegates work to the given one and forwards all uncaught exceptions to [crashListener]. */
private fun createDelegateExecutor(delegate: Executor, crashListener: (Throwable) -> Unit): Executor {
  return Executor { command ->
    delegate.execute {
      try {
        command.run()
      } catch (t: Throwable) {
        crashListener(t)
      }
    }
  }
}
