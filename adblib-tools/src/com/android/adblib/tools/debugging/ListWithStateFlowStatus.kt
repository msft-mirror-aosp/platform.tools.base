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
package com.android.adblib.tools.debugging

import kotlinx.coroutines.flow.StateFlow
import java.util.Objects

/**
 * A [List] of elements [E] paired with a [StateFlowStatus]. This is useful when defining
 * a [StateFlow] that emits [List] instances, to communicate the status of the flow.
 */
open class ListWithStateFlowStatus<out E>(
    private val list: List<E>,
    val flowStatus: StateFlowStatus
) : List<E> by list {

    override fun hashCode(): Int {
        return Objects.hash(flowStatus, list)
    }

    override fun toString(): String {
        // "<className>(<flowStatus>): <list items>"
        return "${this::class.simpleName}($flowStatus): $list"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        // Equals should take into account `flowStatus` if compared with another
        // `ListWithStateFlowStatus` instance
        if (other is ListWithStateFlowStatus<*>) {
            return (list == other.list) && (flowStatus == other.flowStatus)
        }

        // Equals should only take into account elements if compared with a `List` instance
        if (other is List<*>) {
            return list == other
        }

        return false
    }
}
