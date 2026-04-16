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

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppInspectionUtilsTest {

  @Test
  fun testCreateInspectorEnvironment_ioExecutorDelegates() {
    val primaryExecutor = HandlerThreadExecutor("test-thread") {}
    val environment = createInspectorEnvironment(primaryExecutor) {}

    val latch = CountDownLatch(1)
    environment.executors().io().execute { latch.countDown() }

    val completed = latch.await(5, TimeUnit.SECONDS)
    assertThat(completed).isTrue()
    primaryExecutor.quitSafely()
  }

  @Test
  fun testCreateInspectorEnvironment_ioExecutorCatchesException() {
    val primaryExecutor = HandlerThreadExecutor("test-thread") {}
    var caughtThrowable: Throwable? = null
    val latch = CountDownLatch(1)
    val exception = RuntimeException("Test exception")

    val environment =
      createInspectorEnvironment(primaryExecutor) { t ->
        caughtThrowable = t
        latch.countDown()
      }

    environment.executors().io().execute { throw exception }

    val completed = latch.await(5, TimeUnit.SECONDS)
    assertThat(completed).isTrue()
    assertThat(caughtThrowable).isEqualTo(exception)
    primaryExecutor.quitSafely()
  }
}
