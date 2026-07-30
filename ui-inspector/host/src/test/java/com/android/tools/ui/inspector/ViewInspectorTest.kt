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
            .addNodes(chain)
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

    var current: UiNode = uiDump.roots.single()
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
}
