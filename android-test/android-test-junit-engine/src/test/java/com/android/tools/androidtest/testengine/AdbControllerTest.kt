/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tools.androidtest.testengine

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Unit tests for [AdbController]. */
class AdbControllerTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var adb: File

  @Before
  fun setUp() {
    adb = tempFolder.newFile("adb")
  }

  private fun createAdbController(exitCode: Int, output: String = "", error: String = ""): AdbController {
    val process =
      mock<Process> {
        on { it.exitValue() } doReturn exitCode
        on { it.inputStream } doReturn output.byteInputStream()
        on { it.errorStream } doReturn error.byteInputStream()
        on { it.waitFor(any(), any()) } doReturn true
      }
    return AdbController(adb, processBuilder = { mock { on { start() } doReturn process } })
  }

  @Test
  fun runCommand_success() {
    val controller = createAdbController(0, output = "success-output")
    val result = controller.runCommand(listOf("some-cmd"))

    assertThat(result.exitCode).isEqualTo(0)
    assertThat(result.output).isEqualTo("success-output")
    assertThat(result.errorOutput).isEmpty()
  }

  @Test
  fun runCommand_failure() {
    val controller = createAdbController(1, error = "error-output")
    val result = controller.runCommand(listOf("some-cmd"))

    assertThat(result.exitCode).isEqualTo(1)
    assertThat(result.output).isEmpty()
    assertThat(result.errorOutput).isEqualTo("error-output")
  }

  @Test
  fun runAdbCommand() {
    val executedCommands = mutableListOf<String>()
    val process =
      mock<Process> {
        on { it.exitValue() } doReturn 0
        on { it.inputStream } doReturn "".byteInputStream()
        on { it.errorStream } doReturn "".byteInputStream()
        on { it.waitFor(any(), any()) } doReturn true
      }
    val controller =
      AdbController(
        adb,
        processBuilder = { command ->
          executedCommands.add(command.joinToString(" "))
          mock { on { start() } doReturn process }
        },
      )

    controller.runAdbCommand("serial-123", listOf("install", "app.apk"))

    assertThat(executedCommands).containsExactly("${adb.absolutePath} -s serial-123 install app.apk")
  }

  @Test
  fun runAdbShellCommand() {
    val executedCommands = mutableListOf<String>()
    val process =
      mock<Process> {
        on { it.exitValue() } doReturn 0
        on { it.inputStream } doReturn "".byteInputStream()
        on { it.errorStream } doReturn "".byteInputStream()
        on { it.waitFor(any(), any()) } doReturn true
      }
    val controller =
      AdbController(
        adb,
        processBuilder = { command ->
          executedCommands.add(command.joinToString(" "))
          mock { on { start() } doReturn process }
        },
      )

    controller.runAdbShellCommand("serial-123", listOf("getprop", "ro.build.version.sdk"))

    assertThat(executedCommands).containsExactly("${adb.absolutePath} -s serial-123 shell getprop ro.build.version.sdk")
  }

  @Test
  fun pull() {
    val executedCommands = mutableListOf<String>()
    val process =
      mock<Process> {
        on { it.exitValue() } doReturn 0
        on { it.inputStream } doReturn "".byteInputStream()
        on { it.errorStream } doReturn "".byteInputStream()
        on { it.waitFor(any(), any()) } doReturn true
      }
    val controller =
      AdbController(
        adb,
        processBuilder = { command ->
          executedCommands.add(command.joinToString(" "))
          mock { on { start() } doReturn process }
        },
      )

    controller.pull("serial-123", "/sdcard/file", "/host/file")

    assertThat(executedCommands).containsExactly("${adb.absolutePath} -s serial-123 pull /sdcard/file /host/file")
  }

  @Test
  fun push() {
    val executedCommands = mutableListOf<String>()
    val process =
      mock<Process> {
        on { it.exitValue() } doReturn 0
        on { it.inputStream } doReturn "".byteInputStream()
        on { it.errorStream } doReturn "".byteInputStream()
        on { it.waitFor(any(), any()) } doReturn true
      }
    val controller =
      AdbController(
        adb,
        processBuilder = { command ->
          executedCommands.add(command.joinToString(" "))
          mock { on { start() } doReturn process }
        },
      )

    controller.push("serial-123", "/host/file", "/sdcard/file")

    assertThat(executedCommands).containsExactly("${adb.absolutePath} -s serial-123 push /host/file /sdcard/file")
  }

  @Test
  fun runCommand_timeout() {
    val process = mock<Process>()
    doAnswer {
        doReturn(137).whenever(process).exitValue()
        false
      }
      .whenever(process)
      .waitFor(any<Long>(), any<TimeUnit>())
    doReturn("".byteInputStream()).whenever(process).inputStream
    doReturn("".byteInputStream()).whenever(process).errorStream
    doThrow(IllegalThreadStateException("Process still alive")).whenever(process).exitValue()

    // When waitFor() is called, make exitValue() return a value instead of throwing.
    doAnswer {
        doReturn(137).whenever(process).exitValue()
        137
      }
      .whenever(process)
      .waitFor()

    val controller = AdbController(adb, processBuilder = { mock { on { start() } doReturn process } })

    val result = controller.runCommand(listOf("some-cmd"), java.time.Duration.ofMillis(10))

    assertThat(result.exitCode).isEqualTo(137)
  }

  @Test
  fun runAdbShellCommandToOutputStream_timeout() {
    val process = mock<Process>()
    doAnswer {
        doReturn(137).whenever(process).exitValue()
        false
      }
      .whenever(process)
      .waitFor(any<Long>(), any<TimeUnit>())
    doReturn("".byteInputStream()).whenever(process).inputStream
    doReturn("".byteInputStream()).whenever(process).errorStream
    doThrow(IllegalThreadStateException("Process still alive")).whenever(process).exitValue()

    // When waitFor() is called, make exitValue() return a value instead of throwing.
    doAnswer {
        doReturn(137).whenever(process).exitValue()
        137
      }
      .whenever(process)
      .waitFor()

    val controller = AdbController(adb, processBuilder = { mock { on { start() } doReturn process } })

    val outputStream = java.io.ByteArrayOutputStream()
    val exitCode = controller.runAdbShellCommandToOutputStream("serial", listOf("args"), outputStream, java.time.Duration.ofMillis(10))

    assertThat(exitCode).isEqualTo(137)
  }
}
