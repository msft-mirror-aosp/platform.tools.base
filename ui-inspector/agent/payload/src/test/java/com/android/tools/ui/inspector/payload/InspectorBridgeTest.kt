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

package com.android.tools.ui.inspector.payload

import androidx.inspection.Connection
import androidx.inspection.Inspector
import com.android.tools.ui.inspector.payload.appinspection.DelegatingConnection
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InspectorBridgeTest {

  private val mockConnection =
    object : Connection() {
      override fun sendEvent(data: ByteArray) {
        // Not used
      }
    }

  @Test
  fun testSendCommand_sequentialProcessing() = runBlocking {
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    val primaryExecutor = HandlerThreadExecutor("test_bridge_seq", { throw it })

    var inProgress = false
    val mockInspectorSeq =
      object : Inspector(mockConnection) {
        override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {
          assertThat(inProgress).isFalse()
          inProgress = true
          runBlocking { delay(100) }
          inProgress = false
          callback.reply(data)
        }

        override fun onDispose() {}
      }

    val bridgeSeq = InspectorBridge.createForTesting(mockInspectorSeq, DelegatingConnection(), testScope, primaryExecutor)

    val job1Seq = launch { bridgeSeq.sendCommand(byteArrayOf(1)) }
    val job2Seq = launch { bridgeSeq.sendCommand(byteArrayOf(2)) }

    job1Seq.join()
    job2Seq.join()

    testScope.cancel()
  }

  @Test
  fun testSendCommand_executesOnPrimaryExecutorThread() = runBlocking {
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    val primaryExecutor = HandlerThreadExecutor("test_bridge_thread", { throw it })

    var executionThreadName: String? = null
    val mockInspector =
      object : Inspector(mockConnection) {
        override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {
          executionThreadName = Thread.currentThread().name
          callback.reply(data)
        }

        override fun onDispose() {}
      }

    val bridge = InspectorBridge.createForTesting(mockInspector, DelegatingConnection(), testScope, primaryExecutor)

    bridge.sendCommand(byteArrayOf(1))

    assertThat(executionThreadName).startsWith("test_bridge_thread")

    testScope.cancel()
  }

  @Test
  fun testSendCommand_errorPropagation() = runBlocking {
    val mockInspector =
      object : Inspector(mockConnection) {
        override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {
          throw RuntimeException("Test exception")
        }

        override fun onDispose() {}
      }

    val testScope = CoroutineScope(Dispatchers.Default + Job())
    val primaryExecutor = HandlerThreadExecutor("test_bridge_err", { throw it })
    val bridge = InspectorBridge.createForTesting(mockInspector, DelegatingConnection(), testScope, primaryExecutor)

    var exceptionThrown = false
    try {
      bridge.sendCommand(byteArrayOf(1))
    } catch (e: Exception) {
      exceptionThrown = true
      assertThat(e.message).contains("Test exception")
    }
    testScope.cancel()
    assertThat(exceptionThrown).isTrue()
  }

  @Test
  fun testUpdateConnection_updatesDelegatingConnection() {
    val delegatingConnection = DelegatingConnection()
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    val primaryExecutor = HandlerThreadExecutor("test_bridge_conn", { throw it })

    val mockInspector =
      object : Inspector(mockConnection) {
        override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {}

        override fun onDispose() {}
      }

    val bridge = InspectorBridge.createForTesting(mockInspector, delegatingConnection, testScope, primaryExecutor)

    val newConnection =
      object : Connection() {
        override fun sendEvent(data: ByteArray) {}
      }

    bridge.updateConnection(newConnection)

    assertThat(delegatingConnection.activeConnection === newConnection).isTrue()
    testScope.cancel()
  }

  @Test
  fun testDispose_callsOnDisposeAndQuitsExecutor() = runBlocking {
    var disposed = false
    val mockInspector =
      object : Inspector(mockConnection) {
        override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {}

        override fun onDispose() {
          disposed = true
        }
      }

    val testScope = CoroutineScope(Dispatchers.Default + Job())
    val primaryExecutor = HandlerThreadExecutor("test_bridge_dispose", { throw it })
    val bridge = InspectorBridge.createForTesting(mockInspector, DelegatingConnection(), testScope, primaryExecutor)

    bridge.dispose()

    assertThat(disposed).isTrue()

    var exceptionThrown = false
    try {
      bridge.sendCommand(byteArrayOf(1))
    } catch (e: Exception) {
      exceptionThrown = true
      // If the channel is closed, channel.send throws ClosedSendChannelException.
      // So we can verify that an exception is thrown.
      assertThat(e).isInstanceOf(kotlinx.coroutines.channels.ClosedSendChannelException::class.java)
    }
    testScope.cancel()
    assertThat(exceptionThrown).isTrue()
  }
}
