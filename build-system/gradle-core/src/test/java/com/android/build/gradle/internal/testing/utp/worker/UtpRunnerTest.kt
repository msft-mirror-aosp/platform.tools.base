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

package com.android.build.gradle.internal.testing.utp.worker

import com.android.build.gradle.internal.testing.utp.UtpDependencies
import com.android.build.gradle.internal.testing.utp.UtpDependency
import com.android.build.gradle.internal.testing.utp.UtpTestResultListener
import com.android.build.gradle.internal.testing.utp.UtpTestResultListenerServerRunner
import com.android.testutils.assertThrows
import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.utp.plugins.result.listener.gradle.proto.GradleAndroidTestResultListenerProto
import com.google.common.truth.Truth.assertThat
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
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

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
    @Mock
    private lateinit var processBuilderFactory: (List<String>) -> ProcessBuilder
    @Mock
    private lateinit var processBuilder: ProcessBuilder
    @Mock
    private lateinit var process: Process
    @Mock
    private lateinit var executorServiceFactory: () -> ExecutorService
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var executorService: ExecutorService
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var utpDependencies: UtpDependencies

    @Mock
    private lateinit var utpTestResultListenerServerRunner: UtpTestResultListenerServerRunner

    @Captor
    private lateinit var commandCaptor: ArgumentCaptor<List<String>>
    @Captor
    private lateinit var taskCaptor: ArgumentCaptor<Runnable>
    @Captor
    private lateinit var utpTestResultListenerCaptor: ArgumentCaptor<UtpTestResultListener>

    private lateinit var javaFile: File
    private lateinit var launcherJar: File
    private lateinit var coreJar: File
    private lateinit var xmlTestReportOutputDirectory: File
    private lateinit var loggingFile1: File
    private lateinit var loggingFile2: File
    private lateinit var runnerConfig1: File
    private lateinit var runnerConfig2: File
    private lateinit var resultProtoFile1: File
    private lateinit var resultProtoFile2: File

    private lateinit var utpRunner: UtpRunner

    @Before
    fun setUp() {
        javaFile = tempDir.newFile("my-java")
        coreJar = tempDir.newFile("core.jar")
        launcherJar = tempDir.newFile("launcher.jar")
        xmlTestReportOutputDirectory = tempDir.newFolder("xml-reports")
        loggingFile1 = tempDir.newFile("logging1.properties")
        loggingFile2 = tempDir.newFile("logging2.properties")
        runnerConfig1 = tempDir.newFile("runner-config-1.pb")
        runnerConfig2 = tempDir.newFile("runner-config-2.pb")
        resultProtoFile1 = tempDir.newFile("result1.pb")
        resultProtoFile2 = tempDir.newFile("result2.pb")

        // Write empty proto data to the config files
        val emptyConfig = RunnerConfigProto.RunnerConfig.getDefaultInstance()
        runnerConfig1.outputStream().use { emptyConfig.writeTo(it) }
        runnerConfig2.outputStream().use { emptyConfig.writeTo(it) }

        whenever(executorServiceFactory()).thenReturn(executorService)
        whenever(processBuilderFactory(capture(commandCaptor))).thenReturn(processBuilder)
        whenever(processBuilder.start()).thenReturn(process)

        // Mock UTP dependencies
        whenever(utpDependencies.launcher.files).thenReturn(setOf(launcherJar))
        whenever(utpDependencies.core.files).thenReturn(setOf(coreJar))

        // Mock listener server
        doNothing().whenever(utpTestResultListenerServerRunner).setListener(capture(utpTestResultListenerCaptor))

        utpRunner = UtpRunner(
            javaFile,
            utpDependencies,
            enableUtpTestReportingForAndroidStudio = false,
            utpTestResultListenerServerRunner,
            logger,
            processBuilderFactory,
            executorServiceFactory,
        )
    }

    @Test
    fun execute_successfulRun_startsAllProcessesInParallelAndWritesResults() {
        whenever(process.waitFor()).thenReturn(0)
        whenever(process.inputStream).then { "stdout line".byteInputStream() }
        whenever(process.errorStream).then { "stderr line".byteInputStream() }

        // Act
        utpRunner.execute(
            utpRunnerConfigFileList = listOf(runnerConfig1, runnerConfig2),
            loggingPropertiesFileList = listOf(loggingFile1, loggingFile2),
            deviceIDs = listOf("device1", "device2"),
            deviceNames = listOf("deviceName1", "deviceName2"),
            deviceShardNames = listOf("shardName1", "shardName2"),
            projectPath = "myProject",
            variantName = "myVariant",
            xmlTestReportOutputDirectory = xmlTestReportOutputDirectory,
            utpResultProtoOutputFileList = listOf(resultProtoFile1, resultProtoFile2)
        )

        // Assert: Verify tasks were submitted
        verify(executorService, times(2)).submit(taskCaptor.capture())

        // Execute the captured tasks to simulate the threads running
        taskCaptor.allValues.forEach { it.run() }

        val allCommands = commandCaptor.allValues

        assertThat(allCommands[0]).containsExactly(
            javaFile.absolutePath,
            "-Djava.awt.headless=true",
            "-Djava.util.logging.config.file=${loggingFile1.absolutePath}",
            "-Dfile.encoding=UTF-8",
            "-cp",
            launcherJar.absolutePath,
            UtpDependency.LAUNCHER.mainClass,
            coreJar.absolutePath,
            "--proto_config=${runnerConfig1.absolutePath}",
        ).inOrder()
        assertThat(allCommands[1]).containsExactly(
            javaFile.absolutePath,
            "-Djava.awt.headless=true",
            "-Djava.util.logging.config.file=${loggingFile2.absolutePath}",
            "-Dfile.encoding=UTF-8",
            "-cp",
            launcherJar.absolutePath,
            UtpDependency.LAUNCHER.mainClass,
            coreJar.absolutePath,
            "--proto_config=${runnerConfig2.absolutePath}",
        ).inOrder()

        // Assert: Verify process lifecycle and logging for both tasks
        verify(processBuilder, times(2)).start()
        verify(process, times(2)).waitFor()
        verify(logger, times(2)).info("stdout line")
        verify(logger, times(2)).info("stderr line")
        verify(executorService).shutdownNow()

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
        val future1 = mock<Future<*>>()
        val future2 = mock<Future<*>>()
        val testException = ExecutionException(RuntimeException("Process failed"))

        whenever(executorService.submit(any<Runnable>())).thenReturn(future1, future2)
        whenever(future1.get()).thenReturn(Unit) // First task succeeds
        whenever(future2.get()).thenThrow(testException) // Second task fails

        // Act & Assert
        val e = assertThrows<GradleException> {
            utpRunner.execute(
                utpRunnerConfigFileList = listOf(runnerConfig1, runnerConfig2),
                loggingPropertiesFileList = listOf(loggingFile1, loggingFile2),
                deviceIDs = listOf("device1", "device2"),
                deviceNames = listOf("deviceName1", "deviceName2"),
                deviceShardNames = listOf("shardName1", "shardName2"),
                projectPath = "myProject",
                variantName = "myVariant",
                xmlTestReportOutputDirectory = xmlTestReportOutputDirectory,
                utpResultProtoOutputFileList = listOf(resultProtoFile1, resultProtoFile2)
            )
        }
        assertThat(e).hasMessageThat().isEqualTo("Test Execution failed")

        // Assert
        verify(logger).warn("Test Execution failed", testException)
        verify(executorService).shutdownNow() // Should be called in finally
    }

    @Test
    fun execute_whenInterrupted_rethrows() {
        // Arrange
        val future = mock<Future<*>>()
        val testException = InterruptedException("Test interrupt")
        whenever(executorService.submit(any<Runnable>())).thenReturn(future)
        whenever(future.get()).thenThrow(testException)

        // Act & Assert
        val e = assertThrows<InterruptedException> {
            utpRunner.execute(
                utpRunnerConfigFileList = listOf(runnerConfig1),
                loggingPropertiesFileList = listOf(loggingFile1),
                deviceIDs = listOf("device1"),
                deviceNames = listOf("deviceName1"),
                deviceShardNames = listOf("shardName1"),
                projectPath = "myProject",
                variantName = "myVariant",
                xmlTestReportOutputDirectory = xmlTestReportOutputDirectory,
                utpResultProtoOutputFileList = listOf(resultProtoFile1)
            )
        }
        assertThat(e).isEqualTo(testException)

        // Assert
        verify(executorService).shutdownNow()
    }
}
