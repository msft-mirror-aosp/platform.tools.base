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
package com.android.adblib.tools.debugging.utils

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.tools.testutils.AdbLibToolsTestBase
import com.android.adblib.utils.createChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import org.junit.Assert
import org.junit.Test

class StateFlowForwarderTest : AdbLibToolsTestBase() {

    @Test
    fun testDestinationFlowIsUpdatedWhenSourceFlowIsUpdated(): Unit = runBlockingWithTimeout {
        // Prepare
        val sourceFlow = MutableStateFlow(0)
        val scope = session.scope.createCloseableScope(isSupervisor = true)
        val forwarder = createStateFlowForwarder(scope, sourceFlow)
        val repeatCount = 10
        val valuesSeen = mutableListOf<Int>()
        val job1 = async {
            forwarder.stateFlow
                .transformWhile {
                    emit(it)
                    it != repeatCount * 2
                }
                .collect {
                    valuesSeen.add(it)
                }
        }

        // Act: Update source flow 10 times
        val job2 = async {
            repeat(repeatCount) {
                sourceFlow.update { it + 2 }
                // Small delay to increase chances of all values to be seen by the forwarder,
                // even though it is not required for this test to pass
                delay(1)
            }
        }
        awaitAll(job1, job2)

        // Assert
        Assert.assertTrue(
            "At most ${repeatCount +1} values should have been forwarded, instead of ${valuesSeen.size}",
            valuesSeen.size <= (repeatCount + 1)
        )
        Assert.assertEquals(repeatCount * 2, valuesSeen.last())
    }

    @Test
    fun testDestinationFlowContainsLastSourceFlowValueAfterScopeIsCancelled(): Unit = runBlockingWithTimeout {
        // Prepare
        val sourceFlow = MutableStateFlow(0)
        val scope = session.scope.createCloseableScope(isSupervisor = true)
        val forwarder = createStateFlowForwarder(scope, sourceFlow)
        val repeatCount = 10

        // Act: Update source flow 10 times
        repeat(repeatCount) {
            sourceFlow.update { it + 2 }
        }

        // Wait until forwarder has started, since cancel it and join() to ensure the last update
        // is processed.
        forwarder.stateFlow.first { it >= 4 }
        scope.cancel("Test ending")
        scope.coroutineContext.job.join()

        // Assert
        Assert.assertEquals(20, sourceFlow.value)
        Assert.assertEquals(sourceFlow.value, forwarder.stateFlow.value)
    }

    private fun createStateFlowForwarder(
        scope: CoroutineScope,
        sourceFlow: MutableStateFlow<Int>
    ): StateFlowForwarder<Int> {
        val forwarder = StateFlowForwarder(
            session = session,
            parentScope = scope,
            sourceStateFlowProvider = { sourceFlow },
            defaultValue = sourceFlow.value
        )
        return forwarder
    }

    private fun CoroutineScope.createCloseableScope(isSupervisor: Boolean = false): CoroutineScope {
        return createChildScope(isSupervisor).also { newScope ->
            val closeable = object: AutoCloseable {
                override fun close() {
                    newScope.cancel("CoroutineScope has been closed")
                }
            }
            registerCloseable(closeable)
        }
    }
}
