/*
 * Copyright 2025 The Android Open Source Project
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
package com.android.tools.appinspection.network.trackers

import com.android.tools.appinspection.network.reporters.StreamReporter
import com.android.tools.appinspection.network.reporters.ThreadReporter
import com.android.tools.appinspection.network.testing.FakeConnection
import com.android.tools.appinspection.network.testing.TestStreamReporter
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

class InputStreamTrackerTest {
  private val reporter = streamReporter()

  @Test
  fun read_allBytes_implicit() {
    val stream = ByteArrayInputStream("data\u0000-012345".toByteArray())
    val tracker = InputStreamTracker(stream, reporter)

    tracker.use { it.read(ByteArray(100)) }

    assertThat(reporter.data).isEqualTo("data\u0000-012345")
  }

  @Test
  fun read_allBytes_explicit() {
    val stream = ByteArrayInputStream("data\u0000-012345".toByteArray())
    val tracker = InputStreamTracker(stream, reporter)

    tracker.use { it.read(ByteArray(100), 0, 100) }

    assertThat(reporter.data).isEqualTo("data\u0000-012345")
  }

  @Test
  fun read_allBytes_oneByOne() {
    val stream = ByteArrayInputStream("data\u0000-012345".toByteArray())
    val tracker = InputStreamTracker(stream, reporter)

    tracker.use { tracker -> @Suppress("ControlFlowWithEmptyBody") while (tracker.read() >= 0) {} }

    assertThat(reporter.data).isEqualTo("data\u0000-012345")
  }

  @Test
  fun skip() {
    val stream = ByteArrayInputStream("12345".toByteArray())
    val tracker = InputStreamTracker(stream, reporter)

    tracker.use {
      it.read()
      it.skip(3)
      it.read()
    }

    assertThat(reporter.data).isEqualTo("12345")
  }

  @Test
  fun skip_tooMany() {
    val stream = ByteArrayInputStream("Very long data...".toByteArray())
    val tracker = InputStreamTracker(stream, reporter)

    tracker.use {
      it.read(ByteArray(9))
      it.skip(1L + StreamReporter.MAX_BUFFER_SIZE)
    }

    assertThat(reporter.data).isEqualTo("Very long...Skipped 8 bytes...")
  }

  private fun streamReporter() =
    TestStreamReporter(
      FakeConnection(),
      object : ThreadReporter {
        override fun reportCurrentThread() {}
      },
      connectionId = 1,
      maxBufferSize = 10 * 1024 * 1024,
      bufferHelper = null,
    )
}
