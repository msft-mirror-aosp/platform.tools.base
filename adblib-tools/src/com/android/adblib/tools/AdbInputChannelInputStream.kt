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

import com.android.adblib.AdbInputChannel
import com.android.adblib.read
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.min
import kotlinx.coroutines.runBlocking

/**
 * This class allows you to read data from the [AdbInputChannel] using a standard [InputStream] interface. The [AdbInputChannelInputStream]
 * will block until data is available or the channel is closed.
 *
 * This class should only be used for operations that requires blocking IO based on an [InputStream]. For example,
 * `ImageIO.read(AdbInputChannelInputStream(adbInputChannel, bufferSize))`
 *
 * Closing the [AdbInputChannelInputStream] will also close the underlying [AdbInputChannel], preventing further reads.
 */
internal class AdbInputChannelInputStream(private val inputChannel: AdbInputChannel, bufferSize: Int) : InputStream() {

  private val buffer = ByteBuffer.allocate(bufferSize).flip()

  override fun read(): Int {
    if (!buffer.hasRemaining()) {
      readFromInputChannelToBuffer()
    }
    return if (buffer.hasRemaining()) buffer.get().toInt() and 0xFF else -1
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    if (len == 0) return 0
    if (!buffer.hasRemaining()) {
      readFromInputChannelToBuffer()
    }
    val count = min(buffer.remaining(), len)
    if (count == 0) {
      return -1
    }
    buffer.get(b, off, count)
    return count
  }

  override fun close() {
    inputChannel.close()
  }

  private fun readFromInputChannelToBuffer() {
    buffer.clear()
    runBlocking { inputChannel.read(buffer) }
    buffer.flip()
  }
}
