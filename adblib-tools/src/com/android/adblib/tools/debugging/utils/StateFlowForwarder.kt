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
package com.android.adblib.tools.debugging.utils

import com.android.adblib.AdbSession
import com.android.adblib.adbLogger
import com.android.adblib.utils.logIOCompletionErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Utility class used to forward all updates from a source [StateFlow] to the [stateFlow] property.
 * This class is useful when the source [StateFlow] is not immediately available, i.e. when it is
 * only available __asynchronously__ through a suspending call to [sourceStateFlowProvider].
 *
 * Note: Forwarding is started lazily, when the [stateFlow] property is accessed for the first
 * time, and active as long as [parentScope] is active.
 */
internal class StateFlowForwarder<T>(
    /**
     * The [com.android.adblib.AdbSession] context
     */
    session: AdbSession,
    /**
     * The [CoroutineScope] used to run the coroutine responsible for forwarding the [StateFlow],
     * for example the [scope][com.android.adblib.scope] of a [com.android.adblib.ConnectedDevice].
     */
    private val parentScope: CoroutineScope,
    /**
     * Provides asynchronous access to the source [kotlinx.coroutines.flow.StateFlow]
     */
    private val sourceStateFlowProvider: suspend () -> StateFlow<T>,
    /**
     * The initial value of [stateFlow]. This is needed because forwarding the first
     * value of the source [StateFlow] is done lazily.
     */
    defaultValue: T
) {

    private val logger = adbLogger(session)

    private val destinationMutableStateFlow = MutableStateFlow(defaultValue)

    private val lazyStartMonitoring by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        forwardStateFlowFromSourceFlow()
    }

    /**
     * The [StateFlow] that contains values forwarded from the source [StateFlow].
     *
     * Note: [stateFlow] stops being updated after [parentScope] is cancelled.
     */
    val stateFlow = destinationMutableStateFlow.asStateFlow()
        get() {
            lazyStartMonitoring
            return field
        }

    private fun forwardStateFlowFromSourceFlow(): Job {
        logger.debug { "Forwarding source state flow" }
        var sourceFlow: StateFlow<T>? = null
        return parentScope.launch {
            runCatching {
                sourceFlow = sourceStateFlowProvider()
                logger.debug { "Acquired source flow, start forwarding values to destination flow" }
                sourceFlow.collect { newValue ->
                    logger.verbose { "Forwarding new source flow value: $newValue" }
                    destinationMutableStateFlow.update { newValue }
                }
            }.onFailure { throwable ->
                logger.logIOCompletionErrors(throwable)
            }
        }.also {
            it.invokeOnCompletion {
                // Update destination flow one last time
                sourceFlow?.also {
                    destinationMutableStateFlow.update { sourceFlow.value }
                }
            }
        }
    }
}
