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

import com.android.build.gradle.internal.testing.utp.UtpDependency
import com.android.testutils.assertThrows
import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

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
    @Mock
    private lateinit var executorService: ExecutorService
    @Mock
    private lateinit var future: Future<*>

    @Captor
    private lateinit var commandCaptor: ArgumentCaptor<List<String>>
    @Captor
    private lateinit var taskCaptor: ArgumentCaptor<Runnable>

    private lateinit var javaFile: File
    private lateinit var loggingFile1: File
    private lateinit var loggingFile2: File
    private lateinit var launcherJar1: File
    private lateinit var launcherJar2: File
    private lateinit var coreJar1: File
    private lateinit var coreJar2: File
    private lateinit var runnerConfig1: File
    private lateinit var runnerConfig2: File

    private lateinit var utpRunner: UtpRunner

    @Before
    fun setUp() {
        javaFile = tempDir.newFile("my-java")
        loggingFile1 = tempDir.newFile("logging1.properties")
        loggingFile2 = tempDir.newFile("logging2.properties")
        launcherJar1 = tempDir.newFile("launcherA.jar")
        launcherJar2 = tempDir.newFile("launcherB.jar")
        coreJar1 = tempDir.newFile("coreA.jar")
        coreJar2 = tempDir.newFile("coreB.jar")
        runnerConfig1 = tempDir.newFile("runner-config-1.pb")
        runnerConfig2 = tempDir.newFile("runner-config-2.pb")

        whenever(executorServiceFactory()).thenReturn(executorService)
        whenever(processBuilderFactory(capture(commandCaptor))).thenReturn(processBuilder)
        whenever(processBuilder.start()).thenReturn(process)

        utpRunner = UtpRunner(
            javaExecFile = javaFile,
            loggingPropertiesFileList = listOf(loggingFile1, loggingFile2),
            utpLauncherJars = listOf(launcherJar1, launcherJar2),
            utpCoreJars = listOf(coreJar1, coreJar2),
            utpRunnerConfigFileList = listOf(runnerConfig1, runnerConfig2),
            logger = logger,
            processBuilderFactory = processBuilderFactory,
            executorServiceFactory = executorServiceFactory
        )
    }

    @Test
    fun execute_successfulRun_startsAllProcessesInParallel() {
        // Arrange
        whenever(process.waitFor()).thenReturn(0)
        whenever(process.inputStream).then { "stdout line".byteInputStream() }
        whenever(process.errorStream).then { "stderr line".byteInputStream() }
        val future1 = mock<Future<*>>()
        val future2 = mock<Future<*>>()
        whenever(executorService.submit(any<Runnable>())).thenReturn(future1, future2)
        whenever(future1.get()).thenReturn(Unit)
        whenever(future2.get()).thenReturn(Unit)

        // Act
        utpRunner.execute()

        // Assert: Verify tasks were submitted
        verify(executorService, times(2)).submit(taskCaptor.capture())

        // Execute the captured tasks to simulate the threads running
        taskCaptor.allValues.forEach { it.run() }

        // Assert: Verify commands
        val cpSeparator = File.pathSeparator
        val allCommands = commandCaptor.allValues
        assertThat(allCommands[0]).containsExactly(
            javaFile.absolutePath,
            "-Djava.awt.headless=true",
            "-Djava.util.logging.config.file=${loggingFile1.absolutePath}",
            "-Dfile.encoding=UTF-8",
            "-cp",
            "${launcherJar1.absolutePath}$cpSeparator${launcherJar2.absolutePath}",
            UtpDependency.LAUNCHER.mainClass,
            "${coreJar1.absolutePath}$cpSeparator${coreJar2.absolutePath}",
            "--proto_config=${runnerConfig1.absolutePath}",
        ).inOrder()
        assertThat(allCommands[1]).containsExactly(
            javaFile.absolutePath,
            "-Djava.awt.headless=true",
            "-Djava.util.logging.config.file=${loggingFile2.absolutePath}",
            "-Dfile.encoding=UTF-8",
            "-cp",
            "${launcherJar1.absolutePath}$cpSeparator${launcherJar2.absolutePath}",
            UtpDependency.LAUNCHER.mainClass,
            "${coreJar1.absolutePath}$cpSeparator${coreJar2.absolutePath}",
            "--proto_config=${runnerConfig2.absolutePath}",
        ).inOrder()

        // Assert: Verify process lifecycle and logging for both tasks
        verify(processBuilder, times(2)).start()
        verify(process, times(2)).waitFor()
        verify(logger, times(2)).info("stdout line")
        verify(logger, times(2)).info("stderr line")
        verify(executorService).shutdownNow()
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
            utpRunner.execute()
        }
        assertThat(e).hasMessageThat().isEqualTo("Test Execution failed")

        // Assert
        verify(logger).warn("Test Execution failed", testException)
        verify(executorService).shutdownNow() // Should be called in finally
    }

    @Test
    fun execute_whenInterrupted_rethrows() {
        // Arrange
        val testException = InterruptedException("Test interrupt")
        whenever(executorService.submit(any<Runnable>())).thenReturn(future)
        whenever(future.get()).thenThrow(testException)

        // Act & Assert
        val e = assertThrows<InterruptedException> {
            utpRunner.execute()
        }
        assertThat(e).isEqualTo(testException)

        // Assert
        verify(executorService).shutdownNow()
    }
}
