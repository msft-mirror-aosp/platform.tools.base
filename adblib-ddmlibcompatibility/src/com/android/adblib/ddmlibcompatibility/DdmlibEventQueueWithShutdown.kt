/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.adblib.ddmlibcompatibility

import com.android.adblib.AdbLogger
import com.android.adblib.withPrefix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class DdmlibEventQueueWithShutdown(logger: AdbLogger, name: String) {

  private val logger = logger.withPrefix("DDMLIB EventQueue '$name': ")
  private val dispatcherIsRunning = MutableStateFlow(false)

  /**
   * We limit to [QUEUE_CAPACITY] events in case a ddmlib handler is slowing down event dispatching. When the limit is reached,
   * [posting][post] events is throttled.
   */
  private val queue = Channel<Event>(QUEUE_CAPACITY)

  suspend fun post(scope: CoroutineScope, name: String, handler: () -> Unit) {
    queue.send(Event(scope, name, handler))
  }

  /**
   * Reads from the channel and processes each `Event` until the coroutine stops or the channel is closed. Note that when the channel is
   * closed, elements already in the queue will still be processed, but attempting to post a new event will throw an exception.
   */
  suspend fun runDispatcher() {
    dispatcherIsRunning.value = true
    queue.receiveAsFlow().collect { event ->
      event.scope
        .launch {
          kotlin
            .runCatching {
              logger.verbose { "Invoking ddmlib listener '${event.name}'" }
              event.handler()
              logger.verbose { "Invoking ddmlib listener '${event.name}' - done" }
            }
            .onFailure { throwable -> logger.warn(throwable, "Invoking ddmlib listener '${event.name}' threw an exception: $throwable") }
        }
        .join()
    }
    dispatcherIsRunning.value = false
  }

  /**
   * Closes the queue channel and waits until the dispatcher processes all the elements already queued up (given that the dispatched is
   * running).
   */
  suspend fun shutdown() {
    queue.close()

    // Wait until dispatcher is done processing
    dispatcherIsRunning.first { !it }
  }

  private class Event(val scope: CoroutineScope, val name: String, val handler: () -> Unit)

  companion object {

    const val QUEUE_CAPACITY = 1_000
  }
}
