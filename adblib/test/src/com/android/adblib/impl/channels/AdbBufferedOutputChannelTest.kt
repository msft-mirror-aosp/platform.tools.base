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

import com.android.adblib.AdbBufferedOutputChannel
import com.android.adblib.AdbOutputChannel
import com.android.adblib.testingutils.CloseablesRule
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.TestingAdbSession
import com.android.adblib.write
import kotlinx.coroutines.delay
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.time.Duration
import java.util.concurrent.TimeUnit

class AdbBufferedOutputChannelTest {

    @JvmField
    @Rule
    val exceptionRule: ExpectedException = ExpectedException.none()

    @JvmField
    @Rule
    val closeables = CloseablesRule()

    private fun <T : AutoCloseable> registerCloseable(item: T): T {
        return closeables.register(item)
    }

    @Test
    fun toStringIsWellDefined(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        val buffer = ByteBuffer.allocate(100).limit(10)
        bufferedChannel.write(buffer)

        // Assert
        Assert.assertEquals("AdbBufferedOutputChannel(bufferedBytes=10, bufferCapacity=1000, closed=false, isShutdown=false)", bufferedChannel.toString())
    }

    @Test
    fun writeIsBufferedIfSmallerThanBufferSize(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        val buffer = ByteBuffer.allocate(100).limit(10)
        bufferedChannel.write(buffer)

        // Assert
        Assert.assertEquals(0, output.writeCallCount)
        Assert.assertEquals(0, output.bytes.size)
        Assert.assertFalse(output.closed)
    }

    @Test
    fun writeIsNotBufferedIfLargeBuffer(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 50)

        // Act
        val buffer = ByteBuffer.allocate(100).limit(10)
        bufferedChannel.write(buffer) // 10 bytes
        buffer.position(0)
        buffer.limit(100)
        bufferedChannel.write(buffer)

