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

import androidx.tracing.DelicateTracingApi
import androidx.tracing.PooledTracePacketArray
import androidx.tracing.wire.TraceSink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import okio.appendingSink
import okio.buffer

// TODO(b/479214523): Upstream this use-case to avoid needing to write our own TraceSink.
@OptIn(DelicateTracingApi::class)
internal class TracingSink(private val directory: File) : androidx.tracing.TraceSink() {
  private val traceFile = directory.perfettoTraceFile()
  private val sink = TraceSink(sequenceId = 1, bufferedSink = traceFile.appendingSink().buffer(), coroutineContext = Dispatchers.IO)

  /** Provides the absolute path to the current perfetto file. */
  fun getTraceFilePath(): String {
    return traceFile.absolutePath
  }

  override fun enqueue(pooledPacketArray: PooledTracePacketArray) {
    sink.enqueue(pooledPacketArray)
  }

  override fun onDroppedTraceEvent() {
    sink.onDroppedTraceEvent()
  }

  override fun flush() {
    sink.flush()
  }

  override fun close() {
    sink.close()
  }
}

private fun File.perfettoTraceFile(): File {
  val formatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
  formatter.timeZone = TimeZone.getTimeZone("UTC")
  val traceFile = File(this, "perfetto-${formatter.format(Date())}.perfetto")
  return traceFile
}
