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
package com.google.services.firebase.directaccess.client.device.remote.service.adb.forwardingdaemon.reverse.daemon

import com.google.common.truth.Truth.assertThat
import com.google.services.firebase.directaccess.client.device.remote.service.adb.forwardingdaemon.reverse.MessageType
import com.google.services.firebase.directaccess.client.device.remote.service.adb.forwardingdaemon.reverse.StreamDataHeader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class ReverseDaemonTest {
  private val originalOutput = ReverseDaemon.output
  private val testOutput = ByteArrayOutputStream()

  @Before
  fun setUp() {
    ReverseDaemon.output = testOutput
  }

  @After
  fun tearDown() {
    ReverseDaemon.output = originalOutput
  }

  @Test
  fun testSocketReaderCatchesIOExceptionAndSendsClose() {
    val faultyInputStream =
      object : InputStream() {
        override fun read(): Int {
          throw IOException("Connection reset by peer")
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          throw IOException("Connection reset by peer")
        }
      }

    var closed = false
    val socketReader =
      ReverseDaemon.SocketReader(
        streamId = 123,
        input = faultyInputStream,
        socketOutput = ByteArrayOutputStream(),
        close = { closed = true },
      )

    // Running socket reader should catch the IOException and not propagate it
    socketReader.run()

    assertThat(closed).isTrue()

    // Assert that the CLSE header was written to output despite the IOException
    val buffer = ByteBuffer.wrap(testOutput.toByteArray())
    val header = readHeader(buffer)
    assertThat(header.type).isEqualTo(MessageType.CLSE)
    assertThat(header.streamId).isEqualTo(123)
    assertThat(header.len).isEqualTo(0)
  }

  @Test
  fun testSocketReaderReadsDataThenSendsCloseOnEof() {
    val payload = "hello world".toByteArray()
    val inputStream = ByteArrayInputStream(payload)

    var closed = false
    val socketReader =
      ReverseDaemon.SocketReader(
        streamId = 42,
        input = inputStream,
        socketOutput = ByteArrayOutputStream(),
        close = { closed = true },
      )

    socketReader.run()

    assertThat(closed).isTrue()

    val buffer = ByteBuffer.wrap(testOutput.toByteArray())

    // First message: DATA
    val dataHeader = readHeader(buffer)
    assertThat(dataHeader.type).isEqualTo(MessageType.DATA)
    assertThat(dataHeader.streamId).isEqualTo(42)
    assertThat(dataHeader.len).isEqualTo(payload.size)

    val receivedPayload = ByteArray(payload.size)
    buffer.get(receivedPayload)
    assertThat(receivedPayload).isEqualTo(payload)

    // Second message: CLSE
    val closeHeader = readHeader(buffer)
    assertThat(closeHeader.type).isEqualTo(MessageType.CLSE)
    assertThat(closeHeader.streamId).isEqualTo(42)
    assertThat(closeHeader.len).isEqualTo(0)
  }

  @Test
  fun testSocketReaderSendsCloseOnEmptyStream() {
    val inputStream = ByteArrayInputStream(ByteArray(0))

    var closed = false
    val socketReader =
      ReverseDaemon.SocketReader(
        streamId = 99,
        input = inputStream,
        socketOutput = ByteArrayOutputStream(),
        close = { closed = true },
      )

    socketReader.run()

    assertThat(closed).isTrue()

    val buffer = ByteBuffer.wrap(testOutput.toByteArray())
    val closeHeader = readHeader(buffer)
    assertThat(closeHeader.type).isEqualTo(MessageType.CLSE)
    assertThat(closeHeader.streamId).isEqualTo(99)
    assertThat(closeHeader.len).isEqualTo(0)
  }

  private fun readHeader(buffer: ByteBuffer): StreamDataHeader {
    val header = StreamDataHeader(buffer.slice())
    buffer.position(buffer.position() + 12)
    return header
  }
}
