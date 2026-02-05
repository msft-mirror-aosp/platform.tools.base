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
package com.android.adblib

import java.nio.CharBuffer

/**
 * Abstract class for writing to character streams using `suspend` functions. This is the coroutine friendly equivalent of [java.io.Writer].
 *
 * The [shutdown] method must always be called to ensure the final bytes of the output are "flushed" successfully. This is required because
 * some encoding use "surrogate" characters and the caller may want to detect incomplete sequences of characters.
 */
abstract class SuspendingWriter(private val bufferCapacity: Int = 256) : AutoShutdown {

  /** Temporary buffer of [bufferCapacity] length, used to hold writes of (short) strings and single characters */
  private var tempWriteBuffer: CharBuffer? = null

  init {
    require(bufferCapacity >= 1) { "Buffer capacity must be greater or equal to 1" }
  }

  /** Encodes and writes a single character [ch] */
  open suspend fun writeChar(ch: Char) {
    val charBuffer = getWriteBuffer(1 /* 1 character*/)
    charBuffer.put(ch)
    charBuffer.flip()
    writeChars(charBuffer)
  }

  /** Encodes and writes a string [str] */
  open suspend fun writeString(str: String) {
    writeString(str, 0, str.length)
  }

  /** Encodes and writes a substring of [str] */
  open suspend fun writeString(str: String, offset: Int, length: Int) {
    val charBuffer = getWriteBuffer(length)
    charBuffer.put(str, offset, length)
    charBuffer.flip()
    writeChars(charBuffer)
  }

  /**
   * Encodes and writes the contents of a [CharBuffer]. On success, all characters are written, and [CharBuffer.remaining] is equal to zero.
   */
  abstract suspend fun writeChars(charBuffer: CharBuffer)

  /** Returns an empty [CharBuffer] large enough to contain at least [len] characters */
  private fun getWriteBuffer(len: Int): CharBuffer {
    return if (len <= bufferCapacity) {
      // Re-use existing `writeBuffer` for "small" sizes
      (tempWriteBuffer ?: CharBuffer.allocate(bufferCapacity).also { tempWriteBuffer = it }).also { it.clear() }
    } else {
      // Allocate a new buffer for large sizes
      CharBuffer.allocate(len)
    }
  }
}
