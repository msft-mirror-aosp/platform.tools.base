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

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.useShutdown
import com.android.adblib.utils.ResizableBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.MalformedInputException
import java.nio.charset.UnmappableCharacterException

class SuspendingCharacterEncoderTest {

    @Suppress("DEPRECATION")
    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    fun asciiEncodingWorks(): Unit = runBlockingWithTimeout {
        // Prepare
        val testEncoder = TestEncoder(Charsets.US_ASCII)

        // Act
        testEncoder.encoder.useShutdown { encoder ->
            encoder.encode("foo")
            encoder.encode("bar")
        }

        // Assert
        assertArrayEquals(
            byteArrayOf(
                ASCII_LOWER_F, ASCII_LOWER_O, ASCII_LOWER_O,
                ASCII_LOWER_B, ASCII_LOWER_A, ASCII_LOWER_R
            ), testEncoder.toByteArray()
        )
    }

    @Test
    fun asciiEncodingThrowsOnInvalidCharacter(): Unit = runBlockingWithTimeout {
        // Prepare
        val testEncoder = TestEncoder(Charsets.US_ASCII, bufferCapacity = 16)

        // Act
        exceptionRule.expect(UnmappableCharacterException::class.java)
        testEncoder.encoder.useShutdown { encoder ->
            encoder.encode(UTF16_SURROGATE_PAIR)
        }
    }

    @Test
    fun asciiEncodingUsesReplacementOnInvalidCharacter(): Unit = runBlockingWithTimeout {
        // Prepare
        val testEncoder = TestEncoder(
            Charsets.US_ASCII,
            bufferCapacity = 16,
            throwOnMalformed = false
        )

        // Act
        testEncoder.encoder.useShutdown { encoder ->
            encoder.encode(UTF16_SURROGATE_PAIR)
        }

        // Assert
        assertEquals("?", testEncoder.toString())
    }

    @Test
    fun asciiEncodingUsesCustomReplacementString(): Unit = runBlockingWithTimeout {
        // Prepare
        val testEncoder = TestEncoder(
            Charsets.US_ASCII,
            bufferCapacity = 16,
            throwOnMalformed = false,
            replacement = byteArrayOf('f'.code.toByte())
        )

        // Act
        testEncoder.encoder.useShutdown { encoder ->
            encoder.encode(UTF16_SURROGATE_PAIR)
        }

        // Assert
        assertEquals("f", testEncoder.toString())
    }

    @Test
    fun utf8EncodingUsesCustomReplacementString(): Unit = runBlockingWithTimeout {
        // Prepare
        val testEncoder = TestEncoder(
            Charsets.UTF_8,
            bufferCapacity = 16,
            throwOnMalformed = false,
            replacement = UTF16_REPLACEMENT_CHARACTER.toByteArray(Charsets.UTF_8)
        )

        // Act
        testEncoder.encoder.useShutdown { encoder ->
            // The example uses \uD83D which is a high surrogate, i.e. it is missing the low
            // surrogate, so encoding will fail.
            encoder.encode("\uD83Dff")
        }

        // Assert
        assertEquals("ȡff", testEncoder.toString())
    }

    @Test
    fun utf8EncodingThrowsOnInvalidSurrogate(): Unit = runBlockingWithTimeout {
        // Prepare
        val testEncoder = TestEncoder(
            Charsets.UTF_8,
            bufferCapacity = 16,
            throwOnMalformed = true
        )

        // Act
        exceptionRule.expect(MalformedInputException::class.java)
        testEncoder.encoder.useShutdown { encoder ->
            // The example uses \uD83D which is a high surrogate, i.e. it is missing the low
            // surrogate, so encoding will fail.
            encoder.encode("\uD83Dff")
        }
    }

    @Test
    fun utf16EncodingSupportsSurrogatePairs(): Unit = runBlockingWithTimeout {
        // Prepare
        val testEncoder = TestEncoder(Charsets.UTF_8, bufferCapacity = 16)

        // Act
        testEncoder.encoder.useShutdown { encoder ->
            // See https://stackoverflow.com/a/54922477 for more information and examples
            // on how to use UTF-16 surrogate pairs in Java
            encoder.encode(UTF16_SURROGATE_PAIR)
        }

        // Assert
        assertEquals(UTF16_SURROGATE_PAIR, testEncoder.toString())
    }

