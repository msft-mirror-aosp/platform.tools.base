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

import androidx.inspection.Connection
import androidx.inspection.Inspector
import androidx.inspection.InspectorEnvironment
import androidx.inspection.InspectorFactory

const val INSPECTOR_ID = "ui.inspector.payload.view.inspector"

class ViewInspectorFactory : InspectorFactory<ViewInspector>(INSPECTOR_ID) {
  override fun createInspector(connection: Connection, environment: InspectorEnvironment) = ViewInspector(connection, environment)
}

class ViewInspector(connection: Connection, private val environment: InspectorEnvironment) : Inspector(connection) {
  override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {
    val message = String(data)
    when (message) {
      "hello" -> callback.reply("world".toByteArray())
      "trigger_event" -> {
        connection.sendEvent("hello event".toByteArray())
        callback.reply("event triggered".toByteArray())
      }
      else -> callback.reply("unknown command".toByteArray())
    }
  }

  override fun onDispose() {}
}
