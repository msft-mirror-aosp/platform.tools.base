/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.screenshot

import com.android.tools.render.compose.ComposeScreenshotResult
import com.google.protobuf.Timestamp
import com.google.testing.platform.proto.api.core.ErrorProto.Error
import com.google.testing.platform.proto.api.core.TestCaseProto.TestCase
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteMetaData

private const val MILLIS_PER_SECOND = 1000
private const val NANOS_PER_MILLISECOND = 1000000
private const val NANOS_PER_SECOND = 1000000000
// Timestamp for "0001-01-01T00:00:00Z"
private const val TIMESTAMP_SECONDS_MIN = -62135596800L
// Timestamp for "9999-12-31T23:59:59Z"
private const val TIMESTAMP_SECONDS_MAX = 253402300799L

/**
 * Creates a TestCase for a preview screenshot test.
 */
fun createTestCase(
    composeScreenshot: ComposeScreenshotResult,
    testCaseName: String,
    testStartTime: Long,
    testEndTime: Long
    ): TestCase {
    return TestCase.newBuilder().apply {
        val packageName: String = composeScreenshot.methodFQN.substringBeforeLast(".")
        val className: String = packageName.split(".").last()
        testClass = className
        testPackage = packageName.dropLast(className.length + 1)
        testMethod = testCaseName
        startTime = createTimestampFromMillis(testStartTime)
        Timestamp.newBuilder().nanos
        endTime = createTimestampFromMillis(testEndTime)
    }.build()
}

/**
 * Creates TestSuiteMetadata with the provided class name and test count.
 */
fun createTestSuiteMetadata(className: String, testCount: Int): TestSuiteMetaData {
    return TestSuiteMetaData.newBuilder().apply {
            testSuiteName = className
            scheduledTestCaseCount = testCount
        }.build()
}

/**
 * Creates Error with the provided error message.
 */
fun createError(message: String): Error {
    return Error.newBuilder().apply {
        errorMessage = message
    }.build()
}

/**
 * Create a Timestamp for the provided milliseconds.
 *
 * Refactored from com.google.protobuf.util.Timestamps.fromMillis
 */
private fun createTimestampFromMillis(milliseconds: Long): Timestamp {
    var seconds = milliseconds / MILLIS_PER_SECOND
    var nanos = (milliseconds % MILLIS_PER_SECOND * NANOS_PER_MILLISECOND).toInt()
    // This only checks seconds, because nanos can intentionally overflow to increment the seconds
    // when normalized.
    if (!isValidSeconds(seconds)) {
        throw IllegalArgumentException(
            String.format(
                "Timestamp is not valid. Input seconds is too large. "
                        + "Seconds (%s) must be in range [-62,135,596,800, +253,402,300,799]. ",
                seconds
            )
        )
    }
    if (nanos <= -NANOS_PER_SECOND || nanos >= NANOS_PER_SECOND) {
        seconds += (nanos / NANOS_PER_SECOND)
        nanos %= NANOS_PER_SECOND
    }
    if (nanos < 0) {
        nanos =
            (nanos + NANOS_PER_SECOND) // no overflow since nanos is negative (and we're adding)
        seconds -= 1
    }
    val timestamp = Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build()
    return checkValid(timestamp)
}

/**
 * Returns true if the given number of seconds is valid, if combined with a valid number of nanos.
 * The `seconds` value must be in the range [-62,135,596,800, +253,402,300,799] (i.e.,
 * between 0001-01-01T00:00:00Z and 9999-12-31T23:59:59Z).
 *
 * Refactored from com.google.protobuf.util.Timestamps.isValidSeconds
 */
// this is a legacy conversion API
private fun isValidSeconds(seconds: Long): Boolean {
    return seconds in TIMESTAMP_SECONDS_MIN..TIMESTAMP_SECONDS_MAX
}

/** Throws an [IllegalArgumentException] if the given [Timestamp] is not valid.
 * Refactored from com.google.protobuf.util.Timestamps.checkValid
 */
private fun checkValid(timestamp: Timestamp): Timestamp {
    val seconds = timestamp.seconds
    val nanos = timestamp.nanos
    if (!isValid(seconds, nanos)) {
        throw java.lang.IllegalArgumentException(
            String.format(
                ("Timestamp is not valid. See proto definition for valid values. "
                        + "Seconds (%s) must be in range [-62,135,596,800, +253,402,300,799]. "
                        + "Nanos (%s) must be in range [0, +999,999,999]."),
                seconds, nanos
            )
        )
    }
    return timestamp
}

/**
 * Returns true if the given number of seconds and nanos is a valid [Timestamp]. The `seconds` value must be in the range [-62,135,596,800, +253,402,300,799] (i.e., between
 * 0001-01-01T00:00:00Z and 9999-12-31T23:59:59Z). The `nanos` value must be in the range
 * [0, +999,999,999].
 *
 *
 * **Note:** Negative second values with fractional seconds must still have non-negative
 * nanos values that count forward in time.
 *
 * Refactored from com.google.protobuf.util.Timestamps.isValid
 */
// this is a legacy conversion API
fun isValid(seconds: Long, nanos: Int): Boolean {
    if (!isValidSeconds(seconds)) {
        return false
    }
    if (nanos < 0 || nanos >= NANOS_PER_SECOND) {
        return false
    }
    return true
}



