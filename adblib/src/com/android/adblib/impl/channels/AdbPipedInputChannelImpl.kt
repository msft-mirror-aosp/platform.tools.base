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
package com.android.adblib.impl.channels

import com.android.adblib.AdbPipedInputChannel
import com.android.adblib.AdbPipedOutputChannel
import com.android.adblib.AdbSession
import com.android.adblib.adbLogger
import com.android.adblib.utils.CircularByteBuffer
import com.android.adblib.withErrorTimeout
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

internal class AdbPipedInputChannelImpl(private val session: AdbSession, bufferSize: Int = DEFAULT_BUFFER_SIZE) : AdbPipedInputChannel {

  private val logger = adbLogger(session)

  /** Guard access to [circularBuffer] */
  private val circularBufferLock = Any()

  /** The circular buffer used to store bytes for [readBuffer] and [AdbPipedOutputChannelImpl.writeBuffer] operations */
  private val circularBuffer = CircularByteBuffer(bufferSize)

  /**
   * [StateFlow] of [State] used to coordinate calls to [readBuffer], [AdbPipedOutputChannelImpl.writeBuffer], [close],
   * [AdbPipedOutputChannelImpl.close] and [AdbPipedOutputChannelImpl.error]. The intent is to make sure any state change will wake up a
   * pending [readBuffer] or [AdbPipedOutputChannelImpl.writeBuffer] waiting on the [StateFlow]. For example, a call to [close] should
   * interrupt suspended calls to [readBuffer].
   */
  private val state =
    MutableStateFlow(
      State(
        receivedBytes = circularBuffer.size,
        freeBytes = circularBuffer.remaining,
        closed = false,
        pipeSourceClosed = false,
        pipeSourceError = null,
      )
    )

  override val pipeSource: AdbPipedOutputChannel = AdbPipedOutputChannelImpl(this)

  override fun close() {
    logger.debug { "close()" }
    state.update { it.copy(closed = true) }
  }

  override fun toString(): String {
    return "${AdbPipedInputChannel::class.java.simpleName}(id=${this.hashCode()}, state=${state.value})"
  }

  override suspend fun readBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
    logger.verbose { "readBuffer(${buffer.remaining()})" }

    // If close has been called, fail immediately
    throwIfClosed { ClosedChannelException() }

    // No-op if empty target buffer
    if (buffer.remaining() == 0) {
      return
    }

