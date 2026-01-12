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
package com.android.processmonitor.monitor

import com.android.adblib.testing.FakeAdbLoggerFactory
import com.android.processmonitor.common.ProcessEvent
import com.android.processmonitor.common.ProcessTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SharedProcessTrackerTest {

    private val logger = FakeAdbLoggerFactory().logger

    @Test
    fun trackProcesses_doesNotCrashScope_whenDelegateThrows() = runBlocking {
        // Setup
        val exceptionProcessed = CompletableDeferred<Unit>()
        val delegate = ProcessTracker {
            flow<ProcessEvent> {
                throw IOException("Test exception")
            }.onCompletion {
                exceptionProcessed.complete(Unit)
            }
        }

        val tracker = SharedProcessTracker(this, delegate, logger)

        // Act
        val collectorJob = launch {
            tracker.trackProcesses().collect { }
        }

        withTimeout(1000) {
            exceptionProcessed.await()
        }

        // Give the scope a chance to be canceled if the exception
        // wasn't caught
        delay(100)

        // Assert
        // Collecting from a shared flow remains active as delegate
        // flow exception shouldn't crash the shared flow.
        assertTrue(collectorJob.isActive)
        collectorJob.cancel()
    }
}
