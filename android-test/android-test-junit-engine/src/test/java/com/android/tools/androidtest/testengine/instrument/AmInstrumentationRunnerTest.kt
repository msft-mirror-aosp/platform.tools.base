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

package com.android.tools.androidtest.testengine.instrument

import com.android.tools.androidtest.testengine.CoverageAgentFilesystemInfo
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.logging.Logger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Unit tests for [AmInstrumentationRunner]. */
@RunWith(JUnit4::class)
class AmInstrumentationRunnerTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var fakeAdb: File
  private lateinit var mockLogger: Logger
  private lateinit var mockProcess: Process
  private lateinit var mockProcessBuilder: ProcessBuilder

  @Before
  fun setUp() {
    fakeAdb = tempFolder.newFile("adb")
    mockLogger = mock()
    mockProcess = mock()
    mockProcessBuilder = mock()
  }

  @Test
  fun runAmInstrumentCommand_executesCorrectCommandAndHandlesOutput() {
    val deviceSerial = "test-device-123"
    val runnerClass = "com.example.TestRunner"
    val targetPackage = "com.example.app"

    val stdout =
      """
      INSTRUMENTATION_STATUS_CODE: 1
      INSTRUMENTATION_STATUS: class=com.example.MyTest
      INSTRUMENTATION_STATUS: test=testExample
      INSTRUMENTATION_STATUS_CODE: 0
      INSTRUMENTATION_CODE: -1
      """
        .trimIndent()

    val stderr = "Warning: This is a test warning."

    // Mock the process to return our predefined stdout and stderr
    whenever(mockProcess.inputStream).thenReturn(stdout.byteInputStream())
    whenever(mockProcess.errorStream).thenReturn(stderr.byteInputStream())
    whenever(mockProcess.waitFor()).thenReturn(0)

    // Mock the process builder to return our mocked process
    whenever(mockProcessBuilder.start()).thenReturn(mockProcess)

    var capturedCommand: List<String>? = null
    val runner =
      AmInstrumentationRunner(
        adb = fakeAdb,
        deviceSerial = deviceSerial,
        instrumentationRunnerClass = runnerClass,
        testPackageId = targetPackage,
        instrumentationTargetPackageId = targetPackage,
        logger = mockLogger,
        processBuilder = { command ->
          capturedCommand = command
          mockProcessBuilder
        },
      )

    runner.runAmInstrumentCommand()

    // Verify the correct command was constructed and passed to the process builder.
    assertThat(capturedCommand).isNotNull()
    assertThat(capturedCommand)
      .containsExactly(fakeAdb.absolutePath, "-s", deviceSerial, "shell", "am", "instrument", "-r", "-w", "$targetPackage/$runnerClass")
      .inOrder()

    // Verify that the process was started.
    verify(mockProcessBuilder).start()

    // Verify logging
    val infoCaptor = argumentCaptor<String>()
    verify(mockLogger).info(infoCaptor.capture())
    assertThat(infoCaptor.firstValue).contains("Running instrumentation:")
    assertThat(infoCaptor.firstValue).contains("shell \"am instrument -r -w com.example.app/com.example.TestRunner\"")

    // Verify that stderr was logged as a warning.
    val warningCaptor = argumentCaptor<String>()
    verify(mockLogger).warning(warningCaptor.capture())
    assertThat(warningCaptor.firstValue).isEqualTo(stderr)
  }

  @Test
  fun runAmInstrumentCommand_withAndroidxOrchestrator() {
    val deviceSerial = "test-device-123"
    val runnerClass = "com.example.TestRunner"
    val targetPackage = "com.example.app"

    whenever(mockProcess.inputStream).thenReturn("INSTRUMENTATION_CODE: -1".byteInputStream())
    whenever(mockProcess.errorStream).thenReturn("".byteInputStream())
    whenever(mockProcessBuilder.start()).thenReturn(mockProcess)

    var capturedCommand: List<String>? = null
    val runner =
      AmInstrumentationRunner(
        adb = fakeAdb,
        deviceSerial = deviceSerial,
        instrumentationRunnerClass = runnerClass,
        testPackageId = targetPackage,
        instrumentationTargetPackageId = targetPackage,
        executionMode = "androidx_test_orchestrator", // Test case-insensitivity
        logger = mockLogger,
        processBuilder = { command ->
          capturedCommand = command
          mockProcessBuilder
        },
      )

    runner.runAmInstrumentCommand()

    assertThat(capturedCommand).isNotNull()
    assertThat(capturedCommand)
      .containsExactly(
        fakeAdb.absolutePath,
        "-s",
        deviceSerial,
        "shell",
        "CLASSPATH=$(pm path androidx.test.services)",
        "app_process",
        "/",
        "androidx.test.services.shellexecutor.ShellMain",
        "am",
        "instrument",
        "-r",
        "-w",
        "-e",
        "targetInstrumentation",
        "$targetPackage/$runnerClass",
        "androidx.test.orchestrator/androidx.test.orchestrator.AndroidTestOrchestrator",
      )
      .inOrder()

    // Verify logging
    val infoCaptor = argumentCaptor<String>()
    verify(mockLogger).info(infoCaptor.capture())
    assertThat(infoCaptor.firstValue).contains("Running instrumentation:")
    assertThat(infoCaptor.firstValue)
      .contains(
        "shell \"CLASSPATH=$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain am instrument -r -w -e targetInstrumentation com.example.app/com.example.TestRunner androidx.test.orchestrator/androidx.test.orchestrator.AndroidTestOrchestrator\""
      )
  }

  @Test
  fun runAmInstrumentCommand_withLegacyOrchestrator() {
    val deviceSerial = "test-device-123"
    val runnerClass = "com.example.TestRunner"
    val targetPackage = "com.example.app"

    whenever(mockProcess.inputStream).thenReturn("INSTRUMENTATION_CODE: -1".byteInputStream())
    whenever(mockProcess.errorStream).thenReturn("".byteInputStream())
    whenever(mockProcessBuilder.start()).thenReturn(mockProcess)

    var capturedCommand: List<String>? = null
    val runner =
      AmInstrumentationRunner(
        adb = fakeAdb,
        deviceSerial = deviceSerial,
        instrumentationRunnerClass = runnerClass,
        testPackageId = targetPackage,
        instrumentationTargetPackageId = targetPackage,
        executionMode = "ANDROID_TEST_ORCHESTRATOR",
        logger = mockLogger,
        processBuilder = { command ->
          capturedCommand = command
          mockProcessBuilder
        },
      )

    runner.runAmInstrumentCommand()

    assertThat(capturedCommand).isNotNull()
    assertThat(capturedCommand)
      .containsExactly(
        fakeAdb.absolutePath,
        "-s",
        deviceSerial,
        "shell",
        "CLASSPATH=$(pm path android.support.test.services)",
        "app_process",
        "/",
        "android.support.test.services.shellexecutor.ShellMain",
        "am",
        "instrument",
        "-r",
        "-w",
        "-e",
        "targetInstrumentation",
        "$targetPackage/$runnerClass",
        "android.support.test.orchestrator/android.support.test.orchestrator.AndroidTestOrchestrator",
      )
      .inOrder()
  }

  @Test
  fun runAmInstrumentCommand_withInstrumentationArgs() {
    val deviceSerial = "test-device-123"
    val runnerClass = "com.example.TestRunner"
    val targetPackage = "com.example.app"

    whenever(mockProcess.inputStream).thenReturn("INSTRUMENTATION_CODE: -1".byteInputStream())
    whenever(mockProcess.errorStream).thenReturn("".byteInputStream())
    whenever(mockProcessBuilder.start()).thenReturn(mockProcess)

    var capturedCommand: List<String>? = null
    val runner =
      AmInstrumentationRunner(
        adb = fakeAdb,
        deviceSerial = deviceSerial,
        instrumentationRunnerClass = runnerClass,
        testPackageId = targetPackage,
        instrumentationTargetPackageId = targetPackage,
        instrumentationArgs = mapOf("clearPackageData" to "true", "useTestStorageService" to "false"),
        logger = mockLogger,
        processBuilder = { command ->
          capturedCommand = command
          mockProcessBuilder
        },
      )

    runner.runAmInstrumentCommand()

    assertThat(capturedCommand).isNotNull()
    assertThat(capturedCommand)
      .containsExactly(
        fakeAdb.absolutePath,
        "-s",
        deviceSerial,
        "shell",
        "am",
        "instrument",
        "-r",
        "-w",
        "-e",
        "clearPackageData",
        "true",
        "-e",
        "useTestStorageService",
        "false",
        "$targetPackage/$runnerClass",
      )
      .inOrder()
  }

  @Test
  fun runAmInstrumentCommand_configuresJavaApiAttacher() {
    val deviceSerial = "test-device-123"
    val testPackage = "com.example.app.test"
    val targetPackage = "com.example.app"
    val agentPath = "/data/user/0/com.example.app.test/coverage_agent.so"
    val dataDir = "/data/user/0/com.example.app.test"

    val mockAmProcess = mock<Process>()
    whenever(mockAmProcess.inputStream).thenReturn("INSTRUMENTATION_CODE: -1".byteInputStream())
    whenever(mockAmProcess.errorStream).thenReturn("".byteInputStream())
    whenever(mockAmProcess.waitFor()).thenReturn(0)

    val commands = mutableListOf<List<String>>()
    val agentFilesystemInfo =
      CoverageAgentFilesystemInfo().apply {
        agentBinaryPathOnDevice = agentPath
        dataDirectoryOnDevice = dataDir
      }
    val runner =
      AmInstrumentationRunner(
        adb = fakeAdb,
        deviceSerial = deviceSerial,
        instrumentationRunnerClass = "Runner",
        testPackageId = testPackage,
        instrumentationTargetPackageId = targetPackage,
        agentFilesystemInfo = agentFilesystemInfo,
        logger = mockLogger,
        processBuilder = { command ->
          commands.add(command)
          val pb = mock<ProcessBuilder>()
          whenever(pb.start()).thenReturn(if (command.contains("instrument")) mockAmProcess else mock<Process>())
          pb
        },
      )

    runner.runAmInstrumentCommand()

    // 1. Verify host-side polling/attachment is GONE.
    assertThat(commands.any { it.contains("pidof") }).isFalse()
    assertThat(commands.any { it.contains("attach-agent") }).isFalse()

    // 2. Verify instrumentation command includes Java-API config and auto-injected coverage flags.
    val instrumentCmd = commands.find { it.contains("instrument") }
    assertThat(instrumentCmd).isNotNull()
    assertThat(instrumentCmd).contains("-e")
    assertThat(instrumentCmd).contains("coverage")
    assertThat(instrumentCmd).contains("true")
    assertThat(instrumentCmd).contains("listener")
    assertThat(instrumentCmd).contains("com.android.tools.coverage.CoverageAgentAttacher")
    assertThat(instrumentCmd).contains("coverage-agent-config")
    assertThat(instrumentCmd?.joinToString(" ")).contains("$agentPath=$testPackage,com/example/app,$dataDir")
  }

  @Test
  fun runAmInstrumentCommand_doesNotInjectCoverageArgs_whenAgentIsMissing() {
    val mockAmProcess = mock<Process>()
    whenever(mockAmProcess.inputStream).thenReturn("INSTRUMENTATION_CODE: -1".byteInputStream())
    whenever(mockAmProcess.errorStream).thenReturn("".byteInputStream())
    whenever(mockAmProcess.waitFor()).thenReturn(0)

    val commands = mutableListOf<List<String>>()
    val runner =
      AmInstrumentationRunner(
        adb = fakeAdb,
        deviceSerial = "serial",
        instrumentationRunnerClass = "Runner",
        testPackageId = "pkg",
        instrumentationTargetPackageId = "target",
        agentFilesystemInfo = CoverageAgentFilesystemInfo(),
        processBuilder = { command ->
          commands.add(command)
          val pb = mock<ProcessBuilder>()
          whenever(pb.start()).thenReturn(mockAmProcess)
          pb
        },
      )

    runner.runAmInstrumentCommand()

    val instrumentCmd = commands.find { it.contains("instrument") }
    assertThat(instrumentCmd).isNotNull()
    assertThat(instrumentCmd!!.none { it.contains("coverage") }).isTrue()
    assertThat(instrumentCmd.none { it.contains(".pb") }).isTrue()
  }

  @Test
  fun getAmInstrumentCmd_calculatesBroadInclusionPrefixCorrectly() {
    val targetPackage = "com.android.sample.app"
    val testPackage = "com.android.sample.app.test"
    val agentPath = "/path/to/agent.so"
    val dataDir = "/path/to/data"

    val agentFilesystemInfo =
      CoverageAgentFilesystemInfo().apply {
        agentBinaryPathOnDevice = agentPath
        dataDirectoryOnDevice = dataDir
      }
    val runner =
      AmInstrumentationRunner(
        adb = fakeAdb,
        deviceSerial = "serial",
        instrumentationRunnerClass = "Runner",
        testPackageId = testPackage,
        instrumentationTargetPackageId = targetPackage,
        agentFilesystemInfo = agentFilesystemInfo,
      )

    val command =
      runner.javaClass.getDeclaredMethod("getAmInstrumentCmd").let {
        it.isAccessible = true
        it.invoke(runner) as List<String>
      }

    // Verify prefix is "com/android/sample/app" (entire package of "com.android.sample.app")
    assertThat(command.any { it.contains("com/android/sample/app") }).isTrue()
    assertThat(command.any { it.contains("$agentPath=$testPackage,com/android/sample/app,$dataDir") }).isTrue()
  }

  @Test
  fun runAmInstrumentCommand_withInstrumentInPcc() {
    val deviceSerial = "test-device-123"
    val runnerClass = "com.example.TestRunner"
    val targetPackage = "com.example.app"

    whenever(mockProcess.inputStream).thenReturn("INSTRUMENTATION_CODE: -1".byteInputStream())
    whenever(mockProcess.errorStream).thenReturn("".byteInputStream())
    whenever(mockProcessBuilder.start()).thenReturn(mockProcess)

    var capturedCommand: List<String>? = null
    val runner =
      AmInstrumentationRunner(
        adb = fakeAdb,
        deviceSerial = deviceSerial,
        instrumentationRunnerClass = runnerClass,
        testPackageId = targetPackage,
        instrumentationTargetPackageId = targetPackage,
        instrumentInPcc = true,
        logger = mockLogger,
        processBuilder = { command ->
          capturedCommand = command
          mockProcessBuilder
        },
      )

    runner.runAmInstrumentCommand()

    assertThat(capturedCommand).isNotNull()
    assertThat(capturedCommand)
      .containsExactly(
        fakeAdb.absolutePath,
        "-s",
        deviceSerial,
        "shell",
        "am",
        "instrument",
        "-r",
        "-w",
        "--instrument-in-pcc",
        "$targetPackage/$runnerClass",
      )
      .inOrder()
  }
}
