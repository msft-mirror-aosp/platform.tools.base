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

package com.android.tools.ui.inspector.payload.inspector

import androidx.inspection.ArtTooling
import androidx.inspection.Connection
import androidx.inspection.Inspector
import androidx.inspection.InspectorEnvironment
import androidx.inspection.InspectorExecutors
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

    inspector.onReceiveCommand("hello".toByteArray(), callback)

    assertThat(replyData).isNotNull()
    assertThat(String(replyData!!)).isEqualTo("world")
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

    inspector.onReceiveCommand("unknown".toByteArray(), callback)

    assertThat(replyData).isNotNull()
    assertThat(String(replyData!!)).isEqualTo("unknown command")
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

    inspector.onReceiveCommand("trigger_event".toByteArray(), callback)

    assertThat(replyData).isNotNull()
    assertThat(String(replyData!!)).isEqualTo("event triggered")
    assertThat(capturedEvent).isNotNull()
    assertThat(String(capturedEvent!!)).isEqualTo("hello event")
  }
}
