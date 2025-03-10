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
package com.android.adblib

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.utils.AdbProtocolUtils
import com.android.adblib.utils.ResizableBuffer
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class AdbOutputChannelWriterTest {

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    fun testMultipleWriteStringCallsAreWrittenDirectlyToChannelWhenAutoFlushIsEnabled(): Unit = runBlockingWithTimeout {
        // Prepare
        val testChannel = TestOutputChannel()
        val writer = AdbOutputChannelWriter(testChannel)

        // Act
        writer.useShutdown {
            writer.writeString("FooBar")
            writer.writeString("Blah")
        }

        // Assert
        Assert.assertEquals("FooBarBlah", testChannel.toString())
        Assert.assertEquals(2, testChannel.writeBufferCount)
        Assert.assertTrue(testChannel.closed)
    }

    @Test
    fun testMultipleWriteStringCallsAreCombinedWhenAutoFlushIsDisabled(): Unit = runBlockingWithTimeout {
        // Prepare
        val testChannel = TestOutputChannel()
        val writer = AdbOutputChannelWriter(testChannel, autoFlush = false)

        // Act
        writer.useShutdown {
            writer.writeString("FooBar")
            writer.writeString("Blah")
        }

        // Assert
        Assert.assertEquals("FooBarBlah", testChannel.toString())
        Assert.assertEquals(1, testChannel.writeBufferCount)
        Assert.assertTrue(testChannel.closed)
    }

    @Test
    fun testWriteStringCallWithLargeStringIsSplitIntoMultipleWritesToChannel(): Unit = runBlockingWithTimeout {
        // Prepare
        val testChannel = TestOutputChannel()
        val writer = AdbOutputChannelWriter(testChannel, bufferCapacity = 50)
        val input = "foobar".repeat(45)

        // Act
        writer.useShutdown {
            writer.writeString(input)
        }

        // Assert
        Assert.assertEquals(input, testChannel.toString())
        Assert.assertEquals(6, testChannel.writeBufferCount)
        Assert.assertTrue(testChannel.closed)
    }

    @Test
    fun testMultipleWriteCharCallsAreWrittenDirectlyToChannelWhenAutoFlushIsEnabled(): Unit = runBlockingWithTimeout {
        // Prepare
        val testChannel = TestOutputChannel()
        val writer = AdbOutputChannelWriter(testChannel, autoFlush = true)

        // Act
        writer.useShutdown {
            writer.writeChar('F')
            writer.writeChar('B')
        }

        // Assert
        Assert.assertEquals("FB", testChannel.toString())
        Assert.assertEquals(2, testChannel.writeBufferCount)
        Assert.assertTrue(testChannel.closed)
    }

    private class TestOutputChannel : AdbOutputChannel {

        val workBuffer = ResizableBuffer()
        var writeBufferCount = 0
        var closed = false

        /**
         * Convert [workBuffer] contents into a string, leaving [workBuffer] unchanged
         */
        override fun toString(): String {
            val buffer = workBuffer.forChannelWrite()
            val outputBuffer = ByteBuffer.allocate(buffer.remaining())
            outputBuffer.put(buffer)
            workBuffer.clear()
            // [0, pos] -> [pos=0, limit]
            outputBuffer.flip()
            workBuffer.appendBytes(outputBuffer)
            // [0, pos] -> [pos=0, limit]
            outputBuffer.flip()

            return String(outputBuffer.array(), AdbProtocolUtils.ADB_CHARSET)
        }

        override suspend fun writeBuffer(buffer: ByteBuffer, timeout: Long, unit: TimeUnit) {
            workBuffer.appendBytes(buffer)
            writeBufferCount++
        }

        override fun close() {
            closed = true
        }
    }
}
