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

package com.android.builder.dexing

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Test

class NonShuttingDownExecutorServiceTest {

  private val delegate: ExecutorService = Executors.newFixedThreadPool(1)

  @After
  fun tearDown() {
    delegate.shutdownNow()
  }

  @Test
  fun submitsToDelegate() {
    val wrapper = NonShuttingDownExecutorService(delegate)
    val threadName = wrapper.submit<String> { Thread.currentThread().name }.get(10, TimeUnit.SECONDS)
    assertThat(threadName).isNotEqualTo(Thread.currentThread().name)
  }

  @Test
  fun shutdown_keepsDelegateOpenAndReportsTermination() {
    val wrapper = NonShuttingDownExecutorService(delegate)
    assertThat(wrapper.isShutdown).isFalse()
    assertThat(wrapper.isTerminated).isFalse()

    wrapper.shutdown()

    assertThat(delegate.isShutdown).isFalse()
    assertThat(wrapper.isShutdown).isTrue()
    assertThat(wrapper.isTerminated).isTrue()
    // Returns immediately instead of waiting for the still-running delegate.
    assertThat(wrapper.awaitTermination(1, TimeUnit.DAYS)).isTrue()
    assertThat(delegate.submit<Int> { 42 }.get(10, TimeUnit.SECONDS)).isEqualTo(42)
  }

  @Test
  fun shutdownNow_keepsDelegateOpen() {
    val wrapper = NonShuttingDownExecutorService(delegate)

    assertThat(wrapper.shutdownNow()).isEmpty()

    assertThat(delegate.isShutdown).isFalse()
    assertThat(wrapper.isTerminated).isTrue()
    assertThat(delegate.submit<Int> { 42 }.get(10, TimeUnit.SECONDS)).isEqualTo(42)
  }

  @Test
  fun close_returnsPromptlyAndKeepsDelegateOpen() {
    // ExecutorService.close() only exists on JDK 19+, where its default implementation calls shutdown() and then awaitTermination() until
    // the executor reports termination. Call it reflectively so this test also compiles and runs on older JDKs.
    val close = runCatching { ExecutorService::class.java.getMethod("close") }.getOrNull() ?: return
    val wrapper = NonShuttingDownExecutorService(delegate)

    val closer = Thread { close.invoke(wrapper) }
    closer.start()
    closer.join(TimeUnit.SECONDS.toMillis(30))

    assertThat(closer.isAlive).isFalse()
    assertThat(delegate.isShutdown).isFalse()
    assertThat(delegate.submit<Int> { 42 }.get(10, TimeUnit.SECONDS)).isEqualTo(42)
  }
}
