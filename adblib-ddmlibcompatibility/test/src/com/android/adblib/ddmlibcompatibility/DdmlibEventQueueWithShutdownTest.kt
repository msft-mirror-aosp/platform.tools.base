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
import com.android.adblib.testingutils.CoroutineTestUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.util.concurrent.atomic.AtomicInteger

class DdmlibEventQueueWithShutdownTest {

    @JvmField
    @Rule
    val exceptionRule: ExpectedException = ExpectedException.none()

    private val logger = FakeAdbLoggerFactory()
    private val eventQueue = DdmlibEventQueueWithShutdown(logger.logger, "testQueue")

    @Test
    fun queueProcessingWorks(): Unit = runBlocking {
        coroutineScope {
            // Prepare
            val handlerExecutedCount = AtomicInteger()

            val dispatcherJob = launch { eventQueue.runDispatcher() }

            // Act
            eventQueue.post(this, "handlerName") {
                handlerExecutedCount.incrementAndGet()
            }
            eventQueue.post(this, "handlerName") {
                handlerExecutedCount.incrementAndGet()
            }

            // Assert
            eventQueue.shutdown()
            dispatcherJob.join()
            assertEquals(2, handlerExecutedCount.get())
        }
    }

    @Test
    fun queueEventNotProcessedIfItsScopeIsCancelled(): Unit = runBlocking {
        coroutineScope {
            // Prepare
            val handlerExecutedCount = AtomicInteger()
            val dispatcherJob = launch { eventQueue.runDispatcher() }
            val cancelledScope =
                CoroutineScope(coroutineContext + SupervisorJob()).also { it.cancel() }

            // Act
            eventQueue.post(cancelledScope, "handlerName") {
                handlerExecutedCount.incrementAndGet()
            }

            // Assert
            eventQueue.shutdown()
            dispatcherJob.join()
            assertEquals(0, handlerExecutedCount.get())
        }
    }

    @Test
    fun eventsAreStillProcessedAfterShuttingDownTheQueue(): Unit = runBlocking {
        coroutineScope {
            // Prepare
            val handlerExecutedCount = AtomicInteger()
            val dispatcherJob = launch { eventQueue.runDispatcher() }

            // Act: Post an event
            eventQueue.post(this, "handlerName") {
                handlerExecutedCount.incrementAndGet()
            }
            eventQueue.shutdown()

            // Assert
            dispatcherJob.join()
            assertEquals(1, handlerExecutedCount.get())
        }
    }

    @Test
    fun postingToAShutdownQueueThrows(): Unit = runBlocking {
        coroutineScope {
            // Prepare
            exceptionRule.expect(ClosedSendChannelException::class.java)
            eventQueue.shutdown()

            // Act: Posting to a queue after the shutdown should throw
            eventQueue.post(this, "handlerName") {}

            // Assert:
            fail("Should not reach")
        }
    }

    @Test
    fun postToTheQueueMaintainsExecutionOrder(): Unit = runBlocking {
        coroutineScope {
            // Prepare
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
            CoroutineTestUtils.yieldUntil { handlerExecutedCount.get() == numEvents }
            assertArrayEquals((1..numEvents).toList().toIntArray(), executionOrder.toIntArray())
            eventQueue.shutdown()
            dispatcherJob.join()
            assertEquals(numEvents, handlerExecutedCount.get())
        }
    }

    @Test
    fun queueProcessingContinuesEvenIfOneHandlerThrows(): Unit = runBlocking {
        coroutineScope {
            // Prepare
            val handlerExecutedCount = AtomicInteger()
            val dispatcherJob = launch { eventQueue.runDispatcher() }

            // Act
            // Post an event which throws
            eventQueue.post(this, "handlerName") {
                throw RuntimeException("my test exception")
            }
            // Post another event that doesn't throw
            eventQueue.post(this, "handlerName") {
                handlerExecutedCount.incrementAndGet()
            }

            // Assert
            eventQueue.shutdown()
            dispatcherJob.join()
            assertEquals(1, handlerExecutedCount.get())
            assertTrue(logger.logEntries.any { entry -> entry.message.contains("my test exception") })
        }
    }
}
