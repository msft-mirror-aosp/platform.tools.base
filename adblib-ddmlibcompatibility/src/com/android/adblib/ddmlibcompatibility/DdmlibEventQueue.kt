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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Allows calling ddmlib listeners asynchronously
 * * [runDispatcher] starts the loop that dispatches events posted with [post]. This should be called once and dispatches events as long as
 *   the caller's [CoroutineScope] is active.
 * * [post] enqueue a action to be called asynchronously by the [runDispatcher] loop.
 */
internal class DdmlibEventQueue(logger: AdbLogger, name: String) {

  private val logger = logger.withPrefix("DDMLIB EventQueue '$name': ")

  /**
   * We limit to [QUEUE_CAPACITY] events in case a ddmlib handler is slowing down event dispatching. When the limit is reached,
   * [posting][post] events is throttled.
   */
  private val queue = Channel<Event>(QUEUE_CAPACITY)

  suspend fun post(scope: CoroutineScope, name: String, handler: () -> Unit) {
    queue.send(Event(scope, name, handler))
  }

  suspend fun runDispatcher() {
    queue.receiveAsFlow().collect { event ->
      event.scope
        .launch {
          runCatching {
              logger.verbose { "Invoking ddmlib listener '${event.name}'" }
              event.handler()
              logger.verbose { "Invoking ddmlib listener '${event.name}' - done" }
            }
            .onFailure { throwable -> logger.warn(throwable, "Invoking ddmlib listener '${event.name}' threw an exception: $throwable") }
        }
        .join()
    }
  }

  private class Event(val scope: CoroutineScope, val name: String, val handler: () -> Unit)

  companion object {

    const val QUEUE_CAPACITY = 1_000
  }
}
