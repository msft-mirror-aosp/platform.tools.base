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

import com.android.adblib.impl.channels.SuspendingCharacterEncoder
import com.android.adblib.utils.AdbProtocolUtils
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset

/** A [SuspendingWriter] that writes to the given [AdbOutputChannel] */
internal class AdbOutputChannelWriter(
  val channel: AdbOutputChannel,
  autoFlush: Boolean = true,
  throwsOnMalformed: Boolean = false,
  bufferCapacity: Int = 256,
  charset: Charset = AdbProtocolUtils.ADB_CHARSET,
) : SuspendingWriter(bufferCapacity) {

  /** The suspending encoder we use to encode incoming characters */
  private val encoder =
    SuspendingCharacterEncoder(
      charset,
      autoFlush = autoFlush,
      throwOnMalformed = throwsOnMalformed,
      bufferCapacity = bufferCapacity,
      byteProcessor = this::flushBuffer,
    )

  override suspend fun writeChars(charBuffer: CharBuffer) {
    // Encode characters and call `flushBuffer` as needed
    encoder.encode(charBuffer)
  }

  override suspend fun shutdown() {
    // Last call to `flushBuffer` as needed
    encoder.shutdown()
  }

  override fun close() {
    // We close the underlying channel to follow the convention used by Java
    // OutputStream API
    channel.close()
    encoder.close()
  }

  /** The "callback" from [encoder] when encoded characters are available. */
  private suspend fun flushBuffer(outputBuffer: ByteBuffer) {
    channel.writeExactly(outputBuffer)
  }
}
