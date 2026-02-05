/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.adblib.tools.debugging.processinventory

import com.android.adblib.AdbSession
import com.android.adblib.tools.debugging.ExternalJdwpProcessPropertiesCollector
import com.android.adblib.tools.debugging.ExternalJdwpProcessPropertiesCollectorFactory
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.addExternalJdwpProcessPropertiesCollectorFactory
import com.android.adblib.tools.debugging.processinventory.impl.ProcessInventoryJdwpProcessPropertiesCollector
import com.android.adblib.tools.debugging.processinventory.server.ProcessInventoryServer

/**
 * The main entry point for enabling synchronization of JDWP process properties with a [ProcessInventoryServer].
 *
 * Use [AdbSession.installProcessInventoryJdwpProcessPropertiesCollectorFactory] to activate this service for a given [AdbSession].
 */
internal class ProcessInventoryJdwpProcessPropertiesCollectorFactory(
  private val serverConnection: ProcessInventoryServerConnection,
  private val enabled: () -> Boolean,
) : ExternalJdwpProcessPropertiesCollectorFactory {

  override suspend fun create(process: JdwpProcess): ExternalJdwpProcessPropertiesCollector? {
    return if (enabled()) ProcessInventoryJdwpProcessPropertiesCollector(serverConnection, process)
    else {
      null
    }
  }

  override fun close() {
    serverConnection.close()
  }
}

/** Activates a [ProcessInventoryJdwpProcessPropertiesCollectorFactory] for this [AdbSession] */
fun AdbSession.installProcessInventoryJdwpProcessPropertiesCollectorFactory(
  serverConnection: ProcessInventoryServerConnection,
  enabled: () -> Boolean,
) {
  val factory = ProcessInventoryJdwpProcessPropertiesCollectorFactory(serverConnection, enabled)
  // Note: We don't need to remove, as lifetime is tied to the AdbSession lifetime.
  this.addExternalJdwpProcessPropertiesCollectorFactory(factory)
}
