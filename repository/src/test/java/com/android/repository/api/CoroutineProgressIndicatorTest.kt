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
package com.android.repository.api

import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test

class CoroutineProgressIndicatorTest {

  @Test
  fun isCanceled() {
    val coroutineScope = CoroutineScope(EmptyCoroutineContext)
    val progress = CoroutineProgressIndicator(coroutineScope.coroutineContext, NullProgressIndicator)

    val result = coroutineScope.async { sleepCancellable(progress) }

    Thread.sleep(200)
    coroutineScope.cancel()

    try {
      runBlocking { result.await() }
    } catch (expected: CancellationException) {}
  }

  @Test
  fun cancel() {
    val coroutineScope = CoroutineScope(EmptyCoroutineContext)
    val progress = CoroutineProgressIndicator(coroutineScope.coroutineContext, NullProgressIndicator)

    progress.cancel()

    assertThat(coroutineScope.isActive).isFalse()
  }

  private fun sleepCancellable(progressIndicator: ProgressIndicator) {
    repeat(5) {
      if (progressIndicator.isCanceled) {
        return
      }
      Thread.sleep(1000)
    }
    fail("Should have been cancelled")
  }
}
