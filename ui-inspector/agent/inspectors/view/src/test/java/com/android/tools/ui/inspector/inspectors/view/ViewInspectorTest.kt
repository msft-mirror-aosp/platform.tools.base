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

package com.android.tools.ui.inspector.inspectors.view

import androidx.inspection.ArtTooling
import androidx.inspection.Connection
import androidx.inspection.Inspector
import androidx.inspection.InspectorEnvironment
import androidx.inspection.InspectorExecutors
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Command
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Event
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.HelloCommand
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Response
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.TriggerEventCommand
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.Test

class ViewInspectorTest {

  private val mockEnvironment =
    object : InspectorEnvironment {
      override fun executors(): InspectorExecutors {
        throw UnsupportedOperationException("Not implemented")
      }

      override fun artTooling(): ArtTooling {
        throw UnsupportedOperationException("Not implemented")
      }
    }

  @Test
  fun testOnReceiveCommand_hello() {
    val mockConnection =
      object : Connection() {
        override fun sendEvent(data: ByteArray) {
          // Not used in this test
        }
      }

    val inspector = ViewInspector(mockConnection, mockEnvironment)

    var replyData: ByteArray? = null
    val callback =
      object : Inspector.CommandCallback {
        override fun reply(response: ByteArray) {
          replyData = response
        }

        override fun addCancellationListener(executor: Executor, runnable: Runnable) {
          // Not used
        }
      }

    val command = Command.newBuilder().setHelloCommand(HelloCommand.getDefaultInstance()).build()
    inspector.onReceiveCommand(command.toByteArray(), callback)

    assertThat(replyData).isNotNull()
    val response = Response.parseFrom(replyData!!)
    assertThat(response.specializedCase).isEqualTo(Response.SpecializedCase.HELLO_RESPONSE)
  }

  @Test
  fun testOnReceiveCommand_unknown() {
    val mockConnection =
      object : Connection() {
        override fun sendEvent(data: ByteArray) {
          // Not used
        }
      }
    val inspector = ViewInspector(mockConnection, mockEnvironment)

    var replyData: ByteArray? = null
    val callback =
      object : Inspector.CommandCallback {
        override fun reply(response: ByteArray) {
          replyData = response
        }

        override fun addCancellationListener(executor: Executor, runnable: Runnable) {
          // Not used
        }
      }

    val command = Command.getDefaultInstance()
    var exceptionThrown = false
    try {
      inspector.onReceiveCommand(command.toByteArray(), callback)
    } catch (e: IllegalStateException) {
      exceptionThrown = true
      assertThat(e.message).contains("Unknown command")
    }
    assertThat(exceptionThrown).isTrue()
  }

  @Test
  fun testOnReceiveCommand_triggerEvent() {
    var capturedEvent: ByteArray? = null
    val mockConnection =
      object : Connection() {
        override fun sendEvent(data: ByteArray) {
          capturedEvent = data
        }
      }
    val inspector = ViewInspector(mockConnection, mockEnvironment)

    var replyData: ByteArray? = null
    val callback =
      object : Inspector.CommandCallback {
        override fun reply(response: ByteArray) {
          replyData = response
        }

        override fun addCancellationListener(executor: java.util.concurrent.Executor, runnable: Runnable) {
          // Not used
        }
      }

    val command = Command.newBuilder().setTriggerEventCommand(TriggerEventCommand.getDefaultInstance()).build()
    inspector.onReceiveCommand(command.toByteArray(), callback)

    assertThat(replyData).isNotNull()
    val response = Response.parseFrom(replyData!!)
    assertThat(response.specializedCase).isEqualTo(Response.SpecializedCase.TRIGGER_EVENT_RESPONSE)

    assertThat(capturedEvent).isNotNull()
    val event = Event.parseFrom(capturedEvent!!)
    assertThat(event.specializedCase).isEqualTo(Event.SpecializedCase.HELLO_EVENT)
    assertThat(event.helloEvent.message).isEqualTo("hello event")
  }
}
