/*
 * Copyright (C) 2025 The Android Open Source Project
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
import androidx.tracing.wire.TraceSink
import com.android.tools.idea.flags.StudioFlags
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

// TODO(b/467364934): Finalize the tracing API for use within Studio.
class Tracing {
  var driver: TraceDriver? = null

  private fun initDriver(): TraceDriver {
    val enabled = StudioFlags.STUDIO_TRACE_LIBRARY_ENABLED.get()
    // TODO(b/467364934): Finalize the implementation without a file.
    val path: Path = "/tmp/trace.file".toPath()
    val traceSink = TraceSink(1, FileSystem.SYSTEM.sink(path).buffer())
    return TraceDriver(sink = traceSink, isEnabled = enabled)
  }

  private fun getOrCreateDriver(): TraceDriver {
    if (driver == null) {
      driver = initDriver()
    }
    return driver!!
  }
}
