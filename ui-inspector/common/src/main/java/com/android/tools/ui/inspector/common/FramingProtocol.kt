/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tools.ui.inspector.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Helper for reading and writing framed messages over a stream. Used by both the host and the agent to ensure consistent message framing.
 *
 * The protocol format is:
 * 1. Header: 8 bytes ("UIINSPCT")
 * 2. Payload length: 4 bytes (Int, big-endian)
 * 3. Payload: 'length' bytes
 */
object FramingProtocol {

  private const val HEADER_STRING = "UIINSPCT"
  private val HEADER = HEADER_STRING.toByteArray(Charsets.US_ASCII)
  private const val HEADER_SIZE = 8

  fun writeMessage(outputStream: OutputStream, payload: ByteArray) {
    synchronized(outputStream) {
      val dataOutput = DataOutputStream(outputStream)
      dataOutput.write(HEADER)
      dataOutput.writeInt(payload.size)
      dataOutput.write(payload)
      dataOutput.flush()
    }
  }

  fun readMessage(inputStream: InputStream): ByteArray {
    val dataInput = DataInputStream(inputStream)
    val header = ByteArray(HEADER_SIZE)
    dataInput.readFully(header)
    if (!header.contentEquals(HEADER)) {
      throw IllegalStateException("Invalid header in framing protocol")
    }
    val length = dataInput.readInt()
    val payload = ByteArray(length)
    dataInput.readFully(payload)
    return payload
  }
}
