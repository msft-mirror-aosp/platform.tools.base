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

import android.util.Log
import androidx.inspection.Inspector
import com.android.tools.ui.inspector.common.FramingProtocol
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor

private const val TAG = "studio.InspectorMessenger"

/**
 * Bridges communication between the host and an [Inspector].
 *
 * It receives raw commands from the host, delegates them to the [Inspector], and sends the inspector's replies back to the host using
 * [FramingProtocol].
 */
class InspectorMessenger(
  private val outputStream: OutputStream,
  private val inspector: Inspector,
  private val crashListener: (Throwable) -> Unit,
) {
  fun handleCommand(command: ByteArray) {
    inspector.onReceiveCommand(command, Callback(outputStream, crashListener))
  }
}

private class Callback(private val outputStream: OutputStream, private val crashListener: (Throwable) -> Unit) : Inspector.CommandCallback {
  override fun reply(response: ByteArray) {
    try {
      FramingProtocol.writeMessage(outputStream, response)
    } catch (e: IOException) {
      Log.e(TAG, "IO error sending reply", e)
    } catch (e: Exception) {
      Log.e(TAG, "Unexpected error sending reply", e)
      crashListener(e)
    }
  }

  override fun addCancellationListener(executor: Executor, runnable: Runnable) {
    // Not supported for now
  }
}
