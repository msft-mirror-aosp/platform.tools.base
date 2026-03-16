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

import androidx.tracing.TraceSink
import androidx.tracing.Tracer
import androidx.tracing.wire.ExperimentalRingBufferApi
import androidx.tracing.wire.InMemoryRingBufferTraceSink
import androidx.tracing.wire.TraceDriver
import com.android.tools.tracer.Tracing.initialize
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import okio.appendingSink
import okio.buffer
import org.jetbrains.annotations.VisibleForTesting

@OptIn(ExperimentalRingBufferApi::class)
object Tracing {
  private val state = AtomicReference<TracingState?>(null)

  internal val tracer: Tracer?
    get() = state.get()?.driver?.tracer

  /** Initialize the tracer with the provided [config]. */
  @JvmStatic
  fun initialize(config: TracingConfigProvider) {
    if (state.get()?.isEquivalent(config) == true) {
      return
    }

    initialize(config) { dir -> dir.perfettoTraceFile() }
  }

  @VisibleForTesting
  internal fun initialize(config: TracingConfigProvider, fileProvider: (File) -> File) {
    val traceDirectory = config.getTraceDirectory()

    var oldState: TracingState? = null
    val newState: TracingState

    synchronized(this) {
      val current = state.get()

      newState =
        if (current?.canReuse(config) == true) {
          // All that changes about the previous state is the file, so just copy.
          current.copy(traceFile = fileProvider(traceDirectory))
        } else {
          oldState = current
          createNewState(config, fileProvider)
        }

      state.set(newState)
    }

    oldState?.close()
  }

  private fun createNewState(config: TracingConfigProvider, fileProvider: (File) -> File): TracingState {
    val capacity = config.getRingBufferCapacity()
    val isEnabled = config.isTracingEnabled()
    val traceDirectory = config.getTraceDirectory()
    val traceFile = fileProvider(traceDirectory)
    val sink = TracingSink(capacity, traceFile)
    val driver = TraceDriver(sink.sink, isEnabled)
    return TracingState(config, isEnabled, capacity, traceDirectory, traceFile, driver, sink, fileProvider)
  }

  /**
   * Flush any current trace events to a file and return the path.
   *
   * [initialize] must first be called before this can take any action.
   *
   * Upon flushing, a new file will be started.
   */
  @JvmStatic
  fun flush(): String? {
    val currentState = state.get() ?: return null
    val file = currentState.traceFile
    currentState.sink.flushTo(file)
    initialize(currentState.config, currentState.fileProvider)
    return file.absolutePath
  }

  /**
   * Closes the active tracing session. Any subsequent calls to [trace] will be ignored until [initialize] is called again.
   *
   * If [saveToDisk] is true (defaults to false), flushes any unwritten trace data to disk before closing. This is only applicable when
   * using a ring buffer (capacity > 0). Without a ring buffer, data is always flushed.
   */
  @JvmStatic
  fun close(saveToDisk: Boolean = false) {
    // If we don't have a current state, exit early.
    val currentState = state.getAndSet(null) ?: return
    val file = currentState.traceFile
    if (saveToDisk) {
      currentState.sink.flushTo(file)
    }
    currentState.close()
  }

  private data class TracingState(
    val config: TracingConfigProvider,
    val isTracingEnabled: Boolean,
    val ringBufferCapacity: Long,
    val traceDirectory: File,
    val traceFile: File,
    val driver: androidx.tracing.TraceDriver,
    val sink: TracingSink,
    val fileProvider: (File) -> File,
  ) : AutoCloseable by driver {
    fun isEquivalent(newConfig: TracingConfigProvider): Boolean {
      return isTracingEnabled == newConfig.isTracingEnabled() &&
        ringBufferCapacity == newConfig.getRingBufferCapacity() &&
        traceDirectory.absolutePath == newConfig.getTraceDirectory().absolutePath
    }

    // If the config is the same, a ring buffer sink can be reused.
    // Non-ring buffer sinks don't natively handle file rotation.
    fun canReuse(newConfig: TracingConfigProvider): Boolean {
      return sink.canReuse && isEquivalent(newConfig)
    }
  }

  private sealed interface TracingSink {
    val sink: TraceSink
    val canReuse: Boolean

    fun flushTo(file: File)

    companion object {
      operator fun invoke(capacity: Long, traceFile: File): TracingSink {
        return if (capacity > 0) {
          RingBufferTracingSink(capacity)
        } else {
          StandardTracingSink(traceFile)
        }
      }
    }
  }

  private class RingBufferTracingSink(capacity: Long) : TracingSink {
    override val sink = InMemoryRingBufferTraceSink(1, capacity)
    override val canReuse = true

    override fun flushTo(file: File) {
      file.appendingSink().buffer().use { buffer -> sink.flushTo(buffer) }
    }
  }

  private class StandardTracingSink(file: File) : TracingSink {
    override val sink = androidx.tracing.wire.TraceSink(1, file.appendingSink().buffer())
    override val canReuse = false

    override fun flushTo(file: File) {
      // No explicit flush required as the sink can't be reused and is closed upon flushing.
    }
  }
}

private fun File.perfettoTraceFile(): File {
  val formatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
  formatter.timeZone = TimeZone.getTimeZone("UTC")
  val traceFile = File(this, "perfetto-${formatter.format(Date())}.perfetto")
  return traceFile
}

// TODO(b/467364934): Finalize the tracing APIs below for use within Studio and other tools.

/**
 * Traces the [block] as a named section of code in the trace with context propagation. If [block] is suspending, you should use
 * [traceCoroutine] instead since Kotlin cannot overload the function with the same name. This returns an [AutoCloseable] instance that can
 * be used to close the trace section.
 *
 * It's useful to add a [category] to trace events so that they can be filtered if necessary using the appropriate trace configuration.
 * [name] gives a name to the trace section. [isRoot] provides a hint to the [Tracer] that this trace section is an entry point that all
 * subsequent trace spans can be attributed to. Some [Tracer] implementations treat trace sections as a forest, and require that there is at
 * least one top level root span.
 */
fun <T> trace(category: String? = null, name: String? = null, isRoot: Boolean = false, block: () -> T): T {
  val tracer = Tracing.tracer ?: return block.invoke()
  val traceName = name ?: block.toString()
  val traceCategory = category ?: "default"
  return tracer.trace(traceCategory, traceName, isRoot = isRoot, block = block)
}

/**
 * Traces the suspending [block] as a named section of code in the trace with context propagation. If [block] is *not* suspending, you
 * should use [trace] instead since Kotlin cannot overload the function with the same name. See [trace] for further details, as they are
 * otherwise identical.
 */
suspend fun <T> traceCoroutine(category: String? = null, name: String? = null, isRoot: Boolean = false, block: suspend () -> T): T {
  val tracer = Tracing.tracer ?: return block.invoke()
  val traceName = name ?: block.toString()
  val traceCategory = category ?: "default"
  return tracer.traceCoroutine(traceCategory, traceName, isRoot = isRoot, block = block)
}
