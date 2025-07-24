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

import com.android.adblib.impl.TimeoutTracker
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * An output channel accepts output bytes and sends them to some sink.
 *
 * Note: This is the equivalent of [java.io.OutputStream] with suspending operations instead
 * of blocking ones.
 */
interface AdbOutputChannel : AutoCloseable {

    /**
     * Closes this output channel and releases any system resources associated with it.
     * A closed channel cannot perform output operations and cannot be reopened.
     * Any currently suspended [writeBuffer] operation is promptly cancelled and throws
     * [AsynchronousCloseException]
     *
     * Note: Implementations should be thread-safe to allow prompt cancellation of suspended
     * operations.
     */
    override fun close()

    /**
     * Writes up to [ByteBuffer.remaining] bytes from [buffer] to the underlying channel, updating
     * [ByteBuffer.position] to match the number of bytes written.
     *
     * If a failure occurs, an [java.io.IOException] is thrown, and the [ByteBuffer] state
     * is undefined (i.e. some bytes may have been written, but not all).
     *
     * * Throws [ClosedChannelException] if [close] was previously called
     * * Throws [AsynchronousCloseException] if [close] is called __while this function is
     * suspended__
     * * Throws [java.io.IOException] if an I/O occurs writing from the underlying resource
     * * Throws [TimeoutException] in case the data cannot be written before the timeout expires.
     */
    suspend fun writeBuffer(
        buffer: ByteBuffer,
        timeout: Long = Long.MAX_VALUE,
        unit: TimeUnit = TimeUnit.MILLISECONDS
    )

    /**
     * Writes all [ByteBuffer.remaining] bytes from [buffer] to the underlying channel.
     *
     * If successful, the buffer position is equal to the buffer limit.
     *
     * If a failure occurs, an [java.io.IOException] is thrown, and the [ByteBuffer] state
     * is undefined (i.e. some bytes may have been written, but not all).
     *
     * * Throws [ClosedChannelException] if [close] was previously called
     * * Throws [AsynchronousCloseException] if [close] is called __while this function is
     * suspended__
     * * Throws [java.io.IOException] if an I/O occurs writing from the underlying resource
     * * Throws [TimeoutException] in case the data cannot be written before the timeout expires.
     */
    suspend fun writeExactly(
        buffer: ByteBuffer,
        timeout: Long = Long.MAX_VALUE,
        unit: TimeUnit = TimeUnit.MILLISECONDS
    ) {
        val tracker = TimeoutTracker.fromTimeout(unit, timeout)
        tracker.throwIfElapsed()

        // This default implementation is suboptimal and can be optimized by implementers
        while (buffer.hasRemaining()) {
            val count = write(buffer, tracker)
            if (count <= 0) {
                throw EOFException("Unexpected end of channel")
            }
        }
    }
}

/**
 * Writes up to [ByteBuffer.remaining] bytes from [buffer] to the underlying channel, updating
 * [ByteBuffer.position] to match the number of bytes written.
 *
 * Returns the number of bytes written on success. The return value is zero if and only if
 * [ByteBuffer.remaining] is zero.
 *
 * If a failure occurs, an [java.io.IOException] is thrown, and the [ByteBuffer] state
 * is undefined (i.e. some bytes may have been written, but not all).
 *
 * Throws a [TimeoutException] in case the data cannot be written before the timeout expires.
 */
suspend inline fun AdbOutputChannel.write(
    buffer: ByteBuffer,
    timeout: Long = Long.MAX_VALUE,
    unit: TimeUnit = TimeUnit.MILLISECONDS
): Int {
    val remainingBefore = buffer.remaining()
    writeBuffer(buffer, timeout, unit)
    return remainingBefore - buffer.remaining()
}

internal suspend fun AdbOutputChannel.write(buffer: ByteBuffer, timeout: TimeoutTracker) : Int {
    return write(buffer, timeout.remainingNanos, TimeUnit.NANOSECONDS)
}

internal suspend fun AdbOutputChannel.writeExactly(buffer: ByteBuffer, timeout: TimeoutTracker) {
    writeExactly(buffer, timeout.remainingNanos, TimeUnit.NANOSECONDS)
}

