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

package com.android.tools.utp.gradle

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.TextFormat
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import org.junit.Test

/**
 * Unit tests for UtpWorkActionUtils.kt.
 */
class UtpWorkActionUtilsTest {

    private fun createResultProto(asciiProto: String): TestSuiteResult {
        return TextFormat.parse(asciiProto, TestSuiteResult::class.java)
    }

    @Test
    fun getPlatformErrorMessageShouldReturnErrorMessage() {
        val resultProto = createResultProto("""
            test_status: ERROR
            platform_error {
              errors {
                summary {
                  namespace {
                    namespace: "com.google.testing.platform"
                  }
                  error_code: 3002
                  error_name: "DEVICE_PROVISION_FAILED"
                  error_classification: "UNDERLYING_TOOL"
                  error_message: "Failed trying to provide device controller."
                  stack_trace: "This stacktrace should not be included in the error message."
                }
                cause {
                  summary {
                    error_message: "Gradle was unable to attach one or more devices to the adb server."
                    stack_trace: "stacktrace line1\nstacktrace line2"
                  }
                }
              }
            }
        """)

        assertThat(getPlatformErrorMessage(resultProto)).contains("""
            Failed trying to provide device controller.
            Gradle was unable to attach one or more devices to the adb server.
            stacktrace line1
            stacktrace line2
            """.trimIndent())
    }

    @Test
    fun getPlatformErrorMessageShouldReturnErrorMessageEvenIfErrorMessageIsMissingInProto() {
        val resultProto = createResultProto("""
            test_status: ERROR
            platform_error {
              errors {
                summary {
                  namespace {
                    namespace: "com.google.testing.platform"
                  }
                  error_code: 3002
                  error_name: "DEVICE_PROVISION_FAILED"
                  error_classification: "UNDERLYING_TOOL"
                  error_message: "Failed trying to provide device controller."
                  stack_trace: "This stacktrace should not be included in the error message."
                }
                cause {
                  summary {
                    stack_trace: "stacktrace line1\nstacktrace line2"
                  }
                }
              }
            }
        """)

        assertThat(getPlatformErrorMessage(resultProto)).contains("""
            Failed trying to provide device controller.
            Unknown platform error occurred when running the UTP test suite. Please check logs for details.
            stacktrace line1
            stacktrace line2
            """.trimIndent())
    }
}
