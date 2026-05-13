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
import com.android.tools.ui.inspector.common.FramingProtocol
import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.payload.InspectorBridge
import com.android.tools.ui.inspector.payload.SessionHandler
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Command
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.CreateInspectorCommand
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.GetVersionCommand
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.Response
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol.ShutdownCommand
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionHandlerTest {

  private val mockConnection =
    object : Connection() {
      override fun sendEvent(data: ByteArray) {
        // Not used
      }
    }

  @Test
  fun testHandle_shutdown_disposesInspector() = runBlocking {
    val outputStream = ByteArrayOutputStream()

    // Prepare input data with a Shutdown command
    val command = Command.newBuilder().setCommandId(1).setShutdown(ShutdownCommand.getDefaultInstance()).build()

    val commandBytes = command.toByteArray()
    val inputStreamData = ByteArrayOutputStream()
    FramingProtocol.writeMessage(inputStreamData, commandBytes)

    val inputStream = ByteArrayInputStream(inputStreamData.toByteArray())

    var disposed = false
    val mockInspector =
      object : Inspector(mockConnection) {
        override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {
          // Not used
        }

        override fun onDispose() {
          disposed = true
        }
      }

    val shutdownSignal = CompletableDeferred<Unit>()
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    val bridges = mutableMapOf<String, InspectorBridge>()
    val primaryExecutor = HandlerThreadExecutor("test_thread_shutdown", { throw it })
    bridges[ProtocolConstants.VIEW_INSPECTOR_ID] = InspectorBridge(mockInspector, DelegatingConnection(), testScope, primaryExecutor)

    val sessionHandler =
      SessionHandler(
        inputStream = inputStream,
        outputStream = outputStream,
        crashListener = { throw it },
        shutdownSignal = shutdownSignal,
        serverScope = testScope,
        inspectorBridges = bridges,
      )

    sessionHandler.processCommands()
    if (shutdownSignal.isCompleted) {
      bridges.values.forEach { it.dispose() }
    }
    testScope.cancel()
    assertThat(disposed).isTrue()
    assertThat(shutdownSignal.isCompleted).isTrue()

    // Verify response was written
    val writtenBytes = outputStream.toByteArray()
    val responseBytes = FramingProtocol.readMessage(ByteArrayInputStream(writtenBytes))
    val response = Response.parseFrom(responseBytes)

    assertThat(response.commandId).isEqualTo(1)
    assertThat(response.status).isEqualTo(Response.Status.SUCCESS)
    assertThat(response.specializedCase).isEqualTo(Response.SpecializedCase.SHUTDOWN)
  }

  @Test
  fun testHandle_eof_doesNotDisposeInspector() = runBlocking {
    val outputStream = ByteArrayOutputStream()

    // Empty input stream to trigger EOF immediately
    val inputStream = ByteArrayInputStream(ByteArray(0))

    var disposed = false
    val mockInspector =
      object : Inspector(mockConnection) {
        override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {
          // Not used
        }

        override fun onDispose() {
          disposed = true
        }
      }

    val shutdownSignal = CompletableDeferred<Unit>()
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    val bridges = mutableMapOf<String, InspectorBridge>()
    val primaryExecutor = HandlerThreadExecutor("test_thread_eof", { throw it })
    bridges[ProtocolConstants.VIEW_INSPECTOR_ID] = InspectorBridge(mockInspector, DelegatingConnection(), testScope, primaryExecutor)

    val sessionHandler =
      SessionHandler(
        inputStream = inputStream,
        outputStream = outputStream,
        crashListener = { throw it },
        shutdownSignal = shutdownSignal,
        serverScope = testScope,
        inspectorBridges = bridges,
      )

    sessionHandler.processCommands()
    testScope.cancel()
    assertThat(disposed).isFalse()
    assertThat(shutdownSignal.isCompleted).isFalse()
  }

  @Test
  fun testHandle_createInspector_failsWithInvalidPath() = runBlocking {
    val outputStream = ByteArrayOutputStream()

    val command =
      Command.newBuilder()
        .setCommandId(1)
        .setCreateInspector(CreateInspectorCommand.newBuilder().setInspectorId("test_inspector").setDexPath("/path/to/test.dex").build())
        .build()

    val commandBytes = command.toByteArray()
    val inputStreamData = ByteArrayOutputStream()
    FramingProtocol.writeMessage(inputStreamData, commandBytes)

    val inputStream = ByteArrayInputStream(inputStreamData.toByteArray())

    val shutdownSignal = CompletableDeferred<Unit>()
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    val sessionHandler =
      SessionHandler(
        inputStream = inputStream,
        outputStream = outputStream,
        crashListener = { throw it },
        shutdownSignal = shutdownSignal,
        serverScope = testScope,
        inspectorBridges = mutableMapOf(),
      )

    sessionHandler.processCommands()
    testScope.cancel()

    val writtenBytes = outputStream.toByteArray()
    val responseBytes = FramingProtocol.readMessage(ByteArrayInputStream(writtenBytes))
    val response = Response.parseFrom(responseBytes)

    assertThat(response.commandId).isEqualTo(1)
    assertThat(response.status).isEqualTo(Response.Status.ERROR)
    assertThat(response.errorMessage).contains("Failed to find InspectorFactory")
  }

  @Test
  fun testHandle_unknownInspector_fails() = runBlocking {
    val outputStream = ByteArrayOutputStream()

    val command =
      Command.newBuilder()
        .setCommandId(1)
        .setInspectorMessage(
          com.android.tools.ui.inspector.protocol.UiInspectorProtocol.InspectorMessageCommand.newBuilder()
            .setInspectorId("unknown_inspector")
            .setPayload(com.android.tools.idea.protobuf.ByteString.copyFrom(ByteArray(0)))
            .build()
        )
        .build()

    val commandBytes = command.toByteArray()
    val inputStreamData = ByteArrayOutputStream()
    FramingProtocol.writeMessage(inputStreamData, commandBytes)

    val inputStream = ByteArrayInputStream(inputStreamData.toByteArray())

    val shutdownSignal = CompletableDeferred<Unit>()
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    val sessionHandler =
      SessionHandler(
        inputStream = inputStream,
        outputStream = outputStream,
        crashListener = { throw it },
        shutdownSignal = shutdownSignal,
        serverScope = testScope,
        inspectorBridges = mutableMapOf(),
      )

    sessionHandler.processCommands()
    testScope.cancel()

    val writtenBytes = outputStream.toByteArray()
    val responseBytes = FramingProtocol.readMessage(ByteArrayInputStream(writtenBytes))
    val response = Response.parseFrom(responseBytes)

    assertThat(response.commandId).isEqualTo(1)
    assertThat(response.status).isEqualTo(Response.Status.ERROR)
    assertThat(response.errorMessage).contains("Unknown inspector ID")
  }

  @Test
  fun testHandle_multipleCommands_processesAll() = runBlocking {
    val outputStream = ByteArrayOutputStream()

    // Prepare input data with two commands
    val command1 =
      Command.newBuilder()
        .setCommandId(1)
        .setInspectorMessage(
          com.android.tools.ui.inspector.protocol.UiInspectorProtocol.InspectorMessageCommand.newBuilder()
            .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
            .setPayload(com.android.tools.idea.protobuf.ByteString.copyFrom(byteArrayOf(10)))
            .build()
        )
        .build()

    val command2 =
      Command.newBuilder()
        .setCommandId(2)
        .setInspectorMessage(
          com.android.tools.ui.inspector.protocol.UiInspectorProtocol.InspectorMessageCommand.newBuilder()
            .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
            .setPayload(com.android.tools.idea.protobuf.ByteString.copyFrom(byteArrayOf(20)))
            .build()
        )
        .build()

    val inputStreamData = ByteArrayOutputStream()
    FramingProtocol.writeMessage(inputStreamData, command1.toByteArray())
    FramingProtocol.writeMessage(inputStreamData, command2.toByteArray())

    val inputStream = ByteArrayInputStream(inputStreamData.toByteArray())

    val receivedPayloads = mutableListOf<ByteArray>()
    val mockInspector =
      object : Inspector(mockConnection) {
        override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {
          receivedPayloads.add(data)
          callback.reply(byteArrayOf(30)) // Dummy reply
        }

        override fun onDispose() {}
      }

    val shutdownSignal = CompletableDeferred<Unit>()
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    val bridges = mutableMapOf<String, InspectorBridge>()
    val primaryExecutor = HandlerThreadExecutor("test_thread_multiple", { throw it })
    bridges[ProtocolConstants.VIEW_INSPECTOR_ID] = InspectorBridge(mockInspector, DelegatingConnection(), testScope, primaryExecutor)

    val sessionHandler =
      SessionHandler(
        inputStream = inputStream,
        outputStream = outputStream,
        crashListener = { throw it },
        shutdownSignal = shutdownSignal,
        serverScope = testScope,
        inspectorBridges = bridges,
      )

    sessionHandler.processCommands()
    testScope.cancel()

    assertThat(receivedPayloads).hasSize(2)
    assertThat(receivedPayloads[0]).isEqualTo(byteArrayOf(10))
    assertThat(receivedPayloads[1]).isEqualTo(byteArrayOf(20))

    // Verify responses
    val writtenBytes = outputStream.toByteArray()
    val responseInputStream = ByteArrayInputStream(writtenBytes)

    val responseBytes1 = FramingProtocol.readMessage(responseInputStream)
    val response1 = Response.parseFrom(responseBytes1)
    assertThat(response1.commandId).isEqualTo(1)

    val responseBytes2 = FramingProtocol.readMessage(responseInputStream)
    val response2 = Response.parseFrom(responseBytes2)
    assertThat(response2.commandId).isEqualTo(2)
  }

  @Test
  fun testHandle_getVersion_composeAbsent() {
    runBlocking {
      val outputStream = ByteArrayOutputStream()

      val command =
        Command.newBuilder()
          .setCommandId(1)
          .setGetVersion(GetVersionCommand.newBuilder().addLibraryIds(ProtocolConstants.COMPOSE_UI_LIBRARY_ID).build())
          .build()

      val inputStreamData = ByteArrayOutputStream()
      FramingProtocol.writeMessage(inputStreamData, command.toByteArray())
      val inputStream = ByteArrayInputStream(inputStreamData.toByteArray())

      // Create a mock classloader that explicitly simulates Compose absence
      val mockAbsentClassLoader =
        object : ClassLoader(SessionHandlerTest::class.java.classLoader) {
          override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == "androidx.compose.ui.Modifier") {
              throw ClassNotFoundException("androidx.compose.ui.Modifier")
            }
            return super.loadClass(name, resolve)
          }
        }

      val shutdownSignal = CompletableDeferred<Unit>()
      val testScope = CoroutineScope(Dispatchers.Default + Job())
      val sessionHandler =
        SessionHandler(
          inputStream = inputStream,
          outputStream = outputStream,
          crashListener = { throw it },
          shutdownSignal = shutdownSignal,
          serverScope = testScope,
          inspectorBridges = mutableMapOf(),
          classLoader = mockAbsentClassLoader, // Inject mock absent classloader!
        )

      sessionHandler.processCommands()
      testScope.cancel()

      val writtenBytes = outputStream.toByteArray()
      val responseBytes = FramingProtocol.readMessage(ByteArrayInputStream(writtenBytes))
      val response = Response.parseFrom(responseBytes)

      assertThat(response.commandId).isEqualTo(1)
      assertThat(response.status).isEqualTo(Response.Status.SUCCESS)
      assertThat(response.specializedCase).isEqualTo(Response.SpecializedCase.GET_VERSION)

      val versionResponse = response.getVersion
      assertThat(versionResponse.versionsMap).isEmpty()
    }
  }

  @Test
  fun testHandle_getVersion_composePresent() {
    runBlocking {
      val outputStream = ByteArrayOutputStream()

      val command =
        Command.newBuilder()
          .setCommandId(1)
          .setGetVersion(GetVersionCommand.newBuilder().addLibraryIds(ProtocolConstants.COMPOSE_UI_LIBRARY_ID).build())
          .build()

      val inputStreamData = ByteArrayOutputStream()
      FramingProtocol.writeMessage(inputStreamData, command.toByteArray())
      val inputStream = ByteArrayInputStream(inputStreamData.toByteArray())

      // Create a mock classloader that simulates Compose presence
      val mockClassLoader =
        object : ClassLoader(SessionHandlerTest::class.java.classLoader) {
          override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == "androidx.compose.ui.Modifier") {
              return Any::class.java // return dummy
            }
            return super.loadClass(name, resolve)
          }

          override fun getResourceAsStream(name: String): InputStream? {
            if (name == "META-INF/androidx.compose.ui_ui.version") {
              return ByteArrayInputStream("1.5.4".toByteArray())
            }
            return super.getResourceAsStream(name)
          }
        }

      val shutdownSignal = CompletableDeferred<Unit>()
      val testScope = CoroutineScope(Dispatchers.Default + Job())
      val sessionHandler =
        SessionHandler(
          inputStream = inputStream,
          outputStream = outputStream,
          crashListener = { throw it },
          shutdownSignal = shutdownSignal,
          serverScope = testScope,
          inspectorBridges = mutableMapOf(),
          classLoader = mockClassLoader,
        )

      sessionHandler.processCommands()
      testScope.cancel()

      val writtenBytes = outputStream.toByteArray()
      val responseBytes = FramingProtocol.readMessage(ByteArrayInputStream(writtenBytes))
      val response = Response.parseFrom(responseBytes)

      assertThat(response.commandId).isEqualTo(1)
      assertThat(response.status).isEqualTo(Response.Status.SUCCESS)
      assertThat(response.specializedCase).isEqualTo(Response.SpecializedCase.GET_VERSION)

      val versionResponse = response.getVersion
      assertThat(versionResponse.versionsMap).containsExactly(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.5.4")
    }
  }

  @Test
  fun testHandle_getVersion_unknownLibrary_fails() {
    runBlocking {
      val outputStream = ByteArrayOutputStream()

      val command =
        Command.newBuilder().setCommandId(1).setGetVersion(GetVersionCommand.newBuilder().addLibraryIds("invalid:lib").build()).build()

      val inputStreamData = ByteArrayOutputStream()
      FramingProtocol.writeMessage(inputStreamData, command.toByteArray())
      val inputStream = ByteArrayInputStream(inputStreamData.toByteArray())

      val shutdownSignal = CompletableDeferred<Unit>()
      val testScope = CoroutineScope(Dispatchers.Default + Job())
      val sessionHandler =
        SessionHandler(
          inputStream = inputStream,
          outputStream = outputStream,
          crashListener = { throw it },
          shutdownSignal = shutdownSignal,
          serverScope = testScope,
          inspectorBridges = mutableMapOf(),
        )

      sessionHandler.processCommands()
      testScope.cancel()

      val writtenBytes = outputStream.toByteArray()
      val responseBytes = FramingProtocol.readMessage(ByteArrayInputStream(writtenBytes))
      val response = Response.parseFrom(responseBytes)

      assertThat(response.commandId).isEqualTo(1)
      assertThat(response.status).isEqualTo(Response.Status.ERROR)
      assertThat(response.errorMessage).contains("Unsupported library ID: invalid:lib")
    }
  }
}
