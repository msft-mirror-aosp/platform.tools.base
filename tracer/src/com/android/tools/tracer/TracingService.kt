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
package com.android.tools.tracer

import androidx.tracing.TraceDriver
import androidx.tracing.Tracer
import com.android.tools.idea.flags.StudioFlags
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import java.util.logging.Logger

/**
 * Application-level service that manages the lifecycle of the Perfetto tracing session and underlying [TraceDriver].
 *
 * **Note:** This service is intended for internal use within the `com.android.tools.tracer` package. Consumers should not retrieve or
 * interact with this service directly. Instead, use the provided top-level [trace] functions to instrument code.
 *
 * TODO(b/467364934): Move this to a studio-specific place.
 */
@Service(Service.Level.APP)
class TracingService : Disposable {
  private val log = Logger.getLogger(TracingService::class.qualifiedName)
  private lateinit var sink: TracingSink
  private lateinit var driver: TraceDriver
  internal lateinit var tracer: Tracer

  init {
    initDriver()
  }

  private fun initDriver() {
    sink = TracingSink(PathManager.getTempDir().toFile())
    val enabled = StudioFlags.STUDIO_TRACE_LIBRARY_ENABLED.get()
    driver = TracingDriver(sink, isEnabled = enabled)
    log.info("Tracing Driver initialized and ${if (enabled) "enabled" else "disabled"}. Saving traces to ${sink.getTraceFilePath()}.")
    tracer = driver.tracer
  }

  /** Flush the current trace events to a file and start a new file by re-initializing the [TraceDriver]. */
  internal fun flush(): String {
    val file = sink.getTraceFilePath()
    driver.close()
    log.info("Perfetto Traces are flushed to ${file}.")
    initDriver()
    return file
  }

  override fun dispose() {
    driver.close()
  }

  companion object {
    @JvmStatic
    fun getInstance(): TracingService? {
      // Pure tests don't have access to this as a service. Making it nullable will allow callers of
      // the top-level functions to perform a no-op.
      val app = ApplicationManager.getApplication() ?: return null

      return try {
        // This attempts to construct the service. Any failure to do so (e.g.
        // PathManager.getTempDir() not available), should result in a no-op service.
        app.getService(TracingService::class.java)
      } catch (_: Throwable) {
        null
      }
    }
  }
}
