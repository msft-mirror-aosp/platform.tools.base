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

package com.android.tools.ui.inspector

import com.android.tools.ui.inspector.common.FramingProtocol
import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ViewInspectorTest {

  @Test
  fun testDumpViews_parsesResponseDeeperThanDefaultRecursionLimit(): Unit = runBlocking {
    // A chain of 150 nested ViewNodes: one proto nesting level per View, well past protobuf's default recursion limit of 100.
    val depth = 150
    var chain = ViewInspectorProtocol.ViewNode.newBuilder().setId(depth.toLong()).setClassName(1)
    for (id in depth - 1 downTo 1) {
      chain = ViewInspectorProtocol.ViewNode.newBuilder().setId(id.toLong()).setClassName(1).addChildren(chain)
    }
    val viewResponse =
      ViewInspectorProtocol.Response.newBuilder()
        .setDumpViewsResponse(
          ViewInspectorProtocol.DumpViewsResponse.newBuilder()
            .addStrings(ViewInspectorProtocol.StringEntry.newBuilder().setId(1).setValue("android.widget.FrameLayout"))
            .addWindows(ViewInspectorProtocol.WindowInfo.newBuilder().setRoot(chain))
        )
        .build()

    val serverSocket = ServerSocket(0)
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    testScope.launch {
      serverSocket.accept().use { socket ->
        val cmd = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(socket.getInputStream()))
        val response =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
                .setPayload(ByteString.copyFrom(viewResponse.toByteArray()))
            )
            .build()
        val agentMessage = UiInspectorProtocol.AgentMessage.newBuilder().setResponse(response).build()
        FramingProtocol.writeMessage(socket.getOutputStream(), agentMessage.toByteArray())
      }
    }

    val uiDump =
      CommandSender.connect("127.0.0.1", serverSocket.localPort, this).use { commandSender ->
        dumpViews(commandSender, includeAttributes = false, includeResolutionStack = false)
      }

    var current: UiNode = uiDump.windows.single().root
    var nodeCount = 1
    while (current.children.isNotEmpty()) {
      current = current.children.single()
      nodeCount++
    }
    assertThat(nodeCount).isEqualTo(depth)
    assertThat(current.className).isEqualTo("android.widget.FrameLayout")

    testScope.cancel()
    serverSocket.close()
  }

  @Test
  fun testDumpViews_resolvesDimensionsFromEachWindowConfiguration(): Unit = runBlocking {
    fun window(id: Long, className: Int, density: Int, fontScale: Float, theme: Int) =
      ViewInspectorProtocol.WindowInfo.newBuilder()
        .setRoot(
          ViewInspectorProtocol.ViewNode.newBuilder()
            .setId(id)
            .setClassName(className)
            .addAttributes(
              ViewInspectorProtocol.ViewNode.Attribute.newBuilder()
                .setName(3)
                .setType(ViewInspectorProtocol.ViewNode.Attribute.Type.DIMENSION)
                .setFloatValue(420f)
            )
            .addAttributes(
              ViewInspectorProtocol.ViewNode.Attribute.newBuilder()
                .setName(4)
                .setType(ViewInspectorProtocol.ViewNode.Attribute.Type.DIMENSION)
                .setFloatValue(420f)
            )
        )
        .setConfiguration(ViewInspectorProtocol.Configuration.newBuilder().setDensity(density).setFontScale(fontScale))
        .setTheme(theme)

    val response =
      ViewInspectorProtocol.Response.newBuilder()
        .setDumpViewsResponse(
          ViewInspectorProtocol.DumpViewsResponse.newBuilder()
            .addStrings(ViewInspectorProtocol.StringEntry.newBuilder().setId(1).setValue("Window160"))
            .addStrings(ViewInspectorProtocol.StringEntry.newBuilder().setId(2).setValue("Window420"))
            .addStrings(ViewInspectorProtocol.StringEntry.newBuilder().setId(3).setValue("layout_width"))
            .addStrings(ViewInspectorProtocol.StringEntry.newBuilder().setId(4).setValue("textSize"))
            .addStrings(ViewInspectorProtocol.StringEntry.newBuilder().setId(5).setValue("@style/Theme.One"))
            .addStrings(ViewInspectorProtocol.StringEntry.newBuilder().setId(6).setValue("@style/Theme.Two"))
            .addWindows(window(id = 1, className = 1, density = 160, fontScale = 1.0f, theme = 5))
            .addWindows(window(id = 2, className = 2, density = 420, fontScale = 2.0f, theme = 6))
            .addDisplays(ViewInspectorProtocol.Display.newBuilder().setId(0).setWidthPx(1080).setHeightPx(1920))
        )
        .build()

    val dump = exchangeDumpResponse(response)

    assertThat(dump.windows).hasSize(2)
    assertThat(dump.displays).containsExactly(DisplayInfo(id = 0, widthPx = 1080, heightPx = 1920, orientation = null))
    val first = dump.windows[0]
    val second = dump.windows[1]
    assertThat(first.theme).isEqualTo("@style/Theme.One")
    assertThat(second.theme).isEqualTo("@style/Theme.Two")
    assertThat(first.configuration?.density).isEqualTo(Dimension.Dpi(160))
    assertThat(second.configuration?.density).isEqualTo(Dimension.Dpi(420))
    assertThat(first.root.attributes[0].value).isEqualTo(UiNode.AttributeValue.DimensionVal(420f, dp = 420f, sp = null))
    assertThat(first.root.attributes[1].value).isEqualTo(UiNode.AttributeValue.DimensionVal(420f, dp = null, sp = 420f))
    assertThat(second.root.attributes[0].value).isEqualTo(UiNode.AttributeValue.DimensionVal(420f, dp = 160f, sp = null))
    assertThat(second.root.attributes[1].value).isEqualTo(UiNode.AttributeValue.DimensionVal(420f, dp = null, sp = 80f))
  }

  @Test
  fun testDumpViews_rejectsWindowWithoutRoot() {
    val response =
      ViewInspectorProtocol.Response.newBuilder()
        .setDumpViewsResponse(
          ViewInspectorProtocol.DumpViewsResponse.newBuilder().addWindows(ViewInspectorProtocol.WindowInfo.newBuilder().setTheme(1))
        )
        .build()

    val exception = org.junit.Assert.assertThrows(IllegalStateException::class.java) { runBlocking { exchangeDumpResponse(response) } }
    assertThat(exception).hasMessageThat().isEqualTo("Window at index 0 is missing its root")
  }

  private suspend fun exchangeDumpResponse(viewResponse: ViewInspectorProtocol.Response): UiDump {
    val serverSocket = ServerSocket(0)
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    testScope.launch {
      serverSocket.accept().use { socket ->
        val cmd = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(socket.getInputStream()))
        val response =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
                .setPayload(ByteString.copyFrom(viewResponse.toByteArray()))
            )
            .build()
        FramingProtocol.writeMessage(
          socket.getOutputStream(),
          UiInspectorProtocol.AgentMessage.newBuilder().setResponse(response).build().toByteArray(),
        )
      }
    }
    return try {
      CommandSender.connect("127.0.0.1", serverSocket.localPort, testScope).use { commandSender ->
        dumpViews(commandSender, includeAttributes = true, includeResolutionStack = false)
      }
    } finally {
      testScope.cancel()
      serverSocket.close()
    }
  }
}
