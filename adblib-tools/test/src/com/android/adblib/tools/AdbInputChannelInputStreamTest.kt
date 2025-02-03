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
package com.android.adblib.tools

import com.android.adblib.ByteBufferAdbInputChannel
import org.junit.Test
import com.android.adblib.EmptyAdbInputChannel
import com.android.adblib.readText
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import org.junit.Assert
import org.junit.Assert.assertArrayEquals
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.UUID

class AdbInputChannelInputStreamTest {

    @Test
    fun inputStream_emptyChannel_ReadsNothing() = runBlockingWithTimeout {
        // Prepare
        val bufferSize = 12
        val expected = ByteArray(0)
        val inputChannel = EmptyAdbInputChannel()

        // Act
        val actual = AdbInputChannelInputStream(inputChannel, bufferSize).readAllBytes()

        // Assert
        assertArrayEquals(expected, actual)
    }

    @Test
    fun inputStream_singleRead_ReadsAll() = runBlockingWithTimeout {
        // Prepare
        val randomString = UUID.randomUUID().toString()
        val expected = randomString.toByteArray()
        val bufferSize = randomString.length
        val inputBuffer = ByteBuffer.wrap(expected)
        val inputChannel = ByteBufferAdbInputChannel(inputBuffer)

        // Act
        val actual = AdbInputChannelInputStream(inputChannel, bufferSize).readAllBytes()

        // Assert
        assertArrayEquals(expected, actual)
    }

    @Test
    fun inputStream_multipleReads_ReadsAll() = runBlockingWithTimeout {
        // Prepare
        val randomString = UUID.randomUUID().toString()
        val bufferSize = randomString.length
        val expected = randomString.repeat(3).toByteArray()
        val inputBuffer = ByteBuffer.wrap(expected)
        val inputChannel = ByteBufferAdbInputChannel(inputBuffer)

        // Act
        val actual = AdbInputChannelInputStream(inputChannel, bufferSize).readAllBytes()

        // Assert
        assertArrayEquals(expected, actual)
    }

    @Test
    fun inputStream_closesUnderlyingChannel() = runBlockingWithTimeout {
        // Prepare
        val randomString = UUID.randomUUID().toString().repeat(3)
        val bufferSize = randomString.length
        val expected = randomString.toByteArray()
        val inputBuffer = ByteBuffer.wrap(expected)
        val inputChannel = ByteBufferAdbInputChannel(inputBuffer)

        // Act
        AdbInputChannelInputStream(inputChannel, bufferSize).close()

        try {
            inputChannel.readText(12)
            Assert.fail("Fail: Channel should be closed")
        } catch (_: ClosedChannelException) {
        }
    }
}
