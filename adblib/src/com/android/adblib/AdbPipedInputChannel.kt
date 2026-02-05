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
package com.android.adblib

import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * An [AdbInputChannel] that can be fed data asynchronously from its [pipeSource] property, an [AdbOutputChannel] implementation that writes
 * data for use by [read] operations on this [AdbPipedInputChannel].
 *
 * Note: Implementations are guaranteed to be thread-safe, i.e. writing to [pipeSource] can be done concurrently with [read] operations.
 * However, concurrent writes to [pipeSource] or concurrent [reads][read] are not allowed.
 */
interface AdbPipedInputChannel : AdbInputChannel {

  /** The [AdbOutputChannel] used to [send][AdbOutputChannel.write] data to this input pipe */
  val pipeSource: AdbPipedOutputChannel

  /**
   * Reads up to [ByteBuffer.remaining] bytes from the underlying channel, updating [ByteBuffer.position] to match the number of bytes read.
   *
   * If the pipe is empty and [AdbPipedOutputChannel.shutdown] has been called, [buffer] is unchanged (i.e. no bytes are read, signalling
   * EOF).
   * * Throws [ClosedChannelException] if [close] was previously called
   * * Throws [AsynchronousCloseException] if [close] is called __while this function is suspended__
   * * Throws [java.io.IOException] if an I/O occurs reading from the underlying resource
   * * Throws [TimeoutException] in case the data cannot be read before the timeout expires.
   * * Throws [Throwable] if [AdbPipedOutputChannel.error] was previously called and the pipe is empty.
   */
  override suspend fun readBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit)
}

/** An [AdbOutputChannel] used to send data to an [AdbPipedInputChannel] */
interface AdbPipedOutputChannel : AdbBufferedOutputChannel {

  /**
   * Writes up to [ByteBuffer.remaining] bytes from [buffer] to the underlying channel, updating [ByteBuffer.position] to match the number
   * of bytes written.
   * * Throws [ClosedChannelException] if [close] or [shutdown] were previously called
   * * Throws [AsynchronousCloseException] if [close] is called __while this function is suspended__
   * * Throws [TimeoutException] in case the data cannot be written before the timeout expires.
   * * Throws [Throwable] if [error] was previously called
   */
  override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit)

  /**
   * Wait until all bytes that have been written so far are consumed by the corresponding pipe reader.
   * * Throws [ClosedChannelException] if [close] or [shutdown] were previously called
   * * Throws [AsynchronousCloseException] if [close] is called __while this function is suspended__
   * * Throws [Throwable] if [error] was previously called
   */
  override suspend fun flush()

  /**
   * Similar to [flush] but also signal the corresponding pipe reader that no more data will be coming, i.e. `readXxx` operations on the
   * corresponding [AdbPipedInputChannel] should start returning `-1` after all currently remaining data in the pipe has been read.
   * * Throws [ClosedChannelException] if [close] or [shutdown] were previously called
   * * Throws [AsynchronousCloseException] if [close] is called __while this function is suspended__
   * * Throws [Throwable] if [error] was previously called
   */
  override suspend fun shutdown()

  /**
   * Close this [AdbPipedOutputChannel] synchronously or asynchronously, i.e. all future operations throw [ClosedChannelException] and any
   * pending operation throws [AsynchronousCloseException].
   */
  override fun close()

  /**
   * Signal that `readXxx` operations on the corresponding [AdbPipedInputChannel] should throw [throwable] after all currently remaining
   * data in the pipe has been read.
   *
   * Note: This operation is a no-op if [close] or [shutdown] have been called previously.
   */
  fun error(throwable: Throwable)
}
