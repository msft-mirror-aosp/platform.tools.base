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

import com.android.adblib.AutoShutdown
import java.lang.AssertionError
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction
import kotlin.math.min

/**
 * Performs encoding of characters into bytes using a [byteProcessor] coroutine. Call [encode] as many times as needed to encode characters,
 * then [shutdown] when no more characters need to be encoded.
 */
internal class SuspendingCharacterEncoder(
  charset: Charset,
  private val autoFlush: Boolean = true,
  throwOnMalformed: Boolean = false,
  replacement: ByteArray? = null,
  bufferCapacity: Int = 256,
  private val byteProcessor: suspend (ByteBuffer) -> Unit,
) : AutoShutdown {

  private val encoder: CharsetEncoder = createEncoder(charset, throwOnMalformed, replacement)

  /**
   * Internal buffer used to store the encoded characters. Bytes already encoded are in the [0, pos] range Available room for new encoded
   * characters is in the [pos, limit=capcacity] range
   */
  private val byteBuffer = ByteBuffer.allocate(bufferCapacity)

  /**
   * Leftover characters from previous calls to [encode], in case of incomplete multi character sequence. Note that leftover characters are
   * always stored in the `[0, position]` range.
   */
  private var _leftoverBuffer: CharBuffer? = null

  /** Non-null access to [_leftoverBuffer] */
  private val leftoverBuffer: CharBuffer
    get() {
      return _leftoverBuffer ?: run { CharBuffer.allocate(byteBuffer.capacity()).limit(0).also { _leftoverBuffer = it } }
    }

  init {
    require(bufferCapacity >= 2 * encoder.maxBytesPerChar()) {
      "Buffer capacity is too small for encoder (capacity=$bufferCapacity, encoder=${encoder.maxBytesPerChar()})"
    }
  }

  suspend fun encode(charBuffer: CharBuffer) {
    // This outer loop is needed in case we need to partially copy `charBuffer`
    // to `leftoverBuffer` to process surrogate character. Since we limit the size of
    // `leftoverBuffer`, we need to iterate until we process all characters of `charBuffer`.
    while (charBuffer.hasRemaining()) {
      // Ensure `inputBuffer` takes into account `leftoverBuffer` from previous call
      assertLeftoverBufferIsValid()
      val inputBuffer = prepareInputBuffer(charBuffer)

      while (true) {
        when (encodeOnce(inputBuffer, byteBuffer, false)) {
          EncodeOnceResult.Done -> break
          EncodeOnceResult.CallAgain -> continue
        }
      }

      // Ensure `leftoverBuffer` is properly setup for next call
      finalizeLeftoverBuffer(inputBuffer)
      assertLeftoverBufferIsValid()
    }
  }

  override suspend fun shutdown() {
    // Try to convert any leftover input
    if (_leftoverBuffer != null) {
      encodeOnce(leftoverBuffer, byteBuffer, endOfInput = true).also { assert(it == EncodeOnceResult.Done) }
      assert(leftoverBuffer.remaining() == 0)
    }
    flushBuffer(byteBuffer)
  }

  override fun close() {
    // Nothing to do
  }

  /**
   * Calls [CharsetEncoder.encode] once, using current [inputBuffer] and [outputBuffer]. Returns [EncodeOnceResult.Done] if no more calls
   * are needed Results [EncodeOnceResult.CallAgain] if another call is needed.
   */
  private suspend fun encodeOnce(inputBuffer: CharBuffer, outputBuffer: ByteBuffer, endOfInput: Boolean): EncodeOnceResult {
    // Encore "inputCharBuffer" to "outputBuffer"
    val result = encoder.encode(inputBuffer, outputBuffer, endOfInput)
    return when {
      // Indicates that as much of the input buffer as possible has been encoded.
      // If there is no further input then the invoker can proceed to the next step of
      // the encoding operation.
      // Otherwise, this method should be invoked again with further input.
      result.isUnderflow -> {
        if (autoFlush) {
          flushBuffer(outputBuffer)
        }
        EncodeOnceResult.Done
      }
      // Indicates that there is insufficient space in the output buffer to encode any
      // more characters.
      // This method should be invoked again with an output buffer that has more
      // remaining bytes. This is typically done by draining any encoded bytes from
      // the output buffer.
      result.isOverflow -> {
        flushBuffer(outputBuffer)
        EncodeOnceResult.CallAgain
      }

      result.isMalformed || result.isUnmappable -> {
        @Suppress("BlockingMethodInNonBlockingContext") result.throwException()
        throw AssertionError("Internal error: no exception thrown for error '${result}'")
      }

      else -> {
        throw AssertionError("Internal error: Unexpected encoder result")
      }
    }
  }

  private enum class EncodeOnceResult {
    Done,
    CallAgain,
  }

  private fun prepareInputBuffer(inputBuffer: CharBuffer): CharBuffer {
    // If we have remaining bytes in `previousCharBuffer`, we need to use them
    // as prefix of the new inputBuffer (support for multi character sequences)
    val inputCharBuffer =
      if (_leftoverBuffer !== null && leftoverBuffer.position() > 0) {
        // Note: Leftover characters are always in the range [0, pos==limit]

        // Append as much as possible from `inputBuffer` to `leftoverBuffer`
        val copyCount = min(leftoverBuffer.capacity() - leftoverBuffer.limit(), inputBuffer.remaining())

        // Copy data at end of leftover buffer, first changing range from [0, pos==limit]
        // to [pos=limit, limit=capacity]
        leftoverBuffer.limit(leftoverBuffer.capacity())

        putBufferImpl(leftoverBuffer, inputBuffer, inputBuffer.position(), copyCount)

        // Update positions because `put` method does not update positions of either buffer
        inputBuffer.position(inputBuffer.position() + copyCount)
        leftoverBuffer.position(leftoverBuffer.position() + copyCount)

        // Change range of characters to encode from [0, position] to [pos=0, limit]
        // so we can write the buffer content (maybe partially)
        leftoverBuffer.flip()
      } else {
        // Common case: we can use the input buffer as-is
        inputBuffer
      }
    return inputCharBuffer
  }

  private fun finalizeLeftoverBuffer(inputBuffer: CharBuffer) {
    // There may be leftover characters in `inputBuffer` they contain an incomplete
    // sequence of characters. We need to keep them for the next call
    if (inputBuffer.hasRemaining()) {
      // If we were using `leftoverBuffer`, simply compact it for the next call
      if (inputBuffer === _leftoverBuffer) {
        // Data from [position, limit] is moved to position [0, pos=count]
        leftoverBuffer.compact()

        // Update range to [pos=0, count]
        val count = leftoverBuffer.remaining()
        leftoverBuffer.position(0)
        leftoverBuffer.limit(count)
      } else {
        // If we were using the `inputBuffer` directly, copy its remaining
        // contents to `leftoverBuffer` for next call
        val count = inputBuffer.remaining()
        check(leftoverBuffer.remaining() == 0)
        check(leftoverBuffer.capacity() >= count)
        check(leftoverBuffer.position() == 0)

        // Copy `inputBuffer` to start of `leftoverBuffer` for next call
        leftoverBuffer.limit(count)
        leftoverBuffer.put(inputBuffer)
        check(inputBuffer.remaining() == 0)

        // Data is in the range [0, pos=limit], change the range to [0, pos]
        // leftoverBuffer.position(0)
        check(leftoverBuffer.remaining() == 0)
        check(leftoverBuffer.position() == count)
        check(leftoverBuffer.limit() == count)
      }
    } else {
      assert(!inputBuffer.hasRemaining())
      // All input has been consumed, ensure `leftoverBuffer` is reset
      if (inputBuffer === _leftoverBuffer) {
        // Set range to [pos=0, limit=0]
        leftoverBuffer.clear()
        leftoverBuffer.limit(0)
      }
    }

    // We are done for now, since we are out of input characters that can be
    // encoded in the output.
    assertLeftoverBufferIsValid()
  }

  private suspend fun flushBuffer(outputBuffer: ByteBuffer) {
    // [0, pos] -> [pos=0, limit]
    outputBuffer.flip()
    if (outputBuffer.hasRemaining()) {
      byteProcessor(outputBuffer)
      check(outputBuffer.remaining() == 0) {
        "Encoder did not write all bytes to underlying resource (remaining=${outputBuffer.remaining()})"
      }
    }
    outputBuffer.clear()
  }

  private fun assertLeftoverBufferIsValid() {
    _leftoverBuffer?.also {
      // Assert that any leftover characters is in this range
      // [0 <= (pos==limit) < capacity]
      assert(it.limit() == it.position())
      assert(it.limit() < it.capacity())
    }
  }

  companion object {

    /** Return a [CharsetEncoder] for the given [charset] */
    private fun createEncoder(charset: Charset, throwOnMalformed: Boolean, replacement: ByteArray?): CharsetEncoder {
      if (throwOnMalformed && replacement != null) {
        throw IllegalArgumentException("Using replacement and throwing exception are incompatible options")
      }
      val errorAction =
        if (throwOnMalformed) {
          CodingErrorAction.REPORT
        } else {
          CodingErrorAction.REPLACE
        }
      return charset.newEncoder().onMalformedInput(errorAction).onUnmappableCharacter(errorAction).also {
        if (replacement != null) {
          it.replaceWith(replacement)
        }
      }
    }
  }

  /**
   * This method transfers [length] chars into the [dst] buffer from the given [src] buffer, starting at the given [srcPosition] in the
   * source buffer and the given [CharBuffer.position] in the [dst] buffer.
   *
   * The positions of both buffers are unchanged.
   */
  private fun putBufferImpl(dst: CharBuffer, src: CharBuffer, srcPosition: Int, length: Int): CharBuffer {
    // Note: Once JDK16+ is available, there is a more efficient API we could use:
    // dst.put(dst.position(), src, srcPosition, length)

    if (src.hasArray()) {
      dst.put(src.array(), srcPosition, length)
    } else {
      val srcTemp = src.duplicate()
      srcTemp.position(srcPosition)
      srcTemp.limit(srcPosition + length)
      dst.put(srcTemp)
    }
    // Reset position, since `put` updated it
    dst.position(dst.position() - length)

    return dst
  }
}

internal suspend fun SuspendingCharacterEncoder.encode(text: String) {
  encode(CharBuffer.wrap(text))
}
