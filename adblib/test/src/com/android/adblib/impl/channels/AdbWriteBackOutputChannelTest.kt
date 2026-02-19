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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException

class AdbWriteBackOutputChannelTest {

  @JvmField @Rule var exceptionRule: ExpectedException = ExpectedException.none()

  @JvmField @Rule val closeables = CloseablesRule()

  private fun <T : AutoCloseable> registerCloseable(item: T): T {
    return closeables.register(item)
  }

  @Test
  fun writeShouldWriteToWrappedOutputChannel(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output = TestAdbBufferedOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 1_000)
    val dataBytes = generateSequence<Byte>(1) { (it + 1).toByte() }
    val dataBytesIterator = dataBytes.iterator()

    // Act
    writeBackChannel.write(createBuffer(dataBytesIterator, 100))
    writeBackChannel.shutdown()
    writeBackChannel.close()

    // Assert: Check there was at most 10 writes (maybe less if we write faster than
    // the write back coroutine can work), but we have 1_000 bytes at the end.
    Assert.assertEquals(1, output.writeBufferCallCount)
    Assert.assertEquals(1, output.shutdownCallCount)
    Assert.assertEquals(1, output.flushCallCount)
    Assert.assertEquals(100, output.length)
    Assert.assertEquals(dataBytes.take(100).toList(), output.bytes)
    Assert.assertTrue(output.closed)
  }

  @Test
  fun writeMultipleTimesShouldWriteToWrappedOutputChannel(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output = TestAdbBufferedOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 1_000)
    val dataBytes = generateSequence<Byte>(1) { (it + 1).toByte() }
    val dataBytesIterator = dataBytes.iterator()

    // Act
    repeat(10) { writeBackChannel.write(createBuffer(dataBytesIterator, 100)) }
    writeBackChannel.shutdown()
    writeBackChannel.close()

    // Assert: Check there was at most 10 writes (maybe less if we write faster than
    // the write back coroutine can work), but we have 1_000 bytes at the end.
    Assert.assertTrue(output.writeBufferCallCount <= 10)
    Assert.assertEquals(1, output.shutdownCallCount)
    Assert.assertEquals(1, output.flushCallCount)
    Assert.assertEquals(1_000, output.length)
    Assert.assertEquals(dataBytes.take(1_000).toList(), output.bytes)
    Assert.assertTrue(output.closed)
  }

  @Test
  fun writeMultipleTimesShouldEventuallyTimeoutIfUnderlyingChannelIsSlow(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output =
      object : TestAdbBufferedOutputChannel() {
        override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
          delay(5_000)
        }
      }
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 20)

    // Act
    exceptionRule.expect(TimeoutException::class.java)
    repeat(10) { writeBackChannel.write(ByteBuffer.allocate(10), 20, TimeUnit.MILLISECONDS) }

    // Assert
    Assert.fail("Should not reach")
  }

  @Test
  fun writeWithLargeBufferShouldSkipPipe(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output = TestAdbBufferedOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 100)
    val dataBytes = generateSequence<Byte>(1) { (it + 1).toByte() }
    val dataBytesIterator = dataBytes.iterator()

    // Act
    writeBackChannel.write(createBuffer(dataBytesIterator, 100))
    writeBackChannel.write(createBuffer(dataBytesIterator, 2_000))
    writeBackChannel.write(createBuffer(dataBytesIterator, 100))
    writeBackChannel.shutdown()
    writeBackChannel.close()

    // Assert: Check there was at most 10 writes (maybe less if we write faster than
    // the write back coroutine can work), but we have 1_000 bytes at the end.
    Assert.assertEquals(
      "There should be exactly 3 write operations to the output channel: " +
        "one write operation of 100 bytes before the large buffer write, " +
        "one large write operation of 2_000 bytes, " +
        "one last write operation of 100 bytes after the large buffer write.",
      3,
      output.writeBufferCallCount,
    )
    Assert.assertEquals(1, output.shutdownCallCount)
    Assert.assertEquals(1, output.flushCallCount)
    Assert.assertEquals(2_200, output.length)
    Assert.assertEquals(dataBytes.take(2_200).toList(), output.bytes)
    Assert.assertTrue(output.closed)
  }

  @Test
  fun writeWithLargeBufferShouldTimeoutIfUnderlyingChannelIsSlow(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output =
      object : TestAdbBufferedOutputChannel() {
        override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
          delay(5_000)
        }
      }
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 5)

    // Act
    exceptionRule.expect(TimeoutException::class.java)
    writeBackChannel.write(ByteBuffer.allocate(10), 20, TimeUnit.MILLISECONDS)

    // Assert
    Assert.fail("Should not reach")
  }

  @Test
  fun writeRethrowsUnderlyingChannelException(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output =
      object : TestAdbOutputChannel() {
        override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
          if (writeBufferCallCount == 3) {
            throw IOException("Fake I/O error")
          }
          super.writeBuffer(buffer, timeout, unit)
        }
      }
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 1_000)

    // Act: If we keep writing to the write back channel, we should eventually see
    // the exception thrown by the underlying output channel it writes to.
    exceptionRule.expect(IOException::class.java)
    exceptionRule.expectMessage("Fake I/O error")
    repeat(1_000) {
      writeBackChannel.write(ByteBuffer.allocate(100))
      delay(10)
    }

    // Assert
    Assert.fail("Should not reach")
  }

  @Test
  fun writeThrowsAfterShutdown(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val testOutputChannel = TestAdbBufferedOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(testOutputChannel, 1_000)

    // Act
    val buffer = ByteBuffer.allocate(10)
    writeBackChannel.write(buffer)
    writeBackChannel.shutdown()

    exceptionRule.expect(ClosedChannelException::class.java)
    buffer.clear()
    writeBackChannel.write(buffer)

    // Assert
    Assert.fail("Should not reach")
  }

  @Test
  fun writeThrowsAfterClose(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val testOutputChannel = TestAdbBufferedOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(testOutputChannel, 1_000)

    // Act
    val buffer = ByteBuffer.allocate(10)
    writeBackChannel.write(buffer)
    writeBackChannel.close()

    exceptionRule.expect(ClosedChannelException::class.java)
    buffer.clear()
    writeBackChannel.write(buffer)

    // Assert
    Assert.fail("Should not reach")
  }

  @Test
  fun writeExactlyShouldWriteToWrappedOutputChannel(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output = TestAdbBufferedOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 1_000)
    val dataBytes = generateSequence<Byte>(1) { (it + 1).toByte() }
    val dataBytesIterator = dataBytes.iterator()

    // Act
    writeBackChannel.writeExactly(createBuffer(dataBytesIterator, 100))
    writeBackChannel.shutdown()
    writeBackChannel.close()

    // Assert: Check there was at most 10 writes (maybe less if we write faster than
    // the write back coroutine can work), but we have 1_000 bytes at the end.
    Assert.assertEquals(1, output.writeBufferCallCount)
    Assert.assertEquals(1, output.shutdownCallCount)
    Assert.assertEquals(1, output.flushCallCount)
    Assert.assertEquals(100, output.length)
    Assert.assertEquals(dataBytes.take(100).toList(), output.bytes)
    Assert.assertTrue(output.closed)
  }

  @Test
  fun writeExactlyMultipleTimesShouldWriteToWrappedOutputChannel(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output = TestAdbBufferedOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 1_000)
    val dataBytes = generateSequence<Byte>(1) { (it + 1).toByte() }
    val dataBytesIterator = dataBytes.iterator()

    // Act
    repeat(10) { writeBackChannel.writeExactly(createBuffer(dataBytesIterator, 100)) }
    writeBackChannel.shutdown()
    writeBackChannel.close()

    // Assert: Check there was at most 10 writes (maybe less if we write faster than
    // the write back coroutine can work), but we have 1_000 bytes at the end.
    Assert.assertTrue(output.writeBufferCallCount <= 10)
    Assert.assertEquals(1, output.shutdownCallCount)
    Assert.assertEquals(1, output.flushCallCount)
    Assert.assertEquals(1_000, output.length)
    Assert.assertEquals(dataBytes.take(1_000).toList(), output.bytes)
    Assert.assertTrue(output.closed)
  }

  @Test
  fun writeExactlyThrowsAfterShutdown(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val testOutputChannel = TestAdbBufferedOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(testOutputChannel, 1_000)

    // Act
    writeBackChannel.write(ByteBuffer.allocate(10))
    writeBackChannel.shutdown()

    exceptionRule.expect(ClosedChannelException::class.java)
    writeBackChannel.writeExactly(ByteBuffer.allocate(10))

    // Assert
    Assert.fail("Should not reach")
  }

  @Test
  fun writeExactlyThrowsAfterClose(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val testOutputChannel = TestAdbBufferedOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(testOutputChannel, 1_000)

    // Act
    writeBackChannel.write(ByteBuffer.allocate(10))
    writeBackChannel.close()

    exceptionRule.expect(ClosedChannelException::class.java)
    writeBackChannel.writeExactly(ByteBuffer.allocate(10))

    // Assert
    Assert.fail("Should not reach")
  }

  @Test
  fun flushWritesAllOutputToWrappedOutputChannel(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val testOutputChannel = TestAdbOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(testOutputChannel, 1_000)
    val dataBytes = generateSequence<Byte>(1) { (it + 1).toByte() }
    val chunkCount = 50
    val chunkSize = 5
    val dataBytesIterator = dataBytes.iterator()

    // Act: Write as fast as possible to the "write back channel" so that it cannot keep up
    // with forwarding writes to our "testOutputChannel", then call "flush()" and verify all
    // bytes were successfully written to our "testOutputChannel".
    repeat(chunkCount) {
      val buffer = ByteBuffer.allocate(chunkSize)
      val bytesChunk = dataBytesIterator.asSequence().take(chunkSize)
      buffer.put(bytesChunk.toList().toByteArray())
      buffer.flip()
      writeBackChannel.writeExactly(buffer)
    }
    writeBackChannel.flush()

    // Assert
    Assert.assertEquals(chunkCount * chunkSize, testOutputChannel.length)
    Assert.assertFalse(testOutputChannel.closed)
    Assert.assertEquals(chunkCount * chunkSize, testOutputChannel.length)
    Assert.assertEquals(dataBytes.take(chunkCount * chunkSize).toList(), testOutputChannel.bytes)
  }

  @Test
  fun flushCallsFlushOnWrappedOutputChannel(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val testOutputChannel = TestAdbBufferedOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(testOutputChannel, 1_000)
    val dataBytes = generateSequence<Byte>(1) { (it + 1).toByte() }
    val chunkCount = 50
    val chunkSize = 5
    val dataBytesIterator = dataBytes.iterator()

    // Act: Write as fast as possible to the "write back channel" so that it cannot keep up
    // with forwarding writes to our "testOutputChannel", then call "flush()" and verify all
    // bytes were successfully written to our "testOutputChannel".
    repeat(chunkCount) {
      val buffer = ByteBuffer.allocate(chunkSize)
      val bytesChunk = dataBytesIterator.asSequence().take(chunkSize)
      buffer.put(bytesChunk.toList().toByteArray())
      buffer.flip()
      writeBackChannel.writeExactly(buffer)
    }
    writeBackChannel.flush()

    // Assert
    Assert.assertEquals(chunkCount * chunkSize, testOutputChannel.length)
    Assert.assertFalse(testOutputChannel.closed)
    Assert.assertEquals(1, testOutputChannel.flushCallCount)
    Assert.assertEquals(0, testOutputChannel.shutdownCallCount)
    Assert.assertEquals(chunkCount * chunkSize, testOutputChannel.length)
    Assert.assertEquals(dataBytes.take(chunkCount * chunkSize).toList(), testOutputChannel.bytes)
  }

  @Test
  fun flushThrowsAfterShutdown(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val testOutputChannel = TestAdbOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(testOutputChannel, 1_000)

    // Act
    writeBackChannel.writeExactly(ByteBuffer.allocate(10))
    writeBackChannel.shutdown()

    // Assert
    exceptionRule.expect(ClosedChannelException::class.java)
    writeBackChannel.flush()
  }

  @Test
  fun flushThrowsAfterClose(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val testOutputChannel = TestAdbOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(testOutputChannel, 1_000)

    // Act
    writeBackChannel.writeExactly(ByteBuffer.allocate(10))
    writeBackChannel.close()

    // Assert
    exceptionRule.expect(ClosedChannelException::class.java)
    writeBackChannel.flush()
  }

  @Test
  fun flushRethrowsUnderlyingChannelException(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output =
      object : TestAdbOutputChannel() {
        override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
          throw IOException("Fake I/O error")
        }
      }
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 1_000)

    // Act: If we keep writing to the write back channel, we should eventually see
    // the exception thrown by the underlying output channel it writes to.
    writeBackChannel.write(ByteBuffer.allocate(100))
    exceptionRule.expect(IOException::class.java)
    exceptionRule.expectMessage("Fake I/O error")
    writeBackChannel.flush()

    // Assert
    Assert.fail("Should not reach")
  }

  @Test
  fun closeCancelsSlowWrite(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val testOutputChannelWriteStarted = CompletableDeferred<Unit>()
    val testOutputChannelWriteCancellation = CompletableDeferred<Throwable>()
    val testOutputChannel =
      object : TestAdbBufferedOutputChannel() {
        override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
          runCatching {
              testOutputChannelWriteStarted.complete(Unit)
              super.writeBuffer(buffer, timeout, unit)
              // Simulate slow write
              delay(100_000)
            }
            .onFailure { throwable ->
              // Capture the cancellation
              testOutputChannelWriteCancellation.complete(throwable)
              throw throwable
            }
        }
      }
    val writeBackChannel = channelFactory.createWriteBackChannel(testOutputChannel, 1_000)

    // Act: Write then close write back channel. This should cancel the slow write on the
    // `testOutputChannel`.
    val buffer = ByteBuffer.allocate(10)
    writeBackChannel.write(buffer)
    testOutputChannelWriteStarted.await()
    writeBackChannel.close()

    // Assert
    val throwable = testOutputChannelWriteCancellation.await()
    Assert.assertTrue(throwable is CancellationException)
  }

  @Test
  fun closeDoesNotThrowIfNotShutDown(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output = TestAdbOutputChannel()
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 1_000)

    // Act
    val buffer = ByteBuffer.allocate(10)
    writeBackChannel.write(buffer)
    writeBackChannel.close()

    // Assert
    Assert.assertTrue(output.closed)
  }

  @Test
  fun shutdownRethrowsUnderlyingChannelException(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output =
      object : TestAdbOutputChannel() {
        override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
          throw IOException("Fake I/O error")
        }
      }
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 1_000)

    // Act: If we keep writing to the write back channel, we should eventually see
    // the exception thrown by the underlying output channel it writes to.
    writeBackChannel.write(ByteBuffer.allocate(100))
    exceptionRule.expect(IOException::class.java)
    exceptionRule.expectMessage("Fake I/O error")
    writeBackChannel.shutdown()

    // Assert
    Assert.fail("Should not reach")
  }

  @Test
  fun writeBackRethrowsCancellationException(): Unit = runBlockingWithTimeout {
    // Prepare
    val session = registerCloseable(TestingAdbSession())
    val channelFactory = AdbChannelFactoryImpl(session)
    val output =
      object : TestAdbOutputChannel() {
        override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
          if (writeBufferCallCount == 3) {
            cancel("Fake cancellation error")
          }
          super.writeBuffer(buffer, timeout, unit)
        }
      }
    val writeBackChannel = channelFactory.createWriteBackChannel(output, 1_000)

    // Act
    exceptionRule.expect(CancellationException::class.java)
    exceptionRule.expectMessage("Fake cancellation error")
    repeat(10) {
      val buffer = ByteBuffer.allocate(100)
      writeBackChannel.write(buffer)
      delay(10)
    }
    writeBackChannel.shutdown()
    writeBackChannel.close()

    // Assert
    Assert.fail("Should not reach")
  }

  private fun createBuffer(dataBytesIterator: Iterator<Byte>, count: Int): ByteBuffer {
    val buffer = ByteBuffer.allocate(count)
    while (buffer.hasRemaining()) {
      buffer.put(dataBytesIterator.next())
    }
    return buffer.flip()
  }

  private open class TestAdbOutputChannel : AdbOutputChannel {

    var closedCallCount = 0

    val length: Int
      get() = bytes.size

    val closed: Boolean
      get() = closedCallCount > 0

    val bytes = mutableListOf<Byte>()

    var writeBufferCallCount = 0

    override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
      writeBufferCallCount++
      while (buffer.hasRemaining()) {
        bytes.add(buffer.get())
      }
    }

    override fun close() {
      closedCallCount++
    }
  }

  private open class TestAdbBufferedOutputChannel : TestAdbOutputChannel(), AdbBufferedOutputChannel {
    var flushCallCount = 0
    var shutdownCallCount = 0

    override suspend fun flush() {
      flushCallCount++
    }

    override suspend fun shutdown() {
      shutdownCallCount++
    }
  }
}
