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

import androidx.inspection.Connection
import androidx.inspection.Inspector
import androidx.inspection.InspectorEnvironment
import com.android.tools.ui.inspector.common.FramingProtocol
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppInspectionUtilsTest {

  private val mockConnection =
    object : Connection() {
      override fun sendEvent(data: ByteArray) {
        // Not used
      }
    }

  @Test
  fun testCreateInspectorEnvironment_ioExecutorDelegates() {
    val primaryExecutor = HandlerThreadExecutor("test-thread") {}
    val environment = createInspectorEnvironment(primaryExecutor) {}

    val latch = CountDownLatch(1)
    environment.executors().io().execute { latch.countDown() }

    val completed = latch.await(5, TimeUnit.SECONDS)
    assertThat(completed).isTrue()
    primaryExecutor.quitSafely()
  }

  @Test
  fun testCreateInspectorEnvironment_ioExecutorCatchesException() {
    val primaryExecutor = HandlerThreadExecutor("test-thread") {}
    var caughtThrowable: Throwable? = null
    val latch = CountDownLatch(1)
    val exception = RuntimeException("Test exception")

    val environment =
      createInspectorEnvironment(primaryExecutor) { t ->
        caughtThrowable = t
        latch.countDown()
      }

    environment.executors().io().execute { throw exception }

    val completed = latch.await(5, TimeUnit.SECONDS)
    assertThat(completed).isTrue()
    assertThat(caughtThrowable).isEqualTo(exception)
    primaryExecutor.quitSafely()
  }

  @Test
  fun testCreateAppInspectionConnection_wrapsEventWithId() {
    val outputStream = ByteArrayOutputStream()
    val inspectorId = "test_inspector_id"
    val connection = createAppInspectionConnection(inspectorId, outputStream) {}

    val eventPayload = byteArrayOf(1, 2, 3)
    connection.sendEvent(eventPayload)

    val writtenBytes = outputStream.toByteArray()
    val responseBytes = FramingProtocol.readMessage(ByteArrayInputStream(writtenBytes))
    val event = com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Event.parseFrom(responseBytes)

    assertThat(event.specializedCase)
      .isEqualTo(com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Event.SpecializedCase.INSPECTOR_MESSAGE)
    assertThat(event.inspectorMessage.inspectorId).isEqualTo(inspectorId)
    assertThat(event.inspectorMessage.payload.toByteArray()).isEqualTo(eventPayload)
  }

  @Test
  fun testDelegatingConnection_delegates() {
    var capturedEvent: ByteArray? = null
    val mockConnection =
      object : Connection() {
        override fun sendEvent(data: ByteArray) {
          capturedEvent = data
        }
      }

    val delegatingConnection = DelegatingConnection()
    delegatingConnection.activeConnection = mockConnection

    val eventPayload = byteArrayOf(4, 5)
    delegatingConnection.sendEvent(eventPayload)

    assertThat(capturedEvent).isEqualTo(eventPayload)
  }

  @Test
  fun testHandleCommandSuspend_resumesWithReply() =
    kotlinx.coroutines.runBlocking {
      var replyCallback: Inspector.CommandCallback? = null
      val mockInspector =
        object : Inspector(mockConnection) {
          override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {
            replyCallback = callback
          }

          override fun onDispose() {}
        }

      val commandPayload = byteArrayOf(7)
      val job =
        kotlinx.coroutines.GlobalScope.launch {
          val reply = mockInspector.handleCommandSuspend(commandPayload)
          assertThat(reply).isEqualTo(byteArrayOf(99))
        }

      // Yield to let the coroutine start and call onReceiveCommand
      kotlinx.coroutines.delay(100)

      assertThat(replyCallback).isNotNull()
      replyCallback!!.reply(byteArrayOf(99))

      job.join()
    }

  @Test
  fun testLoadInspectorDynamically_throwsOnInvalidPath() {
    val mockEnvironment =
      object : InspectorEnvironment {
        override fun executors(): androidx.inspection.InspectorExecutors {
          throw UnsupportedOperationException("Not implemented")
        }

        override fun artTooling(): androidx.inspection.ArtTooling {
          throw UnsupportedOperationException("Not implemented")
        }
      }

    var exceptionThrown = false
    try {
      loadInspectorDynamically("test_id", "/invalid/path.dex", mockConnection, mockEnvironment)
    } catch (e: Exception) {
      exceptionThrown = true
      assertThat(e.message).contains("Failed to find InspectorFactory")
    }
    assertThat(exceptionThrown).isTrue()
  }
}
