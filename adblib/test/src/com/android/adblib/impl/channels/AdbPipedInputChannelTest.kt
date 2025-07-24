/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.adblib.AdbOutputChannel
import com.android.adblib.read
import com.android.adblib.testingutils.CloseablesRule
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.TestingAdbSession
import com.android.adblib.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AdbPipedInputChannelTest {

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @JvmField
    @Rule
    val closeables = CloseablesRule()

    private fun <T : AutoCloseable> registerCloseable(item: T): T {
        return closeables.register(item)
    }

    @Test
    fun readReceivesBytesFromPipeSourceWrite(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)
        val chunkCount = 100
        val chunkSize = 15
        val dataBytes = generateSequence<Byte>(1) { (it + 1).toByte() }
        val dataBytesIterator = dataBytes.iterator()

        // Act
        val bytesRead = mutableListOf<Byte>()
        launch {
            while (true) {
                val buffer = ByteBuffer.allocate(chunkSize / 2)
                if (pipedChannel.read(buffer) < 0) {
                    break
                }
                buffer.flip()
                while(buffer.hasRemaining()) {
                    bytesRead.add(buffer.get())
                }
            }
        }
        repeat(chunkCount) {
            val buffer = ByteBuffer.allocate(chunkSize)
            while(buffer.hasRemaining()) {
                buffer.put(dataBytesIterator.next())
            }
            buffer.flip()
            pipedChannel.pipeSource.writeExactly(buffer)
        }
        pipedChannel.pipeSource.shutdown()

        // Assert
        Assert.assertEquals(chunkCount * chunkSize, bytesRead.size)
        Assert.assertEquals(dataBytes.take(bytesRead.size).toList(), bytesRead)
    }

    @Test
    fun readWithTimeoutThrowsTimeoutException(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act
        exceptionRule.expect(TimeoutException::class.java)
        pipedChannel.read(ByteBuffer.allocate(10), 10, TimeUnit.MILLISECONDS)

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun readReceivesEOFAfterPipeSourceShutdown(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act
        pipedChannel.pipeSource.shutdown()
        val byteCount = pipedChannel.read(ByteBuffer.allocate(10))

        // Assert
        Assert.assertEquals(-1, byteCount)
    }

    @Test
    fun readThrowsAfterClose(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act
        pipedChannel.close()
        val buffer = ByteBuffer.allocate(10)
        exceptionRule.expect(ClosedChannelException::class.java)
        pipedChannel.read(buffer)

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun readThrowsWhenCloseIsCalledDuringPendingRead(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act
        val readAboutToStart = CompletableDeferred<Unit>()
        launch {
            readAboutToStart.await()
            // Give a chance for "read" to actually start. Note that there is a slight change
            // this test may be flaky: if `close` is called before `read` starts, the exception
            // thrown would be `ClosedChannelException` instead of `AsynchronousCloseException`
            delay(20)
            pipedChannel.close()
        }
        exceptionRule.expect(AsynchronousCloseException::class.java)
        readAboutToStart.complete(Unit)
        pipedChannel.read(ByteBuffer.allocate(10))

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun pipeSourceWriteThrowsAfterClose(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act
        pipedChannel.close()
        exceptionRule.expect(ClosedChannelException::class.java)
        pipedChannel.pipeSource.write(ByteBuffer.allocate(10))

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun pipeSourceWriteThrowsAfterShutdown(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act
        pipedChannel.pipeSource.shutdown()
        exceptionRule.expect(ClosedChannelException::class.java)
        pipedChannel.pipeSource.write(ByteBuffer.allocate(10))

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun pipeSourceWriteWithTimeoutThrowsTimeoutException(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act: fill pipe, then write with a timeout
        pipedChannel.pipeSource.writeNBytes(count = 15, times = 1)
        exceptionRule.expect(TimeoutException::class.java)
        pipedChannel.pipeSource.write(ByteBuffer.allocate(10), 10, TimeUnit.MILLISECONDS)

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun pipeSourceWriteIsCancelledWhenCloseIsCalled(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(bufferSize = 10)

        // Act
        val writeAboutToStart = CompletableDeferred<Unit>()
        launch {
            writeAboutToStart.await()
            // Give a chance for `write` to actually start. Note that there is a slight change
            // this test may be flaky: if `close` is called before `write` starts, the exception
            // thrown would be `ClosedChannelException` instead of `AsynchronousCloseException`
            delay(20)
            pipedChannel.close()
        }
        // Won't block
        pipedChannel.pipeSource.writeExactly(ByteBuffer.allocate(10))

        exceptionRule.expect(AsynchronousCloseException::class.java)
        writeAboutToStart.complete(Unit)
        pipedChannel.pipeSource.writeExactly(ByteBuffer.allocate(5))

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun pipeSourceShutdownIsTreatedAsEndOfFile(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act
        val deferredCount = async {
            var total = 0
            val buffer = ByteBuffer.allocate(10)
            while(true) {
                buffer.clear()
                val count = pipedChannel.read(buffer)
                if (count < 0) {
                    break
                }
                total += count
            }
            total
        }

        pipedChannel.pipeSource.writeNBytes(count = 100, times = 15)
        pipedChannel.pipeSource.shutdown() // Sends EOF
        val readCount = deferredCount.await()

        // Assert
        Assert.assertEquals(1_500, readCount)
    }

    @Test
    fun pipeSourceShutdownSuspendsUntilAllBytesReadAndPreventsMoreWrites(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act: Ensure `flush` waits until a `read` consumes all bytes
        val writeDone = CompletableDeferred<Unit>()
        val readyForShutdown = CompletableDeferred<Unit>()
        val readyForRead = CompletableDeferred<Unit>()
        val asyncWrite = async {
            pipedChannel.pipeSource.writeExactly(ByteBuffer.allocate(10))
            writeDone.complete(Unit)
            readyForShutdown.await()
            pipedChannel.pipeSource.shutdown()
        }

        val asyncRead = async {
            readyForRead.await()
            pipedChannel.readExactly(ByteBuffer.allocate(5))
            pipedChannel.readExactly(ByteBuffer.allocate(5))
        }

        writeDone.await()
        readyForShutdown.complete(Unit)
        delay(10)
        val asyncWriteCompletedEarly = asyncWrite.isCompleted
        readyForRead.complete(Unit)
        asyncRead.await()
        asyncWrite.await()

        // Assert
        Assert.assertFalse(asyncWriteCompletedEarly)
        exceptionRule.expect(ClosedChannelException::class.java)
        pipedChannel.pipeSource.writeExactly(ByteBuffer.allocate(5))
    }

    @Test
    fun pipeSourceShutdownMakesPendingReadReceiveEOF(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act
        val readAboutToStart = CompletableDeferred<Unit>()
        launch {
            readAboutToStart.await()
            // Give a chance for "read" to actually start.
            delay(20)
            pipedChannel.pipeSource.shutdown()
        }
        readAboutToStart.complete(Unit)
        val byteCount = pipedChannel.read(ByteBuffer.allocate(10))

        // Assert
        Assert.assertEquals(-1, byteCount)
    }

    @Test
    fun pipeSourceCloseMakesPendingReadReceiveEOF(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act
        val readAboutToStart = CompletableDeferred<Unit>()
        launch {
            readAboutToStart.await()
            // Give a chance for "read" to actually start.
            delay(20)
            pipedChannel.pipeSource.close()
        }
        readAboutToStart.complete(Unit)
        val byteCount = pipedChannel.read(ByteBuffer.allocate(10))

        // Assert
        Assert.assertEquals(-1, byteCount)
    }

    @Test
    fun pipeSourceFlushSuspendsUntilAllBytesAreRead(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act: Ensure `flush` waits until a `read` consumes all bytes
        val writeDone = CompletableDeferred<Unit>()
        val readyForFlush = CompletableDeferred<Unit>()
        val readyForRead = CompletableDeferred<Unit>()
        val asyncWrite = async {
            pipedChannel.pipeSource.writeExactly(ByteBuffer.allocate(10))
            writeDone.complete(Unit)
            readyForFlush.await()
            pipedChannel.pipeSource.flush()
        }

        val asyncRead = async {
            readyForRead.await()
            pipedChannel.readExactly(ByteBuffer.allocate(5))
            pipedChannel.readExactly(ByteBuffer.allocate(5))
        }

        writeDone.await()
        readyForFlush.complete(Unit)
        delay(10)
        val asyncWriteCompletedEarly = asyncWrite.isCompleted
        readyForRead.complete(Unit)
        asyncRead.await()
        asyncWrite.await()

        // Assert
        Assert.assertFalse(asyncWriteCompletedEarly)
    }

    @Test
    fun pipeSourceErrorMakesReadThrowThePipeSourceException(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act
        val deferredResult = async {
            pipedChannel.read(ByteBuffer.allocate(10))
        }
        pipedChannel.pipeSource.error(IOException("My Error"))

        // Assert
        exceptionRule.expect(IOException::class.java)
        exceptionRule.expectMessage("My Error")
        deferredResult.await()
    }

    @Test
    fun pipeSourceErrorMaksReadThrowsOnlyAfterBufferedBytesAreRead(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel = channelFactory.createPipedChannel(15)

        // Act
        val deferredResult = async {
            var total = 0
            val buffer = ByteBuffer.allocate(10)
            while(true) {
                buffer.clear()
                val count = try {
                    pipedChannel.read(buffer)
                } catch (t: Throwable) {
                    return@async Pair(total, t)
                }
                total += count
            }
            throw IllegalStateException()
        }

        pipedChannel.pipeSource.writeNBytes(count = 100, times = 15)
        pipedChannel.pipeSource.error(IOException("My Error"))
        val (byteCount, throwable) = deferredResult.await()

        // Assert
        Assert.assertEquals(1500, byteCount)
        exceptionRule.expect(IOException::class.java)
        exceptionRule.expectMessage("My Error")
        throw throwable
    }

    @Test
    fun cancellationOfParentCoroutineCancelsPendingReads(): Unit = runBlockingWithTimeout {
        // Prepare
        val channelFactory = createChannelFactory()
        val pipedChannel1 = channelFactory.createPipedChannel(15)
        val pipedChannel2 = channelFactory.createPipedChannel(15)

        // Act
        val readStarted1 = CompletableDeferred<Unit>()
        val readStarted2 = CompletableDeferred<Unit>()
        val job = async {
            launch {
                readStarted1.complete(Unit)
                // Blocked until cancellation
                pipedChannel1.read(ByteBuffer.allocate(10))
            }
            launch {
                readStarted2.complete(Unit)
                // Blocked until cancellation
                pipedChannel2.read(ByteBuffer.allocate(10))
            }
        }

        awaitAll(readStarted1, readStarted2)
        job.cancel(CancellationException("My Cancellation"))

        exceptionRule.expect(CancellationException::class.java)
        job.await()

        // Assert
        Assert.fail("Should not reach")
    }

    private fun createChannelFactory(): AdbChannelFactoryImpl {
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        return channelFactory
    }

    private suspend fun AdbOutputChannel.writeNBytes(count: Int, times: Int) {
        val buffer = ByteBuffer.allocate(count)
        repeat(times) {
            buffer.clear()
            repeat(count) {
                buffer.put((count % 255).toByte())
            }
            buffer.flip()
            writeExactly(buffer)
        }
    }
}
