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

import com.android.testutils.assertThrows
import com.android.tools.utp.gradle.api.UtpDependencies
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.testing.platform.proto.api.config.RunnerConfigProto
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import java.io.File
import java.util.concurrent.ExecutionException
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.eq
import org.mockito.kotlin.isA
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/** Unit test for [UtpRunner]. */
@RunWith(MockitoJUnitRunner::class)
class UtpRunnerTest {

  @Rule @JvmField val tempDir = TemporaryFolder()

  @Mock private lateinit var logger: Logger

  @Mock private lateinit var utpDependencies: UtpDependencies

  // Inputs
  private lateinit var runnerConfig1: RunnerConfigProto.RunnerConfig
  private lateinit var runnerConfig2: RunnerConfigProto.RunnerConfig

  // Outputs
  private lateinit var resultFile1: File
  private lateinit var resultFile2: File
  private lateinit var mergedResultFile: File
  private lateinit var exitCodeFile: File

  private lateinit var utpRunner: UtpRunner

  // Test State
  private var throwExceptionFromUtp = false
  private var simulatedTestStatus = TestStatus.PASSED
  private val capturedUtpConfigs = mutableListOf<RunnerConfigProto.RunnerConfig>()

  @Before
  fun setUp() {
    // Setup Output Files
    resultFile1 = tempDir.newFile("result-1.pb")
    resultFile2 = tempDir.newFile("result-2.pb")
    mergedResultFile = tempDir.newFile("merged-result.pb")
    exitCodeFile = tempDir.newFile("exit-code.txt")

    // Setup Input Configs
    runnerConfig1 = RunnerConfigProto.RunnerConfig.newBuilder().build()
    runnerConfig2 = RunnerConfigProto.RunnerConfig.newBuilder().build()

    // Initialize Runner with a custom executor lambda to mock UTP behavior
    utpRunner =
      UtpRunner(utpDependencies, logger, MoreExecutors::newDirectExecutorService) { utpConfig ->
        capturedUtpConfigs.add(utpConfig)

        if (throwExceptionFromUtp) {
          throw RuntimeException("UTP Process failed")
        }

        // Simulate UTP writing a result file based on which config is running.
        // In a real scenario, the config contains the output path.
        // Here we map the input config instance to the pre-created output file.
        val targetFile =
          when (utpConfig) {
            runnerConfig1 -> resultFile1
            runnerConfig2 -> resultFile2
            else -> throw IllegalStateException("Unknown config")
          }

        // Write a dummy proto result to the file so the merger can read it later
        val dummyResult = TestSuiteResult.newBuilder().setTestStatus(simulatedTestStatus).build()
        targetFile.outputStream().use { dummyResult.writeTo(it) }
      }
  }

  @Test
  fun execute_successfulRun_startsAllProcessesInParallelAndMergesResults() {
    // Act
    utpRunner.execute(listOf(runnerConfig1, runnerConfig2), listOf(resultFile1, resultFile2), mergedResultFile, exitCodeFile)

    // Assert: Verify tasks were submitted
    assertThat(capturedUtpConfigs).containsExactly(runnerConfig1, runnerConfig2)

    // Assert: Verify exit code was written (0 for success)
    assertThat(exitCodeFile.readText()).isEqualTo("0")

    // Assert: Verify merged file has content
    val mergedResult = mergedResultFile.inputStream().use { TestSuiteResult.parseFrom(it) }
    assertThat(mergedResult.testStatus).isEqualTo(TestStatus.PASSED)
  }

  @Test
  fun execute_testFailures_writesFailureExitCode() {
    // Arrange
    simulatedTestStatus = TestStatus.FAILED

    // Act
    utpRunner.execute(listOf(runnerConfig1, runnerConfig2), listOf(resultFile1, resultFile2), mergedResultFile, exitCodeFile)

    // Assert: Verify exit code was written (1 for failure)
    assertThat(exitCodeFile.readText()).isEqualTo("1")
  }

  @Test
  fun execute_executionException_throwsGradleException() {
    // Arrange
    throwExceptionFromUtp = true

    // Act & Assert
    val e =
      assertThrows<GradleException> {
        utpRunner.execute(listOf(runnerConfig1, runnerConfig2), listOf(resultFile1, resultFile2), mergedResultFile, exitCodeFile)
      }
    assertThat(e).hasMessageThat().isEqualTo("Test Execution failed")

    // Assert: Logger should verify the underlying execution exception
    verify(logger, times(2)).warn(eq("Test Execution failed"), isA<ExecutionException>())
  }
}