    session.withErrorTimeout(timeout, unit) {
      // We need to retry until we can read at least one byte to `buffer`
      while (true) {
        // Read available bytes from circular buffer (there may not be any)
        val byteCount = wrapCircularBufferOperation { it.read(buffer) }

        // There were bytes available in the circular buffer, return now
        if (byteCount > 0) {
          logger.verbose { "read: $byteCount bytes read" }
          break
        }

        val currentStateValue = state.value
        when {
          currentStateValue.pipeSourceError != null -> {
            // If output had an error and there are no available bytes, rethrow error
            logger.verbose { "read(): error from pipe source: '${currentStateValue.pipeSourceError}'" }
            throw currentStateValue.pipeSourceError
          }

          currentStateValue.pipeSourceClosed -> {
            // If output was closed and there are no available bytes, we reached EOF
            logger.verbose { "read(): EOF reached" }
            break
          }
        }

        // Wait until bytes are received, or pipe (source) have been closed
        state.waitUntil { closed || pipeSourceClosed || pipeSourceError != null || receivedBytes > 0 }

        // If close has been called, fail immediately
        throwIfClosed { AsynchronousCloseException() }
      }
    }
  }

  private inline fun <T : ClosedChannelException> throwIfClosed(factory: () -> T) {
    state.value.also { value ->
      if (value.closed) {
        throw factory().initCause(IOException("${AdbPipedInputChannel::class.java.simpleName} has been closed"))
      }
    }
  }

  /** Wraps a read or write [operation] on [circularBuffer] and returns the number of bytes processed. */
  private inline fun wrapCircularBufferOperation(operation: (CircularByteBuffer) -> Int): Int {
    return synchronized(circularBufferLock) {
      val byteCount = operation(circularBuffer)

      // Update our state to reflect new state of the circular buffer
      state.update { it.copy(receivedBytes = circularBuffer.size, freeBytes = circularBuffer.remaining) }
      byteCount
    }
  }

  private class AdbPipedOutputChannelImpl(private val pipeImpl: AdbPipedInputChannelImpl) : AdbPipedOutputChannel {

    private val session: AdbSession
      get() = pipeImpl.session

    private val state: MutableStateFlow<State>
      get() = pipeImpl.state

    private val logger = adbLogger(session)

    override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
      logger.verbose { "writeBuffer(${buffer.remaining()})" }

      // If pipe source is closed (or "close") has been called, fail immediately
      throwIfPipeSourceClosedOrError { ClosedChannelException() }

      // No-op if empty source buffer
      if (buffer.remaining() == 0) {
        return
      }

      session.withErrorTimeout(timeout, unit) {
        // We need to retry until we can write at least one byte from `sourceBuffer`
        while (true) {
          // Move bytes from the source buffer to the circular buffer
          val byteCount = pipeImpl.wrapCircularBufferOperation { it.write(buffer) }

          // If there was room in circular buffer, we are done
          if (byteCount > 0) {
            logger.verbose { "writeBuffer(): $byteCount bytes received" }
            break
          }

          // There was no room in circular buffer, wait until close is called
          // or a "read" operation frees up room from the circular buffer.
          state.waitUntil { closed || pipeSourceClosed || pipeSourceError != null || freeBytes > 0 }

          // If "closeWriter" (or "close") has been called, fail immediately
          throwIfPipeSourceClosedOrError { AsynchronousCloseException() }
        }
      }
    }

    override suspend fun flush() {
      logger.debug { "flush()" }
      throwIfPipeSourceClosedOrError { ClosedChannelException() }

      // Wait until pipe is empty (throw if it is closed during the wait)
      while (true) {
        state.waitUntil { closed || pipeSourceClosed || pipeSourceError != null || receivedBytes == 0 }
        throwIfPipeSourceClosedOrError { AsynchronousCloseException() }
        if (state.value.receivedBytes == 0) {
          break
        }
      }
    }

    override suspend fun shutdown() {
      logger.debug { "shutdown()" }
      throwIfPipeSourceClosedOrError { ClosedChannelException() }
      flush()
      close()
    }

    override fun close() {
      logger.debug { "close()" }
      // Update state so that a pending `writeXxx` operation interrupts itself, as well
      // or ensure future `writeXxx` operation fail immediately.
      state.update { it.copy(pipeSourceClosed = true) }
    }

    override fun error(throwable: Throwable) {
      logger.debug { "error($throwable)" }
      // We just update the pipe state to notify the pipe read operations,
      // no pending operation on this pipe source is affected
      state.update { it.copy(pipeSourceError = throwable) }
    }

    private inline fun <T : ClosedChannelException> throwIfPipeSourceClosedOrError(factory: () -> T) {
      state.value.also { value ->
        if (value.pipeSourceClosed) {
          throw factory().initCause(IOException("${AdbPipedOutputChannel::class.java.simpleName} has been closed"))
        }
        if (value.closed) {
          throw factory().initCause(IOException("${AdbPipedInputChannel::class.java.simpleName} has been closed"))
        }
        if (value.pipeSourceError != null) {
          throw factory().initCause(value.pipeSourceError)
        }
      }
    }
  }

  private data class State(
    /** The number of in-use bytes in [circularBuffer] (i.e. [CircularByteBuffer.size]) */
    val receivedBytes: Int,
    /** The number of unused bytes in [circularBuffer] (i.e. [CircularByteBuffer.remaining]) */
    val freeBytes: Int,
    /** Whether [AdbPipedInputChannel.close] has been called */
    val closed: Boolean,
    /** Whether [AdbPipedOutputChannel.shutdown] or [AdbPipedOutputChannel.close] has been called */
    val pipeSourceClosed: Boolean,
    /** Error reported from [AdbPipedOutputChannel.error] */
    val pipeSourceError: Throwable?,
  )

  companion object {

    private suspend inline fun <T> StateFlow<T>.waitUntil(crossinline predicate: T.() -> Boolean) {
      if (!value.predicate()) {
        first { it.predicate() }
      }
    }
  }
}
