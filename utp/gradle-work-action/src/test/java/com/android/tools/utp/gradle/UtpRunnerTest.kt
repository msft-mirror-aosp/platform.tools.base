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
import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.utp.gradle.api.UtpDependencies
import com.android.tools.utp.plugins.result.listener.gradle.proto.GradleAndroidTestResultListenerProto
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.protobuf.Any
import com.google.testing.platform.proto.api.config.RunnerConfigProto
import com.google.testing.platform.proto.api.core.TestCaseProto
import com.google.testing.platform.proto.api.core.TestResultProto
import com.google.testing.platform.proto.api.core.TestSuiteResultProto
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.capture
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.isA
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.concurrent.ExecutionException

/**
 * Unit test for [UtpRunner].
 */
@RunWith(MockitoJUnitRunner::class)
class UtpRunnerTest {

    @Rule
    @JvmField
    val tempDir = TemporaryFolder()

    @Mock
    private lateinit var logger: Logger
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var utpDependencies: UtpDependencies

    @Mock
    private lateinit var utpTestResultListenerServerRunner: UtpTestResultListenerServerRunner

    @Captor
    private lateinit var utpTestResultListenerCaptor: ArgumentCaptor<UtpTestResultListener>

    private lateinit var launcherJar: File
    private lateinit var coreJar: File
    private lateinit var xmlTestReportOutputDirectory: File
    private lateinit var runnerConfig1: File
    private lateinit var runnerConfig2: File
    private lateinit var resultProtoFile1: File
    private lateinit var resultProtoFile2: File
    private lateinit var mergedResultProtoFile: File
    private lateinit var testResultExitCodeFile: File

    private lateinit var utpRunner: UtpRunner

    private var throwExceptionFromUtp = false
    private val capturedUtpConfigs = mutableListOf<File>()

    @Before
    fun setUp() {
        coreJar = tempDir.newFile("core.jar")
        launcherJar = tempDir.newFile("launcher.jar")
        xmlTestReportOutputDirectory = tempDir.newFolder("xml-reports")
        runnerConfig1 = tempDir.newFile("runner-config-1.pb")
        runnerConfig2 = tempDir.newFile("runner-config-2.pb")
        resultProtoFile1 = tempDir.newFile("result1.pb")
        resultProtoFile2 = tempDir.newFile("result2.pb")
        mergedResultProtoFile = tempDir.newFile("mergedResult.pb")
        testResultExitCodeFile = tempDir.newFile("testResultExitCodeFile.txt")

        // Write empty proto data to the config files
        val emptyConfig = RunnerConfigProto.RunnerConfig.getDefaultInstance()
        runnerConfig1.outputStream().use { emptyConfig.writeTo(it) }
        runnerConfig2.outputStream().use { emptyConfig.writeTo(it) }

        // Mock UTP dependencies
        whenever(utpDependencies.launcher.files).thenReturn(setOf(launcherJar))
        whenever(utpDependencies.core.files).thenReturn(setOf(coreJar))

        // Mock listener server
        doNothing().whenever(utpTestResultListenerServerRunner).setListener(capture(utpTestResultListenerCaptor))

        utpRunner = UtpRunner(
            utpDependencies,
            enableUtpTestReportingForAndroidStudio = false,
            utpTestResultListenerServerRunner,
            logger,
            MoreExecutors::newDirectExecutorService,
        ) { utpConfig ->
            capturedUtpConfigs.add(utpConfig)
            if (throwExceptionFromUtp) {
                throw RuntimeException("UTP Process failed")
            }
        }
    }

    @Test
    fun execute_successfulRun_startsAllProcessesInParallelAndWritesResults() {
        // Act
        utpRunner.execute(
            utpRunnerConfigFileList = listOf(runnerConfig1, runnerConfig2),
            deviceIDs = listOf("device1", "device2"),
            deviceNames = listOf("deviceName1", "deviceName2"),
            deviceShardNames = listOf("shardName1", "shardName2"),
            projectPath = "myProject",
            variantName = "myVariant",
            xmlTestReportOutputDirectory = xmlTestReportOutputDirectory,
            mergedUtpResultProtoOutputFile = mergedResultProtoFile,
            testResultExitCodeFile = testResultExitCodeFile,
            utpResultProtoOutputFileList = listOf(resultProtoFile1, resultProtoFile2)
        )

        // Assert: Verify tasks were submitted
        assertThat(capturedUtpConfigs).hasSize(2)

        // Assert: Verify listener received results and wrote proto files
        val listener = utpTestResultListenerCaptor.value
        val testSuiteResult = TestSuiteResultProto.TestSuiteResult.newBuilder()
            .addTestResult(TestResultProto.TestResult.newBuilder()
                .setTestCase(TestCaseProto.TestCase.newBuilder().setTestClass("myTest"))
            )
            .build()
        val event = GradleAndroidTestResultListenerProto.TestResultEvent.newBuilder()
            .setDeviceId("device1")
            .setTestSuiteFinished(
                GradleAndroidTestResultListenerProto.TestResultEvent.TestSuiteFinished.newBuilder()
                    .setTestSuiteResult(Any.pack(testSuiteResult))
            ).build()

        listener.onTestResultEvent(event)

        // Verify the correct proto file was written
        assertThat(resultProtoFile1.length()).isGreaterThan(0)
        assertThat(resultProtoFile2.length()).isEqualTo(0)
        val writtenProto = resultProtoFile1.inputStream().use {
            TestSuiteResultProto.TestSuiteResult.parseFrom(it)
        }
        assertThat(writtenProto).isEqualTo(testSuiteResult)

        val resultsXml = xmlTestReportOutputDirectory.resolve("TEST-shardName1-myProject-myVariant.xml")
        assertThat(resultsXml).exists()
        assertThat(resultsXml).containsAllOf(
            """<property name="device" value="shardName1" />""",
            """<property name="flavor" value="myVariant" />""",
            """<property name="project" value="myProject" />""",
        )
    }

    @Test
    fun execute_oneTaskFails_throwsGradleException() {
        // Arrange
        throwExceptionFromUtp = true

        // Act & Assert
        val e = assertThrows<GradleException> {
            utpRunner.execute(
                utpRunnerConfigFileList = listOf(runnerConfig1, runnerConfig2),
                deviceIDs = listOf("device1", "device2"),
                deviceNames = listOf("deviceName1", "deviceName2"),
                deviceShardNames = listOf("shardName1", "shardName2"),
                projectPath = "myProject",
                variantName = "myVariant",
                xmlTestReportOutputDirectory = xmlTestReportOutputDirectory,
                mergedUtpResultProtoOutputFile = mergedResultProtoFile,
                testResultExitCodeFile = testResultExitCodeFile,
                utpResultProtoOutputFileList = listOf(resultProtoFile1, resultProtoFile2)
            )
        }
        assertThat(e).hasMessageThat().isEqualTo("Test Execution failed")

        // Assert
        verify(logger, times(2))
            .warn(eq("Test Execution failed"), isA<ExecutionException>())
    }
}
