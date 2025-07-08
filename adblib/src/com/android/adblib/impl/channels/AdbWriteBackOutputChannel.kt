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
import com.android.adblib.AdbInputChannel
import com.android.adblib.AdbOutputChannel
import com.android.adblib.AdbPipedInputChannel
import com.android.adblib.AdbPipedOutputChannel
import com.android.adblib.AdbSession
import com.android.adblib.adbLogger
import com.android.adblib.read
import com.android.adblib.utils.createChildScope
import com.android.adblib.utils.runAlongOtherScope
import com.android.adblib.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Implementation of an [AdbOutputChannel] that buffers write operations to an in-memory
 * [AdbPipedInputChannel], and uses a "write-back" coroutine to flush the in-memory
 * [AdbPipedInputChannel] contents to [outputChannel].
 */
internal class AdbWriteBackOutputChannel(
    session: AdbSession,
    private val outputChannel: AdbOutputChannel,
    bufferSize: Int,
    private val closeOutputChannel: Boolean
) : AdbBufferedOutputChannel {

    private val logger = adbLogger(session)

    /**
     * The [AdbPipedInputChannel] used to concurrently store write-back from [writeBuffer] operations.
     */
    private val pipe = session.channelFactory.createPipedChannel(bufferSize)

    /**
     * The scope of the write-back coroutine.
     */
    private val thisScope = session.scope.createChildScope(isSupervisor = true)

    /**
     * Keeps track of the # of bytes written to [pipe]
     */
    private val bytesWrittenToPipe = MutableStateFlow(0L)

    /**
     * Keeps track of the # of bytes written to [outputChannel]
     */
    private val bytesWrittenToOutput = MutableStateFlow(0L)

    /**
     * The "Write Back" coroutine helper class
     */
    private val writeBackWorker = WriteBackWorker(session, thisScope, pipe, outputChannel, bytesWrittenToOutput, bufferSize)

    /**
     * Whether [shutdown] has been called, preventing additional calls to [write], [shutdown], [flush]
     */
    private var isShutdown = false

    /**
     * Whether [close] has been called, preventing additional calls to [write], [shutdown], [flush]
     */
    private var isClosed = false

    /**
     * Keep track of write back worker error (if any) so we can rethrow its exception from various
     * public entry points.
     */
    @Volatile
    private var writeBackWorkerException: Throwable? = null

    init {
        writeBackWorker.invokeOnException { throwable ->
            writeBackWorkerException = throwable

            // Ensure that future write to the pipe source fails immediately
            pipe.pipeSource.error(throwable)

            // Ensure that any pending or future call to `flush` or `shutdown` is cancelled
            thisScope.cancel(throwable.toCancellationException())
        }
    }

    /**
     * Write [buffer] asynchronously to the underlying output channel.
     *
     * * Throws [ClosedChannelException] if [close] or [shutdown] were previously called
     * * Throws [AsynchronousCloseException] if [close] is called __while this function is
     * suspended__
     * * Throws [Throwable] if there was a previous error while writing to the underlying output channel
     * * Throws [TimeoutException] in case the data cannot be written before the timeout expires.
     */
    override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        wrapPublicEntryPoint {
            wrapPipeSourceWriteOperation { pipeSource ->
                pipeSource.write(buffer, timeout, unit)
            }
        }
    }

    /**
     * Write [buffer] asynchronously to the underlying output channel.
     *
     * * Throws [ClosedChannelException] if [close] or [shutdown] were previously called
     * * Throws [AsynchronousCloseException] if [close] is called __while this function is
     * suspended__
     * * Throws [Throwable] if there was a previous error while writing to the underlying output channel
     * * Throws [TimeoutException] in case the data cannot be written before the timeout expires.
     */
    override suspend fun writeExactly(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
        wrapPublicEntryPoint {
            wrapPipeSourceWriteOperation { pipeSource ->
                buffer.remaining().also {
                    pipeSource.writeExactly(buffer, timeout, unit)
                }
            }
        }
    }

    /**
     * Waits for the write-back coroutine to process all currently pending bytes.
     *
     * * Throws [ClosedChannelException] if [close] or [shutdown] were previously called
     * * Throws [AsynchronousCloseException] if [close] is called __while this function is
     * suspended__
     * * Throws [Throwable] if there was a previous error while writing to the underlying output channel
     */
    override suspend fun flush() {
        wrapPublicEntryPoint {
            // Note: Use `thisScope` to ensure cancellation if/when `close` is called
            runAlongOtherScope(thisScope) {
                flushImpl()
            }
        }
    }

    /**
     * Waits for the write-back coroutine to process all currently pending bytes, then mark
     * this channel as [isShutdown].
     *
     * * Throws [ClosedChannelException] if [close] or [shutdown] were previously called
     * * Throws [AsynchronousCloseException] if [close] is called __while this function is
     * suspended__
     * * Throws [Throwable] if there was a previous error while writing to the underlying output channel
     */
    override suspend fun shutdown() {
        wrapPublicEntryPoint {
            // Note: Use `thisScope` to ensure cancellation if/when `close` is called
            runAlongOtherScope(thisScope) {
                // Mark channel as "shutdown" so further write/flush calls are rejected
                isShutdown = true

                // First, signal there will be no more data written. This allows the write back
                // coroutine to terminate successfully, but also ensures further `write`
                // operations throw `ClosedChannelException`.
                pipe.pipeSource.shutdown()

                // Then we flush and wait for write back worker to finish
                flushImpl()
                writeBackWorker.await()

                // Finally, we optionally shut down the output channel
                if (closeOutputChannel) {
                    // In case the wrapped output channel is buffered, we shut it down too.
                    (outputChannel as? AdbBufferedOutputChannel)?.shutdown()
                }
            }
        }
    }

    /**
     * Note: [close] is the only method that is allowed be called concurrently, so we
     * need to ensure its behavior is guaranteed if another pending operation
     * (e.g. [writeBuffer] or [flush] or even [shutdown]) is active.
     */
    override fun close() {
        logger.debug { "close: Closing write-back output channel" }

        // Closing the pipe ensures any pending read operation on the pipe is cancelled
        isClosed = true
        pipe.close()

        // Closing the scope ensures any pending write operation on this output channel is cancelled
        thisScope.cancel("${this::class.simpleName} has been closed")

        // Close the underlying output channel if required
        if (closeOutputChannel) {
            outputChannel.close()
        }
    }

    private suspend fun flushImpl() {
        // Wait until the number of bytes sent to us via the `writeXxx` methods is exactly
        // equal to the number of bytes send to the output channel by the `writeBackLoop`
        logger.debug { "flush: waiting for all bytes to be written to output" }
        bytesWrittenToOutput.first { it == bytesWrittenToPipe.value }

        // In case the wrapped output channel is buffered, we flush it too.
        (outputChannel as? AdbBufferedOutputChannel)?.also {
            logger.debug { "flush: flushing output channel" }
            it.flush()
        }
    }

    /**
     * Executes [writeOperation] on [pipe], handling exceptions appropriately
     *
     * Throws [CancellationException] if [close] is called during this operation
     * Throws [ClosedChannelException] if [close] or [shutdown] were previously called.
     * Throws [Throwable] if there was a previous error while writing to the underlying output channel
     */
    private inline fun wrapPipeSourceWriteOperation(writeOperation: (AdbPipedOutputChannel) -> Int): Int {
        // Note: The pipe ensures cancellation if/when the pipe is closed
        return writeOperation(pipe.pipeSource).also { byteCount ->
            logger.verbose { "write: Wrote $byteCount bytes to pipe '$pipe'" }
            bytesWrittenToPipe.update { it + byteCount }
        }
    }

    private inline fun <R> wrapPublicEntryPoint(block: () -> R): R {
        if (isShutdown) {
            throw ClosedChannelException().initCause(
                IOException("${AdbWriteBackOutputChannel::class.java.simpleName} has been shutdown"))
        }

        if (isClosed) {
            throw ClosedChannelException().initCause(
                IOException("${AdbWriteBackOutputChannel::class.java.simpleName} has been closed"))
        }

        return try {
            block()
        } catch (t: Throwable) {
            if (isClosed) {
                throw AsynchronousCloseException().initCause(
                    IOException("${AdbWriteBackOutputChannel::class.java.simpleName} has been closed").initCause(t))
            }

            throw writeBackWorkerException ?: t
        }
    }

    /**
     * Handles the "write-back" coroutine: writes bytes from [input] to [output] as fast
     * as possible.
     */
    private class WriteBackWorker(
        session: AdbSession,
        parentScope: CoroutineScope,
        private val input: AdbInputChannel,
        private val output: AdbOutputChannel,
        private val bytesWritten: MutableStateFlow<Long>,
        bufferSize: Int
    ) {

        private val logger = adbLogger(session)

        /**
         * The [Job] where [writeBackLoop] is launched.
         */
        private val writeBackAsync = parentScope.async {
            // Note: [writeBack] may throw an exception if it fails to write to the output channel,
            // or if the parent `AdbWriteBackOutputChannel` is closed.
            writeBackLoop(bufferSize)
        }

        suspend fun await() {
            writeBackAsync.await()
        }

        fun invokeOnException(handler: (Throwable) -> Unit) {
            writeBackAsync.invokeOnCompletion { throwable ->
                if (throwable != null) {
                    handler.invoke(throwable)
                }
            }
        }

        /**
         * Forward data from [input] to [output] in a loop until an exception is thrown
         * or [input] reaches EOF.
         */
        private suspend fun writeBackLoop(bufferSize: Int) {
            logger.debug { "write-back coroutine starting with bufferSize=$bufferSize" }
            val buffer = ByteBuffer.allocate(bufferSize)
            while (true) {
                buffer.clear() // [position=0, limit=capacity]
                val byteCount = input.read(buffer) // [position=byteCount, limit=capacity]
                logger.verbose { "$byteCount byte(s) read from input '$input'" }
                if (byteCount < 0) {
                    // Reached EOF, we are done
                    break
                }
                buffer.flip() // [position=byteCount, limit=capacity] -> [position=0, limit]
                output.writeExactly(buffer)
                logger.verbose { "$byteCount bytes forwarded to output '$output'" }
                bytesWritten.update { it + byteCount }
            }
        }
    }

    companion object {

        private fun Throwable.toCancellationException(): CancellationException {
            return when {
                this is CancellationException -> this
                else -> CancellationException(this.message).also {
                    it.initCause(this)
                }
            }
        }
    }
}
