/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.adblib.utils

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Same as [launch], except cancellation from the child coroutine [block] is propagated to the parent coroutine (scope).
 *
 * The behavior is the same as [launch] wrt the following aspects:
 * * The parent coroutine won't complete until the child coroutine [block] completes.
 * * The parent coroutine fails with an exception if the child coroutine [block] throws an exception.
 */
inline fun CoroutineScope.launchCancellable(
  context: CoroutineContext = EmptyCoroutineContext,
  start: CoroutineStart = CoroutineStart.DEFAULT,
  crossinline block: suspend CoroutineScope.() -> Unit,
): Job {
  return launch(context, start) {
    try {
      block()
    } catch (e: CancellationException) {
      // Note: this is a no-op is the parent scope is already cancelled
      this@launchCancellable.cancel(e)
      throw e
    }
  }
}

/**
 * Creates a child [CoroutineScope] of this scope.
 *
 * @param isSupervisor whether to use a regular [Job] or a [SupervisorJob]
 * @param context [CoroutineContext] to apply in addition to the parent scope [CoroutineContext]
 */
fun CoroutineScope.createChildScope(isSupervisor: Boolean = false, context: CoroutineContext = EmptyCoroutineContext): CoroutineScope {
  val newJob =
    if (isSupervisor) {
      SupervisorJob(this.coroutineContext.job)
    } else {
      Job(this.coroutineContext.job)
    }
  return CoroutineScope(this.coroutineContext + newJob + context)
}

/** Runs [block] as a regular `suspend` function, except that it gets cancelled when [otherScope] is cancelled. */
suspend inline fun <R> runAlongOtherScope(otherScope: CoroutineScope, crossinline block: suspend () -> R): R {
  // Attach a completion handler that cancels this coroutine when "otherScope" is cancelled
  // The completion handler is removed as soon as the execution of `block` ends, so that
  // we don't cancel the caller at some point later in the execution path.
  val currentJob = currentCoroutineContext().job
  val handler =
    otherScope.coroutineContext.job.invokeOnCompletion { throwable ->
      when (throwable) {
        is CancellationException -> {
          currentJob.cancel(throwable)
        }

        null -> {
          /* Nothing to do */
        }

        else -> {
          currentJob.cancel(CancellationException(throwable.message, throwable))
        }
      }
    }

  return try {
    block()
  } finally {
    handler.dispose()
  }
}
