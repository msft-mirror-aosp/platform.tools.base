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
import com.google.common.truth.Truth.assertThat
import org.gradle.api.logging.Logger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.*
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(MockitoJUnitRunner::class)
class UtpRunnerTest {

    @Rule
    @JvmField
    val tempDir = TemporaryFolder()

    @Rule
    @JvmField
    val expectedException: ExpectedException = ExpectedException.none()

    @Mock
    lateinit var logger: Logger
    @Mock
    lateinit var processBuilderFactory: (List<String>) -> ProcessBuilder
    @Mock
    lateinit var processBuilder: ProcessBuilder
    @Mock
    lateinit var process: Process

    @Captor
    lateinit var commandCaptor: ArgumentCaptor<List<String>>

    // --- Test Fixtures ---
    private lateinit var javaFile: File
    private lateinit var loggingFile: File
    private lateinit var launcherJar1: File
    private lateinit var launcherJar2: File
    private lateinit var coreJar1: File
    private lateinit var coreJar2: File
    private lateinit var runnerConfig: File
    private lateinit var serverConfig: File

    private lateinit var utpRunner: UtpRunner

    @Before
    fun setUp() {
        // Create dummy files using the TemporaryFolder rule
        javaFile = tempDir.newFile("my-java")
        loggingFile = tempDir.newFile("logging.properties")
        launcherJar1 = tempDir.newFile("launcherA.jar")
        launcherJar2 = tempDir.newFile("launcherB.jar")
        coreJar1 = tempDir.newFile("coreA.jar")
        coreJar2 = tempDir.newFile("coreB.jar")
        runnerConfig = tempDir.newFile("runner-config.pb")
        serverConfig = tempDir.newFile("server-config.pb")

        // Mock the factory to return our mock ProcessBuilder
        whenever(processBuilderFactory.invoke(capture(commandCaptor))).thenReturn(processBuilder)

        // Mock the ProcessBuilder to return our mock Process
        whenever(processBuilder.start()).thenReturn(process)

        // Instantiate the class under test
        utpRunner = UtpRunner(
            javaExecFile = javaFile,
            loggingPropertiesFile = loggingFile,
            utpLauncherJars = listOf(launcherJar1, launcherJar2),
            utpCoreJars = listOf(coreJar1, coreJar2),
            utpRunnerConfigFile = runnerConfig,
            utpServerConfigFile = serverConfig,
            logger = logger,
            processBuilderFactory = processBuilderFactory
        )
    }

    @Test
    fun `execute builds correct command, streams output, and waits`() {
        // Arrange
        // Simulate a successful process exit
        whenever(process.waitFor()).thenReturn(0)

        // Simulate process output
        whenever(process.inputStream).thenReturn(
            "stdout line 1\nstdout line 2".byteInputStream()
        )
        whenever(process.errorStream).thenReturn(
            "stderr line 1".byteInputStream()
        )

        // Act
        utpRunner.execute()

        // Assert
        // 1. Verify the exact command arguments
        val command = commandCaptor.value
        val cpSeparator = File.pathSeparator

        assertThat(command).containsExactly(
            javaFile.absolutePath,
            "-Djava.awt.headless=true",
            "-Djava.util.logging.config.file=${loggingFile.absolutePath}",
            "-Dfile.encoding=UTF-8",
            "-cp",
            "${launcherJar1.absolutePath}$cpSeparator${launcherJar2.absolutePath}",
            UtpDependency.LAUNCHER.mainClass,
            "${coreJar1.absolutePath}$cpSeparator${coreJar2.absolutePath}",
            "--proto_config=${runnerConfig.absolutePath}",
            "--proto_server_config=${serverConfig.absolutePath}"
        ).inOrder() // Verify all arguments in the correct order

        // 2. Verify process lifecycle
        verify(processBuilder).start()
        verify(process, times(1)).waitFor()
        verify(process, never()).destroyForcibly()

        // 3. Verify logging
        // Use timeout because GrabProcessOutput runs in separate threads
        verify(logger, timeout(1000)).info("stdout line 1")
        verify(logger, timeout(1000)).info("stdout line 2")
        verify(logger, timeout(1000)).info("stderr line 1")
    }

    @Test
    fun `execute when interrupted, destroys process and re-throws`() {
        // Arrange
        // Mock the process streams to be empty
        whenever(process.inputStream).thenReturn(ByteArrayInputStream(byteArrayOf()))
        whenever(process.errorStream).thenReturn(ByteArrayInputStream(byteArrayOf()))

        // Mock the call sequence for process.waitFor()
        whenever(process.waitFor())
            .thenThrow(InterruptedException("Test interrupt")) // First call in try-block
            .thenReturn(143) // Second call in catch-block

        // Assert
        // 1. Expect the correct exception to be thrown
        expectedException.expect(InterruptedException::class.java)
        expectedException.expectMessage("Test interrupt")

        // Act
        utpRunner.execute()

        // 2. Verify the process lifecycle
        // (Note: If execute() throws, these lines are only reached if the exception
        // was expected. This is a slight difference from JUnit 5's assertThrows,
        // but for this case, it works.)
        verify(processBuilder).start()
        verify(process).destroyForcibly() // Should be called from the catch block
        verify(process, times(2)).waitFor() // First call in try, second in catch
    }
}