    @Test
    fun utf16EncodingSupportsSurrogatePairsOverMultipleCalls(): Unit = runBlockingWithTimeout {
        // Prepare
        val testEncoder = TestEncoder(Charsets.UTF_8, bufferCapacity = 16)

        // Act
        testEncoder.encoder.useShutdown { encoder ->
            // See https://stackoverflow.com/a/54922477 for more information and examples
            // on how to use UTF-16 surrogate pairs in Java
            encoder.encode(UTF16_SURROGATE_PAIR[0].toString())
            encoder.encode(UTF16_SURROGATE_PAIR[1].toString())
        }

        // Assert
        assertEquals(UTF16_SURROGATE_PAIR, testEncoder.toString())
    }

    @Test
    fun utf16EncodingThrowOnIncompleteSurrogatePair(): Unit = runBlockingWithTimeout {
        // Prepare
        val testEncoder = TestEncoder(Charsets.UTF_8, bufferCapacity = 16)

        // Act
        exceptionRule.expect(MalformedInputException::class.java)
        testEncoder.encoder.useShutdown { encoder ->
            // See https://stackoverflow.com/a/54922477 for more information and examples
            // on how to use UTF-16 surrogate pairs in Java
            encoder.encode(UTF16_SURROGATE_PAIR[0].toString() + "f")
        }
    }

    @Test
    fun utf16EncodingWorksWithLongString(): Unit = runBlockingWithTimeout {
        // Prepare
        val testEncoder = TestEncoder(Charsets.UTF_8, bufferCapacity = 16)
        val inputText = "This is a string longer than 16 bytes"

        // Act
        testEncoder.encoder.useShutdown { encoder ->
            // See https://stackoverflow.com/a/54922477 for more information and examples
            // on how to use UTF-16 surrogate pairs in Java
            encoder.encode(inputText)
        }

        // Assert
        assertEquals(inputText, testEncoder.toString())
    }

    @Test
    fun utf16EncodingWorksWithSurrogatePairsInMiddleOfLongInput(): Unit = runBlockingWithTimeout {
        // Prepare:
        // 1. We use a small buffer to force splitting of the input,
        // 2. We also use a surrogate pair sent in 2 separate inputs to force moving the
        // input to/from the `leftoverBuffer` in `SuspendingCharacterEncoder` implementation
        val testEncoder = TestEncoder(Charsets.UTF_8, bufferCapacity = 7)
        val inputText1 = "$UTF16_SURROGATE_PAIR-".repeat(5) + UTF16_SURROGATE_PAIR[0]
        val inputText2 = UTF16_SURROGATE_PAIR[1] + "$UTF16_SURROGATE_PAIR-".repeat(5)

        // Act
        testEncoder.encoder.useShutdown { encoder ->
            encoder.encode(inputText1)
            encoder.encode(inputText2)
        }

        // Assert
        assertEquals(inputText1 + inputText2, testEncoder.toString())
    }

    /**
     * Helper class to wrap a [SuspendingCharacterEncoder] and its accumulated [ByteBuffer]
     * resulting from calling [SuspendingCharacterEncoder.encode]
     */
    private class TestEncoder(
        private val charset: Charset,
        throwOnMalformed: Boolean = true,
        replacement: ByteArray? = null,
        bufferCapacity: Int = 16
    ) {

        val encoder = SuspendingCharacterEncoder(
            charset,
            throwOnMalformed = throwOnMalformed,
            replacement = replacement,
            bufferCapacity = bufferCapacity,
            byteProcessor = this::collectBytes
        )
        private val bytes = ResizableBuffer()

        fun toByteArray(): ByteArray {
            // Data in `buffer` in the range [0, position]
            val buffer = bytes.forChannelWrite()
            val count = buffer.remaining()

            return ByteArray(count).also { byteArray ->
                buffer.get(byteArray)
                buffer.clear()

                // Reset the resizable buffer with in its original state
                bytes.clear()
                bytes.appendBytes(byteArray)
            }
        }

        override fun toString(): String {
            val byteArray = toByteArray()
            val encodedBuffer = ByteBuffer.wrap(byteArray)
            return charset.decode(encodedBuffer).toString()
        }

        private fun collectBytes(buffer: ByteBuffer) {
            bytes.appendBytes(buffer)
        }
    }

    companion object {

        private const val ASCII_LOWER_A: Byte = 0x61
        private const val ASCII_LOWER_B: Byte = 0x62
        private const val ASCII_LOWER_F: Byte = 0x66
        private const val ASCII_LOWER_O: Byte = 0x6f
        private const val ASCII_LOWER_R: Byte = 0x72

        // See https://stackoverflow.com/a/54922477 for more information and examples
        // on how to use UTF-16 surrogate pairs in Java
        // "🌉".equals("\ud83c\udf09")
        private const val UTF16_SURROGATE_PAIR = "\ud83c\udf09"

        private const val UTF16_REPLACEMENT_CHARACTER = "ȡ"
    }
}
