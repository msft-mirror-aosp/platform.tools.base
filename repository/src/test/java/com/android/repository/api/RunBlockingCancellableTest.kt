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
package com.android.repository.api

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunBlockingCancellableTest {

  private class TestCancellationException : RuntimeException()

  @Test
  fun returnsResultWithoutCheckingCancellationWhenNotSuspended() {
    val checks = AtomicInteger(0)
    val result = runBlockingCancellable(checkCanceled = { checks.incrementAndGet() }) { 42 }
    assertThat(result).isEqualTo(42)
    assertThat(checks.get()).isEqualTo(0)
  }

  @Test
  fun returnsResultAfterSuspending() {
    val checks = AtomicInteger(0)
    val result =
      runBlockingCancellable(checkCanceled = { checks.incrementAndGet() }) {
        delay(200)
        "done"
      }
    assertThat(result).isEqualTo("done")
    assertThat(checks.get()).isGreaterThan(0)
  }

  @Test
  fun propagatesExceptionFromBlock() {
    class BlockException : RuntimeException()
    assertThrows(BlockException::class.java) {
      runBlockingCancellable(checkCanceled = {}) { throw BlockException() }
    }
    assertThrows(BlockException::class.java) {
      runBlockingCancellable(checkCanceled = {}) {
        delay(50)
        throw BlockException()
      }
    }
  }

  @Test
  fun cancelsSuspendedBlockWhenCheckCanceledThrows() {
    val cancelled = AtomicBoolean(false)
    val blockSuspended = CountDownLatch(1)
    val blockWasCancelled = AtomicBoolean(false)
    val neverCompletes = CompletableDeferred<Unit>()

    val thread = Thread {
      assertTrue(blockSuspended.await(5, TimeUnit.SECONDS))
      cancelled.set(true)
    }
    thread.start()

    assertThrows(TestCancellationException::class.java) {
      runBlockingCancellable(checkCanceled = { if (cancelled.get()) throw TestCancellationException() }) {
        try {
          blockSuspended.countDown()
          neverCompletes.await()
        } catch (e: CancellationException) {
          blockWasCancelled.set(true)
          throw e
        }
      }
    }
    thread.join()
    assertThat(blockWasCancelled.get()).isTrue()
  }
}
