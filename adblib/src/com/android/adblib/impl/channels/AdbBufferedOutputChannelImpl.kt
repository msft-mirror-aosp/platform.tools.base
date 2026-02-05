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

import com.android.adblib.AdbBufferedOutputChannel
import com.android.adblib.AdbOutputChannel
import com.android.adblib.AdbSession
import com.android.adblib.INFINITE_TIMEOUT
import com.android.adblib.adbLogger
import com.android.adblib.impl.TimeoutTracker
import com.android.adblib.write
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit

internal class AdbBufferedOutputChannelImpl(
  session: AdbSession,
  private val outputChannel: AdbOutputChannel,
  bufferSize: Int = DEFAULT_BUFFER_SIZE,
  private val closeOutputChannel: Boolean = true,
) : AdbBufferedOutputChannel {

  private val logger = adbLogger(session)

  /**
   * The bytes that need to be written to [outputChannel], starting at position `0` up to position [ByteBuffer.limit].
   *
   *      | 0           |  position  . . . .  limit=capacity   |
   *      +-------------+--------------------------------------+
   *      |  data       |           free space                 |
   */
  private val outputBuffer = ByteBuffer.allocate(bufferSize)

  private var isShutdown: Boolean = false

  private var closed: Boolean = false

  override fun toString(): String {
    return "${AdbBufferedOutputChannel::class.java.simpleName}(" +
      "bufferedBytes=${outputBuffer.position()}, " +
      "bufferCapacity=${outputBuffer.capacity()}, " +
      "closed=$closed, " +
      "isShutdown=$isShutdown" +
      ")"
  }

  override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
    throwIfClosed()
    if (buffer.remaining() == 0) {
      return
    }

    // If buffer is large, skip buffering
    if (buffer.remaining() > outputBuffer.capacity()) {
      val tracker = TimeoutTracker.fromTimeout(unit, timeout)
      flushOutputBuffer(tracker.getRemainingTime(unit), unit)
      outputChannel.write(buffer, tracker.getRemainingTime(unit), unit)
      return
    }

    // Fast path: outputBuffer has room for some bytes
    if (outputBuffer.remaining() > 0) {
      copyBufferToOutputBuffer(buffer).also { count -> logger.verbose { "write: Copied $count bytes from buffer to '$outputBuffer'" } }
      return
    }

    // Flush `outputBuffer` to underlying output
    // [0..position...limit] -> [position=0... limit=old position...capacity]
    flushOutputBuffer(timeout, unit)

    // There is room now, so copy as mush as we can
    assert(outputBuffer.remaining() > 0)
    copyBufferToOutputBuffer(buffer).also { count -> logger.verbose { "write: Copied $count bytes from buffer to '$outputBuffer'" } }
  }

  override suspend fun writeExactly(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
    throwIfClosed()
    if (buffer.remaining() == 0) {
      return
    }

    // Fast path: outputBuffer has room for the whole buffer
    if (outputBuffer.remaining() >= buffer.remaining()) {
      copyBufferToOutputBuffer(buffer).also { count -> logger.verbose { "write: Copied $count bytes from buffer to '$outputBuffer'" } }
      return
    }

    super.writeExactly(buffer, timeout, unit)
  }

  override suspend fun flush() {
    throwIfClosed()
    flushOutputBuffer(INFINITE_TIMEOUT, TimeUnit.MILLISECONDS)
    (outputChannel as? AdbBufferedOutputChannel)?.flush()
  }

  override suspend fun shutdown() {
    // To be consistent with `Socket.shutdown`, calling shutdown a 2nd time should throw
    // an exception.
    throwIfClosed()
    flush()
    isShutdown = true
    if (closeOutputChannel) {
      (outputChannel as? AdbBufferedOutputChannel)?.shutdown()
    }
  }

  /**
   * Note: In general, [close] should never fail (even if called multiple times) or suspend (as it can be called in a `finally` block or
   * during coroutine cancellation). Furthermore, in this implementation, we don't need to cancel any pending [write] or [flush] operation
   * as these operation only suspend when writing to the underlying [outputChannel], which should already handle cancellation/close
   * correctly.
   */
  override fun close() {
    closed = true
    if (closeOutputChannel) {
      outputChannel.close()
    }
  }

  private fun throwIfClosed() {
    if (isShutdown) {
      throw ClosedChannelException().initCause(IOException("${AdbBufferedOutputChannel::class.java.simpleName} has been shutdown"))
    }
    if (closed) {
      throw ClosedChannelException().initCause(IOException("${AdbBufferedOutputChannel::class.java.simpleName} has been closed"))
    }
  }

  private fun copyBufferToOutputBuffer(srcBuffer: ByteBuffer): Int {
    val srcCount = srcBuffer.remaining()
    val dstCount = outputBuffer.remaining()
    return if (srcCount <= dstCount) {
      // Fast path: if source buffer has fewer bytes than available space in output buffer,
      // we can just copy all bytes from `srcBuffer`
      outputBuffer.put(srcBuffer)
      srcCount
    } else {
      // Otherwise, we copy `dstCount` bytes from `srcBuffer` to `outputBuffer`
      // Note: We use the internal byte array of outputBuffer internal array to prevent
      // additional allocation of an intermediate ByteBuffer slice of `srcBuffer`
      assert(outputBuffer.hasArray())
      val dstArray = outputBuffer.array()
      val dstIndex = outputBuffer.position()
      assert(srcCount > dstCount)
      srcBuffer.get(dstArray, dstIndex, dstCount)
      outputBuffer.position(dstIndex + dstCount)
      assert(outputBuffer.position() == outputBuffer.limit())
      assert(outputBuffer.position() == outputBuffer.capacity())
      dstCount
    }
  }

  private suspend fun flushOutputBuffer(timeout: Long, unit: TimeUnit) {
    // Write `outputBuffer` data in range [0...position] to `outputChannel`
    outputBuffer.position().also { byteCount ->
      outputBuffer.flip()
      outputChannel.writeExactly(outputBuffer, timeout, unit)
      outputBuffer.clear()
      logger.verbose { "flushOutputBuffer: Written $byteCount bytes to underlying output" }
    }
  }
}
