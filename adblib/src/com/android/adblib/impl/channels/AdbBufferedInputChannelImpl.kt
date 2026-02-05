/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.adblib.AdbInputChannel
import com.android.adblib.AdbSession
import com.android.adblib.adbLogger
import com.android.adblib.read
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Implementation of an [AdbInputChannel] that reads data from another [AdbInputChannel] using an internal [ByteBuffer] of the given buffer
 * size.
 *
 * This class is similar to [BufferedInputStream], but for [AdbInputChannel] instead of [InputStream].
 */
internal class AdbBufferedInputChannelImpl(
  session: AdbSession,
  private val sourceChannel: AdbInputChannel,
  bufferSize: Int = DEFAULT_BUFFER_SIZE,
  private val closeSourceChannel: Boolean = true,
) : AdbInputChannel {

  private val logger = adbLogger(session)

  /** The bytes that were read from [sourceChannel], starting at position `0` up to position [ByteBuffer.limit]. */
  private val inputBuffer = ByteBuffer.allocate(bufferSize).limit(0)

  /**
   * [ByteBuffer.slice] of [inputBuffer] that contains bytes available for the next [read] operations(s), starting at [ByteBuffer.position]
   * up to [ByteBuffer.limit].
   *
   * Note: [inputBuffer] and [inputBufferSlice] always have the same [ByteBuffer.limit] and [ByteBuffer.capacity].
   */
  private val inputBufferSlice = inputBuffer.duplicate()

  private var closed = false

  init {
    assertInputBufferIsValid()
  }

  override suspend fun readBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
    throwIfClosed()
    if (inputBufferSlice.remaining() > 0) {
      // Fast path: internal buffer contains data so we can return right away
      copyInputBufferTo(buffer).also { count -> logger.verbose { "read: Copied $count bytes from input '$sourceChannel'" } }
    } else if (buffer.remaining() > inputBuffer.capacity()) {
      // If the buffer is larger than our buffer capacity, read directly from the
      // underlying source channel (and clear internal buffer)
      inputBuffer.clear().limit(0)
      inputBufferSlice.clear().limit(0)
      sourceChannel.readBuffer(buffer, timeout, unit)
    } else {
      readAndCopyInputBufferTo(buffer, timeout, unit)
    }
  }

  override suspend fun readExactly(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
    throwIfClosed()
    // Fast path: internal buffer contains enough data to fill [buffer]
    if (inputBufferSlice.remaining() >= buffer.remaining()) {
      copyInputBufferTo(buffer).also { count -> logger.verbose { "readExactly: Copied $count bytes from input '$sourceChannel'" } }
      assert(buffer.remaining() == 0)
      return
    }
    super.readExactly(buffer, timeout, unit)
  }

  override fun close() {
    closed = true
    if (closeSourceChannel) {
      sourceChannel.close()
    }
  }

  private fun copyInputBufferTo(dstBuffer: ByteBuffer): Int {
    val count = min(inputBufferSlice.remaining(), dstBuffer.remaining())
    val savedLimit = inputBufferSlice.limit()
    inputBufferSlice.limit(inputBufferSlice.position() + count)
    dstBuffer.put(inputBufferSlice)
    inputBufferSlice.limit(savedLimit)
    return count
  }

  private suspend fun readAndCopyInputBufferTo(dstBuffer: ByteBuffer, timeout: Long, unit: TimeUnit): Int {
    assertInputBufferIsValid()
    inputBuffer.clear()
    inputBufferSlice.clear()
    val count = sourceChannel.read(inputBuffer, timeout, unit)
    logger.verbose { "read: Read $count bytes from input '$sourceChannel'" }
    if (count <= 0) {
      return count
    }
    assert(count == inputBuffer.position())
    // Data from [0, position=count], change to [position=0, limit=count]
    inputBuffer.flip()
    inputBufferSlice.limit(count)
    assertInputBufferIsValid()
    assert(inputBuffer.remaining() > 0)
    assert(inputBufferSlice.remaining() > 0)
    assert(inputBufferSlice.remaining() == inputBuffer.remaining())
    return copyInputBufferTo(dstBuffer)
  }

  @Suppress("NOTHING_TO_INLINE")
  private inline fun assertInputBufferIsValid() {
    assert(inputBuffer.position() == 0)
    assert(inputBuffer.limit() == inputBufferSlice.limit())
    assert(inputBuffer.capacity() == inputBufferSlice.capacity())
  }

  private fun throwIfClosed() {
    if (closed) {
      throw ClosedChannelException().initCause(IOException("${AdbInputChannel::class.java.simpleName} has been closed"))
    }
  }
}
