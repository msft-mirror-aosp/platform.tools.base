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

import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import java.util.concurrent.Executor

/**
 * An executor that runs tasks on a dedicated [HandlerThread]. It replicates the behavior of App Inspection's HandlerThreadExecutor to
 * ensure sequential execution and safe exception handling.
 */
class HandlerThreadExecutor(name: String, private val crashListener: (Throwable) -> Unit) : Executor {

  private val thread = HandlerThread(name).apply { start() }

  val handler =
    object : Handler(thread.looper) {
      override fun dispatchMessage(msg: Message) {
        if (msg.callback != null) {
          // Catch all exceptions to prevent errors in the inspector from crashing the target app.
          try {
            msg.callback.run()
          } catch (t: Throwable) {
            crashListener(t)
          }
        } else {
          super.dispatchMessage(msg)
        }
      }
    }

  override fun execute(command: Runnable) {
    handler.post(command)
  }

  fun quitSafely() {
    thread.quitSafely()
  }
}
