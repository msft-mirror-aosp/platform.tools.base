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

import com.android.adblib.AdbInputChannel
import com.android.adblib.read
import com.android.adblib.skipRemaining
import com.android.adblib.testingutils.CloseablesRule
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.TestingAdbSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.min

class AdbBufferedInputChannelTest {

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
    fun readShouldReadBufferSizeFromUnderlyingChannel(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = TestingInputChannel(length = 1_000, throwOnEOF = false)
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 1_000)

        // Act
        val byteCount = bufferedChannel.read(ByteBuffer.allocate(10))

        // Assert
        Assert.assertEquals(10, byteCount)
        Assert.assertEquals(1000, input.offset)
        Assert.assertEquals(1, input.readCounter)
    }

    @Test
    fun readShouldConsumeAsManyBytesAsPossibleFromTheUnderlyingChannel(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = TestingInputChannel(length = 1_000, throwOnEOF = false)
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 1_000)

        // Act
        val counts = (0 until 11)
            .map {
                val buffer = ByteBuffer.allocate(100)
                bufferedChannel.read(buffer)
            }.toList()

        // Assert
        Assert.assertEquals(
            "There should be only 2 `read` invocations: 1 read of 1_000 bytes, 1 read for EOF",
            2,
            input.readCounter
        )
        Assert.assertEquals(1000, input.offset)
        Assert.assertEquals(11, counts.size)
        repeat(counts.size - 1) { index ->
            Assert.assertEquals(100, counts[index])
        }
        Assert.assertEquals(-1, counts.last())
    }

    @Test
    fun readWithLargeBufferSkipsBuffering(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = TestingInputChannel(length = 1_000, throwOnEOF = false)
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 100)

        // Act
        val count1 = bufferedChannel.read(ByteBuffer.allocate(10))
        val count2 = bufferedChannel.read(ByteBuffer.allocate(90))
        val count3 = bufferedChannel.read(ByteBuffer.allocate(200))

        // Assert
        Assert.assertEquals(10, count1)
        Assert.assertEquals(90, count2)
        Assert.assertEquals(200, count3)
        Assert.assertEquals("There should be only 2 reads on the channel:" +
                                    "one read of 100 bytes (buffer size) then one read of " +
                                    "200 bytes (the large read)", 2, input.readCounter)
    }

    @Test
    fun readShouldWorkWithManyReads(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val inputSize = 100 * 1_024 // 100KB
        val input = TestingInputChannel(length = inputSize, throwOnEOF = false)
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 16)

        // Act:
        // This is a "mini" stress test: we read 10 bytes at a time (as fast as possible)
        // from a buffered input channel that reads 16 bytes at a time.
        val count = bufferedChannel.skipRemaining(bufferSize = 10)

        // Assert
        Assert.assertEquals(inputSize, count)
    }

    @Test
    fun readShouldNotMakeBufferedChannelReadTooMuchFromUnderlyingChannel(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = TestingInputChannel(length = 1_000, throwOnEOF = true)
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 1_000)

        // Act: If buffered channel were to read more than 1_000 bytes, an IOException would be
        // throws from the underlying `TestingInputChannel.read`.
        val counts = (0 until 10)
            .map {
                val buffer = ByteBuffer.allocate(100)
                bufferedChannel.read(buffer)
            }.toList()

        // Assert
        Assert.assertEquals(10, counts.size)
        repeat(counts.size) { index ->
            Assert.assertEquals(100, counts[index])
        }
    }

    @Test
    fun readShouldThrowIfUnderlyingChannelThrows(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = TestingInputChannel(length = 1_000, throwOnEOF = true)
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 1_000)

        // Act
        repeat(10) {
            bufferedChannel.read(ByteBuffer.allocate(100))
        }

        exceptionRule.expect(IOException::class.java)
        exceptionRule.expectMessage("My input channel exception")
        bufferedChannel.read(ByteBuffer.allocate(100))

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun readThrowsAfterClose(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = TestingInputChannel(length = 1_000, throwOnEOF = false)
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 16)

        // Act
        bufferedChannel.close()

        // Assert
        exceptionRule.expect(ClosedChannelException::class.java)
        bufferedChannel.read(ByteBuffer.allocate(10))
    }

    @Test
    fun readExactlyShouldReadBufferSizeFromUnderlyingChannel(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = TestingInputChannel(length = 1_000, throwOnEOF = false)
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 1_000)

        // Act
        bufferedChannel.readExactly(ByteBuffer.allocate(1_000))

        // Assert
        Assert.assertEquals(1000, input.offset)
        Assert.assertEquals(1, input.readCounter)
    }

    @Test
    fun readExactlyThrowsAfterClose(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = TestingInputChannel(length = 1_000, throwOnEOF = false)
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 16)

        // Act
        bufferedChannel.close()

        // Assert
        exceptionRule.expect(ClosedChannelException::class.java)
        bufferedChannel.readExactly(ByteBuffer.allocate(10))
    }

    @Test
    fun closeCancelsPendingInputChannelRead(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = TestingInputChannel(length = 1_000, readDelay = Duration.ofSeconds(10))
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 1_000)

        // Act
        val buffer = ByteBuffer.allocate(100)
        val count = withTimeoutOrNull(100) {
            bufferedChannel.read(buffer)
        }
        bufferedChannel.close()

        // Assert
        Assert.assertNull(count)
        Assert.assertTrue(input.closed)
    }

    @Test
    fun cancellationFromInputChannelIsReportedToReader(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = object: AdbInputChannel {
            override suspend fun readBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
                cancel("Input Channel read is cancelled")
            }

            override fun close() {
            }
        }
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 1_000)

        // Act
        exceptionRule.expect(CancellationException::class.java)
        exceptionRule.expectMessage("Input Channel read is cancelled")
        val buffer = ByteBuffer.allocate(100)
        bufferedChannel.read(buffer)
        ensureActive() // Ensures 'cancel' throws CancellationException

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun closeInputChannelIsTrueByDefault(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = TestingInputChannel(length = 1_000, readDelay = Duration.ofSeconds(10))
        val bufferedChannel = channelFactory.createBufferedInputChannel(input, 1_000)

        // Act
        bufferedChannel.close()

        // Assert
        Assert.assertTrue(input.closed)
    }

    @Test
    fun closeInputChannelCanBeOverriddenToFalse(): Unit = runBlockingWithTimeout {
        // Prepare
        val session = registerCloseable(TestingAdbSession())
        val channelFactory = AdbChannelFactoryImpl(session)
        val input = TestingInputChannel(length = 1_000, readDelay = Duration.ofSeconds(10))
        val bufferedChannel =
            channelFactory.createBufferedInputChannel(input, 1_000, closeInputChannel = false)

        // Act
        bufferedChannel.close()

        // Assert
        Assert.assertFalse(input.closed)
    }

    private class TestingInputChannel(
        val length: Int,
        private val throwOnEOF: Boolean = false,
        private val readDelay: Duration? = null
    ) : AdbInputChannel {

        var closed = false
            private set

        var offset = 0
            private set

        var readCounter = 0
            private set

        override suspend fun readBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
            readCounter++
            readDelay?.also {
                delay(it.toMillis())
            }
            when {
                offset < length -> {
                    val byteCount = min(buffer.remaining(), length - offset)
                    repeat(byteCount) {
                        buffer.put(1)
                        offset++
                    }
                }

                else -> {
                    if (throwOnEOF) {
                        throw IOException("My input channel exception")
                    }
                }
            }
        }

        override fun close() {
            closed = true
        }
    }
}
