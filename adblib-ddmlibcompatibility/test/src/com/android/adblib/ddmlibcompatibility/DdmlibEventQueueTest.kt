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

import com.android.adblib.testing.FakeAdbLoggerFactory
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DdmlibEventQueueTest {

  @Test
  fun queueProcessingWorks(): Unit = runBlockingWithTimeout {
    // Prepare
    val logger = FakeAdbLoggerFactory()
    val eventQueue = DdmlibEventQueue(logger.logger, "testQueue")
    val handlerExecutedCount = AtomicInteger()
    val dispatcherJob = launch { eventQueue.runDispatcher() }

    // Act
    eventQueue.post(this, "handlerName") { handlerExecutedCount.incrementAndGet() }
    eventQueue.post(this, "handlerName") { handlerExecutedCount.incrementAndGet() }

    // Assert
    yieldUntil { handlerExecutedCount.get() == 2 }
    dispatcherJob.cancel()
    assertEquals(2, handlerExecutedCount.get())
  }

  @Test
  fun queueEventNotProcessedIfItsScopeIsCancelled(): Unit = runBlockingWithTimeout {
    // Prepare
    val logger = FakeAdbLoggerFactory()
    val eventQueue = DdmlibEventQueue(logger.logger, "testQueue")
    val handlerExecutedCount = AtomicInteger()
    val dispatcherJob = launch { eventQueue.runDispatcher() }
    val cancelledScope = CoroutineScope(coroutineContext + SupervisorJob()).also { it.cancel() }

    // Act
    eventQueue.post(cancelledScope, "handlerName") { handlerExecutedCount.incrementAndGet() }
    delay(100)

    // Assert
    dispatcherJob.cancel()
    assertEquals(0, handlerExecutedCount.get())
  }

  @Test
  fun postToTheQueueMaintainsExecutionOrder(): Unit = runBlockingWithTimeout {
    // Prepare
    val logger = FakeAdbLoggerFactory()
    val eventQueue = DdmlibEventQueue(logger.logger, "testQueue")
    val executionOrder = mutableListOf<Int>()
    val numEvents = 5
    val handlerExecutedCount = AtomicInteger()

    val dispatcherJob = launch { eventQueue.runDispatcher() }

    // Act: Post an event
    for (i in 1..numEvents) {
      eventQueue.post(this, "handlerName") {
        executionOrder.add(i)
        handlerExecutedCount.incrementAndGet()
      }
    }

    // Assert
    yieldUntil { handlerExecutedCount.get() == numEvents }
    assertArrayEquals((1..numEvents).toList().toIntArray(), executionOrder.toIntArray())
    dispatcherJob.cancel()
    assertEquals(numEvents, handlerExecutedCount.get())
  }

  @Test
  fun queueProcessingContinuesEvenIfOneHandlerThrows(): Unit = runBlockingWithTimeout {
    // Prepare
    val logger = FakeAdbLoggerFactory()
    val eventQueue = DdmlibEventQueue(logger.logger, "testQueue")
    val handlerExecutedCount = AtomicInteger()
    val dispatcherJob = launch { eventQueue.runDispatcher() }

    // Act
    // Post an event which throws
    eventQueue.post(this, "handlerName") {
      handlerExecutedCount.incrementAndGet()
      throw RuntimeException("my test exception")
    }
    // Post another event that doesn't throw
    eventQueue.post(this, "handlerName") { handlerExecutedCount.incrementAndGet() }

    // Assert
    yieldUntil { handlerExecutedCount.get() == 2 }
    dispatcherJob.cancel()
    assertEquals(2, handlerExecutedCount.get())
    assertTrue(logger.logEntries.any { entry -> entry.message.contains("my test exception") })
  }
}
