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
package com.android.adblib

import kotlinx.coroutines.flow.StateFlow

/**
 * A general purpose "status" class to be used with [StateFlow] values to describe the current state of the underlying state flow collector.
 */
data class StateFlowStatus(
  val valueKind: ValueKind,

  /** A [Throwable] if the flow is currently in an "error" state. */
  val currentError: Throwable? = null,
) {
  val isActive: Boolean
    get() = valueKind == ValueKind.ACTIVE

  val isStartOfFlow: Boolean
    get() = valueKind == ValueKind.START_OF_FLOW

  val isEndOfFlow: Boolean
    get() = valueKind == ValueKind.END_OF_FLOW

  val isRetrying: Boolean
    get() = valueKind == ValueKind.IS_RETRYING

  override fun toString(): String {
    val statusString =
      when (valueKind) {
        ValueKind.ACTIVE -> "Flow is active"
        ValueKind.START_OF_FLOW -> "Flow is initializing"
        ValueKind.END_OF_FLOW -> "Flow has stopped"
        ValueKind.IS_RETRYING -> "Flow is retrying"
      }
    val errorString = currentError?.let { " (currentError=\"${currentError.message}\")" } ?: ""
    return "$statusString$errorString"
  }

  enum class ValueKind {
    /** Whether the flow is active and a "regular" value is emitted */
    ACTIVE,

    /** Whether this is the very first entry of the flow, emitted before actual values. */
    START_OF_FLOW,

    /** Whether the flow is still active, emitted as the last value of the flow. */
    END_OF_FLOW,

    /**
     * Whether the flow is currently interrupted during a transient error. Typically, [currentError] is set to the error that caused the
     * retry.
     */
    IS_RETRYING,
  }

  companion object {
    val active = StateFlowStatus(valueKind = ValueKind.ACTIVE)
    val startOfFlow = StateFlowStatus(valueKind = ValueKind.START_OF_FLOW)
    val endOfFlow = StateFlowStatus(valueKind = ValueKind.END_OF_FLOW)

    fun retrying(t: Throwable) = StateFlowStatus(valueKind = ValueKind.IS_RETRYING, currentError = t)
  }
}
