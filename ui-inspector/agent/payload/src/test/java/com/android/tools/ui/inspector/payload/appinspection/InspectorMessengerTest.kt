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

package com.android.tools.ui.inspector.payload.appinspection

import androidx.inspection.Inspector
import com.android.tools.ui.inspector.common.FramingProtocol
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InspectorMessengerTest {

  @Test
  fun testHandleCommand_delegatesToInspector() {
    val mockInspector = mock(Inspector::class.java)
    val outputStream = ByteArrayOutputStream()
    val messenger = InspectorMessenger(outputStream, mockInspector) {}
    val command = byteArrayOf(1, 2, 3)

    messenger.handleCommand(command)

    verify(mockInspector).onReceiveCommand(eq(command), any())
  }

  @Test
  fun testCallbackReply_writesToOutputStream() {
    val mockInspector = mock(Inspector::class.java)
    val outputStream = ByteArrayOutputStream()
    val messenger = InspectorMessenger(outputStream, mockInspector) {}
    val command = byteArrayOf(1, 2, 3)

    messenger.handleCommand(command)

    val captor = ArgumentCaptor.forClass(Inspector.CommandCallback::class.java)
    verify(mockInspector).onReceiveCommand(any(), captor.capture())

    val response = byteArrayOf(4, 5, 6)
    captor.value.reply(response)

    val expectedOutput = ByteArrayOutputStream().apply { FramingProtocol.writeMessage(this, response) }.toByteArray()

    assertThat(outputStream.toByteArray()).isEqualTo(expectedOutput)
  }

  @Test
  fun testCallbackReply_catchesIOException() {
    val mockInspector = mock(Inspector::class.java)
    val outputStream =
      object : OutputStream() {
        override fun write(b: Int) {
          throw IOException("Test IO Exception")
        }

        override fun write(b: ByteArray) {
          throw IOException("Test IO Exception")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
          throw IOException("Test IO Exception")
        }
      }
    var caughtThrowable: Throwable? = null
    val messenger = InspectorMessenger(outputStream, mockInspector) { t -> caughtThrowable = t }

    messenger.handleCommand(byteArrayOf(1))

    val captor = ArgumentCaptor.forClass(Inspector.CommandCallback::class.java)
    verify(mockInspector).onReceiveCommand(any(), captor.capture())

    captor.value.reply(byteArrayOf(4))

    assertThat(caughtThrowable).isNull()
  }

  @Test
  fun testCallbackReply_catchesOtherException_callsCrashListener() {
    val mockInspector = mock(Inspector::class.java)
    val outputStream =
      object : OutputStream() {
        override fun write(b: Int) {
          throw RuntimeException("Test Runtime Exception")
        }

        override fun write(b: ByteArray) {
          throw RuntimeException("Test Runtime Exception")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
          throw RuntimeException("Test Runtime Exception")
        }
      }
    var caughtThrowable: Throwable? = null
    val messenger = InspectorMessenger(outputStream, mockInspector) { t -> caughtThrowable = t }

    messenger.handleCommand(byteArrayOf(1))

    val captor = ArgumentCaptor.forClass(Inspector.CommandCallback::class.java)
    verify(mockInspector).onReceiveCommand(any(), captor.capture())

    captor.value.reply(byteArrayOf(4))

    assertThat(caughtThrowable).isInstanceOf(RuntimeException::class.java)
  }
}
