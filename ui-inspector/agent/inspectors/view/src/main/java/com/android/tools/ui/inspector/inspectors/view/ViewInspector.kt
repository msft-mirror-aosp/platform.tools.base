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

import androidx.inspection.Connection
import androidx.inspection.Inspector
import androidx.inspection.InspectorEnvironment
import androidx.inspection.InspectorFactory
import com.android.tools.ui.inspector.protocol.ViewInspectorProtocol.Command
import com.android.tools.ui.inspector.protocol.ViewInspectorProtocol.Event
import com.android.tools.ui.inspector.protocol.ViewInspectorProtocol.HelloEvent
import com.android.tools.ui.inspector.protocol.ViewInspectorProtocol.HelloResponse
import com.android.tools.ui.inspector.protocol.ViewInspectorProtocol.Response
import com.android.tools.ui.inspector.protocol.ViewInspectorProtocol.TriggerEventResponse

const val INSPECTOR_ID = "ui.inspector.payload.view.inspector"

class ViewInspectorFactory : InspectorFactory<ViewInspector>(INSPECTOR_ID) {
  override fun createInspector(connection: Connection, environment: InspectorEnvironment) = ViewInspector(connection, environment)
}

class ViewInspector(connection: Connection, private val environment: InspectorEnvironment) : Inspector(connection) {
  override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {
    val command = Command.parseFrom(data)
    when (command.specializedCase) {
      Command.SpecializedCase.HELLO_COMMAND -> handleHelloCommand(callback)
      Command.SpecializedCase.TRIGGER_EVENT_COMMAND -> handleTriggerEventCommand(callback)
      else -> error("Unknown command: ${command.specializedCase}")
    }
  }

  private fun handleHelloCommand(callback: CommandCallback) {
    callback.reply { helloResponse = HelloResponse.getDefaultInstance() }
  }

  private fun handleTriggerEventCommand(callback: CommandCallback) {
    connection.sendEvent { helloEvent = HelloEvent.newBuilder().setMessage("hello event").build() }
    callback.reply { triggerEventResponse = TriggerEventResponse.getDefaultInstance() }
  }

  override fun onDispose() {}
}

private fun Inspector.CommandCallback.reply(initResponse: Response.Builder.() -> Unit) {
  val response = Response.newBuilder()
  response.initResponse()
  reply(response.build().toByteArray())
}

private fun Connection.sendEvent(init: Event.Builder.() -> Unit) {
  sendEvent(Event.newBuilder().apply { init() }.build().toByteArray())
}
