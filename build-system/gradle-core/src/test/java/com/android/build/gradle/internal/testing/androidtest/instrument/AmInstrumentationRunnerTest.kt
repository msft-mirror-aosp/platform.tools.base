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

package com.android.build.gradle.internal.testing.androidtest.instrument

import com.google.common.truth.Truth.assertThat
import org.gradle.api.logging.Logger
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
import java.io.File

/**
 * Unit tests for [AmInstrumentationRunner].
 */
@RunWith(JUnit4::class)
class AmInstrumentationRunnerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

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

    val stdout = """
            INSTRUMENTATION_STATUS_CODE: 1
            INSTRUMENTATION_STATUS: class=com.example.MyTest
            INSTRUMENTATION_STATUS: test=testExample
            INSTRUMENTATION_STATUS_CODE: 0
            INSTRUMENTATION_CODE: -1
        """.trimIndent()

    val stderr = "Warning: This is a test warning."

    // Mock the process to return our predefined stdout and stderr
    whenever(mockProcess.inputStream).thenReturn(stdout.byteInputStream())
    whenever(mockProcess.errorStream).thenReturn(stderr.byteInputStream())
    whenever(mockProcess.waitFor()).thenReturn(0)

    // Mock the process builder to return our mocked process
    whenever(mockProcessBuilder.start()).thenReturn(mockProcess)

    var capturedCommand: List<String>? = null
    val runner = AmInstrumentationRunner(
      adb = fakeAdb,
      deviceSerial = deviceSerial,
      instrumentationRunnerClass = runnerClass,
      instrumentationTargetPackageId = targetPackage,
      logger = mockLogger,
      processBuilder = { command ->
        capturedCommand = command
        mockProcessBuilder
      }
    )

    runner.runAmInstrumentCommand()

    // Verify the correct command was constructed and passed to the process builder.
    assertThat(capturedCommand).isNotNull()
    assertThat(capturedCommand).containsExactly(
      fakeAdb.absolutePath,
      "-s", deviceSerial,
      "shell", "am", "instrument",
      "-r",
      "-w",
      "$targetPackage/$runnerClass"
    ).inOrder()

    // Verify that the process was started.
    verify(mockProcessBuilder).start()

    // Verify that stderr was logged as a warning.
    val warningCaptor = argumentCaptor<String>()
    verify(mockLogger).warn(warningCaptor.capture())
    assertThat(warningCaptor.firstValue).isEqualTo(stderr)
  }
}
