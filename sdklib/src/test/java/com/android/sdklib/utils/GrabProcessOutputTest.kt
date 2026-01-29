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
package com.android.utils

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner

/** Unit tests for [GrabProcessOutput]. */
@RunWith(MockitoJUnitRunner::class)
class GrabProcessOutputTest {

  @Mock lateinit var process: Process

  val outputLines = mutableListOf<String>()
  val errorLines = mutableListOf<String>()
  val processOutput =
    object : GrabProcessOutput.IProcessOutput {
      override fun out(line: String?) {
        line?.let { outputLines.add(it) }
      }

      override fun err(line: String?) {
        line?.let { errorLines.add(it) }
      }
    }

  private fun mockProcessStreams(stdout: String, stderr: String) {
    `when`(process.inputStream).thenReturn(ByteArrayInputStream(stdout.toByteArray(StandardCharsets.UTF_8)))
    `when`(process.errorStream).thenReturn(ByteArrayInputStream(stderr.toByteArray(StandardCharsets.UTF_8)))
  }

  @Test
  fun `async wait mode returns immediately`() {
    mockProcessStreams("output line", "error line")

    val exitCode = GrabProcessOutput.grabProcessOutput(process, GrabProcessOutput.Wait.ASYNC, processOutput, null, null)

    assertThat(exitCode).isEqualTo(0)
    verify(process, never()).waitFor()

    // Give threads a moment to run and process the output
    Thread.sleep(100)
    assertThat(outputLines).containsExactly("output line")
    assertThat(errorLines).containsExactly("error line")
  }

  @Test
  fun `waitForProcess mode waits and returns exit code`() {
    mockProcessStreams("some data", "")
    `when`(process.waitFor()).thenReturn(123)

    val exitCode = GrabProcessOutput.grabProcessOutput(process, GrabProcessOutput.Wait.WAIT_FOR_PROCESS, processOutput, null, null)

    assertThat(exitCode).isEqualTo(123)
    verify(process).waitFor()
  }

  @Test
  fun `waitForReaders mode waits for streams and process`() {
    val stdout = "line 1\nline 2"
    val stderr = "error 1\nerror 2"
    mockProcessStreams(stdout, stderr)

    `when`(process.waitFor()).thenReturn(0)

    // Use latches to ensure readers are done before asserting
    val outLatch = CountDownLatch(3) // Adjusted for the final null line
    val errLatch = CountDownLatch(3) // Adjusted for the final null line
    val latchingOutput =
      object : GrabProcessOutput.IProcessOutput {
        override fun out(line: String?) {
          line?.let { outputLines.add(it) }
          outLatch.countDown()
        }

        override fun err(line: String?) {
          line?.let { errorLines.add(it) }
          errLatch.countDown()
        }
      }

    GrabProcessOutput.grabProcessOutput(process, GrabProcessOutput.Wait.WAIT_FOR_READERS, latchingOutput, null, null)

    // Wait for latches to ensure threads have finished processing
    outLatch.await(1, TimeUnit.SECONDS)
    errLatch.await(1, TimeUnit.SECONDS)

    assertThat(outputLines).containsExactly("line 1", "line 2").inOrder()
    assertThat(errorLines).containsExactly("error 1", "error 2").inOrder()
    verify(process).waitFor()
  }

  @Test
  fun `timeout throws TimeoutException`() {
    mockProcessStreams("", "")
    `when`(process.waitFor(1L, TimeUnit.SECONDS)).thenReturn(false)

    try {
      GrabProcessOutput.grabProcessOutput(process, GrabProcessOutput.Wait.WAIT_FOR_PROCESS, processOutput, 1L, TimeUnit.SECONDS)
      fail("Expected TimeoutException was not thrown.")
    } catch (e: TimeoutException) {
      // expected
    }
    verify(process).waitFor(1L, TimeUnit.SECONDS)
  }

  @Test
  fun `timeout successful returns exit value`() {
    mockProcessStreams("", "")
    `when`(process.waitFor(1L, TimeUnit.SECONDS)).thenReturn(true)
    `when`(process.exitValue()).thenReturn(42)

    val exitCode =
      GrabProcessOutput.grabProcessOutput(process, GrabProcessOutput.Wait.WAIT_FOR_PROCESS, processOutput, 1L, TimeUnit.SECONDS)

    assertThat(exitCode).isEqualTo(42)
    verify(process).waitFor(1L, TimeUnit.SECONDS)
    verify(process).exitValue()
  }

  @Test
  fun `null output handler does not crash`() {
    mockProcessStreams("out", "err")
    `when`(process.waitFor()).thenReturn(0)

    val exitCode =
      GrabProcessOutput.grabProcessOutput(
        process,
        GrabProcessOutput.Wait.WAIT_FOR_PROCESS,
        null, // Test with null handler
        null,
        null,
      )

    assertThat(exitCode).isEqualTo(0)
  }

  @Test(expected = InterruptedException::class)
  fun `interruptedException propagates`() {
    mockProcessStreams("", "")
    `when`(process.waitFor()).thenThrow(InterruptedException("Test interrupt"))

    GrabProcessOutput.grabProcessOutput(process, GrabProcessOutput.Wait.WAIT_FOR_PROCESS, processOutput, null, null)
  }
}