        // Assert
        Assert.assertEquals(2, output.writeCallCount)
        Assert.assertEquals(110, output.bytes.size)
        Assert.assertFalse(output.closed)
    }

    @Test
    fun writeCalledMultipleTimesIsBufferedAndBatched(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferSize = 20
        val chunkSize = 7
        val chunkCount = 19
        val totalBytes = chunkSize * chunkCount
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, bufferSize)
        val byteSequence = generateSequence<Byte>(1) { (it + 1).toByte()  }

        // Act
        val buffer = ByteBuffer.allocate(100).limit(chunkSize)
        val byteData = byteSequence.iterator()
        repeat(chunkCount) {
            // Create "chunkSize" bytes buffer with next values from the sequence
            buffer.clear()
            buffer.limit(chunkSize)
            repeat(chunkSize) {
                buffer.put(byteData.next())
            }
            buffer.position(0)

            // Write all "chunkSize" bytes to buffered channel
            while (buffer.hasRemaining()) {
                bufferedChannel.write(buffer)
            }
        }

        // Assert
        Assert.assertEquals(totalBytes / bufferSize, output.writeCallCount)
        Assert.assertFalse(output.closed)
        Assert.assertEquals(byteSequence.take((totalBytes / bufferSize) * bufferSize).toList(), output.bytes)

        // Act
        bufferedChannel.shutdown()
        Assert.assertEquals((totalBytes / bufferSize) + 1, output.writeCallCount)
        Assert.assertFalse(output.closed)
        Assert.assertEquals(byteSequence.take(totalBytes).toList(), output.bytes)
    }

    @Test
    fun writeThrowsAfterClose(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        bufferedChannel.close()
        exceptionRule.expect(ClosedChannelException::class.java)
        bufferedChannel.write(ByteBuffer.allocate(10))

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun writeThrowsAfterShutdown(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        bufferedChannel.shutdown()
        exceptionRule.expect(ClosedChannelException::class.java)
        bufferedChannel.write(ByteBuffer.allocate(10))

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun flushForcesWriteToWrappedOutputChannel(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        val buffer = ByteBuffer.allocate(100).limit(10)
        buffer.put(5)
        buffer.put(6)
        buffer.position(0)
        bufferedChannel.write(buffer)
        bufferedChannel.flush()

        // Assert
        Assert.assertEquals("flush should not call `close`", false, output.closed)
        Assert.assertEquals(1, output.writeCallCount)
        Assert.assertEquals(listOf<Byte>(5, 6, 0, 0, 0, 0, 0, 0, 0, 0), output.bytes)
    }

    @Test
    fun flushCallsFlushOnWrappedOutputChannel(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingBufferedOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        val buffer = ByteBuffer.allocate(100).limit(10)
        buffer.put(5)
        buffer.put(6)
        buffer.position(0)
        bufferedChannel.write(buffer)
        bufferedChannel.flush()

        // Assert
        Assert.assertEquals("flush should not call `close`", false, output.closed)
        Assert.assertEquals(1, output.writeCallCount)
        Assert.assertEquals(1, output.flushCallCount)
        Assert.assertEquals(listOf<Byte>(5, 6, 0, 0, 0, 0, 0, 0, 0, 0), output.bytes)
    }

    @Test
    fun flushThrowsAfterShutdown(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        bufferedChannel.shutdown()
        exceptionRule.expect(ClosedChannelException::class.java)
        bufferedChannel.flush()

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun flushThrowsAfterClose(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        bufferedChannel.close()
        exceptionRule.expect(ClosedChannelException::class.java)
        bufferedChannel.flush()

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun shutdownFlushesToBuffer(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        val buffer = ByteBuffer.allocate(100).limit(10)
        buffer.put(5)
        buffer.put(6)
        buffer.position(0)
        bufferedChannel.write(buffer)
        bufferedChannel.shutdown()

        // Assert
        Assert.assertEquals("shutdown should not call `close`", false, output.closed)
        Assert.assertEquals(1, output.writeCallCount)
        Assert.assertEquals(listOf<Byte>(5, 6, 0, 0, 0, 0, 0, 0, 0, 0), output.bytes)
    }

    @Test
    fun shutdownThrowsAfterShutdown(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        bufferedChannel.shutdown()
        exceptionRule.expect(ClosedChannelException::class.java)
        bufferedChannel.shutdown()

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun shutdownCallsShutdownOnWrappedOutputChannel(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingBufferedOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000, closeOutputChannel = true)

        // Act
        bufferedChannel.shutdown()

        // Assert
        Assert.assertEquals(1, output.shutdownCallCount)
    }

    @Test
    fun shutdownDoesNotCallShutdownOnWrappedOutputChannelIfNotAskedTo(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingBufferedOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000, closeOutputChannel = false)

        // Act
        bufferedChannel.shutdown()

        // Assert
        Assert.assertEquals(0, output.shutdownCallCount)
    }

    @Test
    fun shutdownThrowsAfterClose(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        bufferedChannel.close()
        exceptionRule.expect(ClosedChannelException::class.java)
        bufferedChannel.shutdown()

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun closeDoesNotFlushWrappedOutputChannel(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingBufferedOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000)

        // Act
        val buffer = ByteBuffer.allocate(100).limit(10)
        bufferedChannel.write(buffer)
        bufferedChannel.close()

        // Assert
        Assert.assertEquals(0, output.writeCallCount)
        Assert.assertEquals(0, output.flushCallCount)
        Assert.assertEquals(0, output.shutdownCallCount)
        Assert.assertTrue(output.closed)
    }

    @Test
    fun closeCallsCloseOnOutputChannel(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingBufferedOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000, closeOutputChannel = true)

        // Act
        bufferedChannel.close()

        // Assert
        Assert.assertEquals(1, output.closeCallCount)
    }

    @Test
    fun closeDoesNotCallCloseOnOutputChannelIfNotAskedTo(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingBufferedOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000, closeOutputChannel = false)

        // Act
        bufferedChannel.close()

        // Assert
        Assert.assertEquals(0, output.closeCallCount)
        Assert.assertEquals(0, output.writeCallCount)
        Assert.assertEquals(0, output.flushCallCount)
        Assert.assertEquals(0, output.shutdownCallCount)
    }

    @Test
    fun closeDoesNotThrowAfterShutdown(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000, closeOutputChannel = true)

        // Act
        bufferedChannel.shutdown()
        bufferedChannel.close()

        // Assert
        Assert.assertTrue(output.closed)
    }

    @Test
    fun closeDoesNotThrowAfterClose(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val output = TestingOutputChannel()
        val bufferedChannel = channelFactory.createBufferedOutputChannel(output, 1_000, closeOutputChannel = true)

        // Act
        bufferedChannel.close()
        bufferedChannel.close()

        // Assert
        Assert.assertTrue(output.closed)
    }

    private open class TestingOutputChannel(
        private val writeDelay: Duration? = null
    ) : AdbOutputChannel {

        val bytes = mutableListOf<Byte>()

        var closeCallCount = 0
            private set

        var writeCallCount = 0
            private set

        val closed: Boolean
            get() { return closeCallCount >= 1 }

        override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
            writeCallCount++
            writeDelay?.also {
                delay(it.toMillis())
            }

            while(buffer.remaining() > 0) {
                bytes.add(buffer.get())
            }
        }

        override suspend fun writeExactly(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
            super.writeExactly(buffer, timeout, unit)
        }

        override fun close() {
            closeCallCount++
        }
    }

    private class TestingBufferedOutputChannel : TestingOutputChannel(), AdbBufferedOutputChannel {
        var flushCallCount = 0
            private set
        var shutdownCallCount = 0
            private set

        override suspend fun flush() {
            flushCallCount++
        }

        override suspend fun shutdown() {
            shutdownCallCount++
        }
    }
}
