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

package com.android.utils

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.fail
import org.junit.Test

class GrabProcessOutputTest {

  @Test
  fun testTimeoutWithWaitForReaders() {
    // A process that sleeps for 5 seconds
    val pb = ProcessBuilder("sleep", "5")
    val process = pb.start()

    val start = System.currentTimeMillis()
    try {
      // Use WAIT_FOR_READERS and a 1-second timeout
      GrabProcessOutput.grabProcessOutput(
        process,
        GrabProcessOutput.Wait.WAIT_FOR_READERS,
        null, // no output handler
        1L,
        TimeUnit.SECONDS,
      )
      fail("Expected TimeoutException")
    } catch (e: TimeoutException) {
      val duration = System.currentTimeMillis() - start
      // It should time out quickly, not wait for 5 seconds
      assertThat(duration).isLessThan(2000L)
    } finally {
      process.destroy()
    }
  }

  @Test
  fun testOutputIsCapturedWithWaitForReaders() {
    // A process that prints something and finishes
    val pb = ProcessBuilder("echo", "hello world")
    val process = pb.start()

    val outputLines = mutableListOf<String>()
    GrabProcessOutput.grabProcessOutput(
      process,
      GrabProcessOutput.Wait.WAIT_FOR_READERS,
      object : GrabProcessOutput.IProcessOutput {
        override fun out(line: String?) {
          line?.let { outputLines.add(it) }
        }

        override fun err(line: String?) {}
      },
      5L,
      TimeUnit.SECONDS,
    )

    assertThat(outputLines).contains("hello world")
  }
}
