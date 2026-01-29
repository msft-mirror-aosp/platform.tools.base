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
package com.android.adblib.tools.testutils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job

/**
 * Similar to [CoroutineScope.async], but does not propagate the exception to the parent job. This is useful for unit test, where we
 * sometimes want to test an `async` call can fail with an exception, while at the same time we don't want to coroutine scope of the test to
 * fail.
 *
 * In the example below, using [CoroutineScope.async] instead of [CoroutineScope.asyncNoThrow] would result in the test failing with the
 * exception thrown by `foo`, even though the `await` call runs inside a `runCatching`.
 *
 *     fun myTest = runBlocking {
 *         val foo = asyncNoThrow() {
 *             (... code that throws...)
 *         }
 *         runCatching {
 *             foo.await()
 *         }.onFailure { (...) }
 *         .onSuccess { (...) }
 *     }
 */
internal fun <T> CoroutineScope.asyncNoThrow(block: suspend CoroutineScope.() -> T): Deferred<T> {
  // Create a supervisor job so that a failure does not affect this coroutine scope
  val supervisor = SupervisorJob(this.coroutineContext.job)

  // Run the block in the new supervisor job context
  return async(supervisor) { block() }
    .also {
      it.invokeOnCompletion {
        // We cancel the supervisor job so that this coroutine scope
        // is not blocked waiting for the supervisor job to complete.
        supervisor.cancel("asyncNoThrow job has completed")
      }
    }
}
