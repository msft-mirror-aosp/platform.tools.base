/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.internal.testing.utp

import com.android.build.gradle.internal.testing.utp.worker.RunUtpWorkAction
import com.android.build.gradle.internal.testing.utp.worker.RunUtpWorkParameters
import com.android.utils.ILogger
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.TextFormat
import com.google.testing.platform.proto.api.config.RunnerConfigProto.RunnerConfig
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import org.gradle.api.model.ObjectFactory
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.Mockito.contains
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.logging.Level

/**
 * Unit tests for UtpTestUtils.kt.
 */
class UtpTestUtilsTest {
    @get:Rule
    val temporaryFolderRule = TemporaryFolder()

    private val mockUtpDependencies: UtpDependencies = mock(defaultAnswer = RETURNS_DEEP_STUBS)
    private val mockWorkerExecutor: WorkerExecutor = mock()
    private val mockObjectFactory: ObjectFactory = mock()
    private val mockWorkQueue: WorkQueue = mock()
    private val mockLogger: ILogger = mock()

    lateinit var utpResultDir: File
    lateinit var jvmExecutable: File

    @Before
    fun setupMocks() {
        jvmExecutable = temporaryFolderRule.newFile()
        whenever(mockWorkerExecutor.noIsolation()).thenReturn(mockWorkQueue)
        whenever(mockObjectFactory.newInstance(
            eq(RunUtpWorkParameters.UtpRunConfig::class.java))
        ).thenReturn(mock(defaultAnswer = RETURNS_DEEP_STUBS))
    }

    private fun runUtp(
        shardConfig: ShardConfig? = null,
        expectedResult: TestSuiteResult? = createStubResultProto(),
    ): List<UtpTestRunResult> {
        val utpOutputDir = temporaryFolderRule.newFolder()
        utpResultDir = temporaryFolderRule.newFolder()
        val config = UtpRunnerConfig(
            "deviceName",
            "deviceId",
            utpOutputDir,
            RunnerConfig.getDefaultInstance(),
            shardConfig
        )

        if (expectedResult != null) {
            whenever(mockWorkQueue.submit(eq(RunUtpWorkAction::class.java), any())).then {
                File(utpOutputDir, TEST_RESULT_PB_FILE_NAME)
                    .writeBytes(expectedResult.toByteArray())
            }
        }

        return runUtpTestSuiteAndWait(
            listOf(config),
            mockWorkerExecutor,
            mockObjectFactory,
            jvmExecutable,
            "projectName",
            "variantName",
            utpResultDir,
            mockLogger,
            mockUtpDependencies,
            Level.WARNING,
        )
    }

    private fun createStubResultProto(): TestSuiteResult {
        return createResultProto("""
            test_suite_meta_data {
              scheduled_test_case_count: 1
            }
            test_status: PASSED
            test_result {
              test_case {
                test_class: "ExampleInstrumentedTest"
                test_package: "com.example.application"
                test_method: "useAppContext"
              }
              test_status: PASSED
            }
        """)
    }

    private fun createFailedStubResultProto(): TestSuiteResult {
        return createResultProto("""
            test_status: FAILED
            issue {
              namespace {
                namespace: "com.google.testing.platform.runtime.android.driver.AndroidInstrumentationDriver"
              }
              severity: SEVERE
              code: 1
              name: "INSTRUMENTATION_FAILED"
              message: "Test run failed to complete. Instrumentation run failed due to Process crashed."
            }
        """)
    }

    private fun createResultProto(asciiProto: String): TestSuiteResult {
        return TextFormat.parse(asciiProto, TestSuiteResult::class.java)
    }

    @Test
    fun failedToReceiveUtpResults() {
        val results = runUtp(expectedResult = null)

        assertThat(results).containsExactly(UtpTestRunResult(false, null))
        verify(mockLogger).error(
            anyOrNull<Throwable>(),
            contains("Failed to receive the UTP test results"))
    }

    @Test
    fun runSuccessfully() {
        val results = runUtp()

        assertThat(results).containsExactly(UtpTestRunResult(true, createStubResultProto()))
    }

    @Test
    fun runSuccessfullyWithSharding() {
        val results = runUtp(ShardConfig(totalCount = 2, index = 0))

        assertThat(results).containsExactly(UtpTestRunResult(true, createStubResultProto()))
    }

    @Test
    fun runSuccessfullyButTestFailed() {
        val expectedResult = createFailedStubResultProto()

        val results = runUtp(expectedResult = expectedResult)

        assertThat(results).containsExactly(UtpTestRunResult(false, expectedResult))
    }

    @Test
    fun resultHasEmulatorTimeoutException() {
        val testResult = TestSuiteResult.newBuilder().apply {
            platformErrorBuilder.apply {
                addErrorsBuilder().apply {
                    causeBuilder.apply {
                        summaryBuilder.apply {
                            stackTrace = "EmulatorTimeoutException"
                        }
                    }
                }
            }
        }.build()

        assertThat(hasEmulatorTimeoutException(testResult)).isTrue()
    }

    @Test
    fun resultDoesNotHaveEmulatorTimeoutException() {
        val testResult = TestSuiteResult.newBuilder().apply {
            platformErrorBuilder.apply {
                addErrorsBuilder().apply {
                    causeBuilder.apply {
                        summaryBuilder.apply {
                            stackTrace = "Exception"
                        }
                    }
                }
            }
        }.build()

        assertThat(hasEmulatorTimeoutException(testResult)).isFalse()
        assertThat(hasEmulatorTimeoutException(null)).isFalse()
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
