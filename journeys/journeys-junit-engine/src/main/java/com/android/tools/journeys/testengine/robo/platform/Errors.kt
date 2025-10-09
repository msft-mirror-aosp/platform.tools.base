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

package com.android.tools.journeys.testengine.robo.platform

enum class JourneyFailureReason {
    UNKNOWN_FAILURE,
    ADB_INSTALL_FAILED,
    ADB_FORWARDING_FAILED,
    ROBO_PORT_EXTRACTION_FAILED,
    INSTRUMENTATION_FAILED,
    JOURNEY_READ_FAILED,
    AUTHENTICATION_FAILED,
}

/**
 * Custom exception type for errors occurring during Journey execution.
 * Includes a specific [reason] to categorize the failure.
 *
 * @param message A descriptive message for the error.
 * @param cause The underlying cause of the exception, if any.
 * @param reason The specific categorized reason for the journey failure.
 */
class JourneyExecutionException(
    message: String?,
    cause: Throwable? = null,
    val reason: JourneyFailureReason = JourneyFailureReason.UNKNOWN_FAILURE
) : RuntimeException(
    "${message ?: "Unexpected failure while running the journey"} [Reason=${reason.name}]",
    cause
)
