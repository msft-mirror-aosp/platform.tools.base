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
package com.android.tools.tracer

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TracingTest {
  private val tempDir = Files.createTempDirectory("tracing-test").toFile()

  @After
  fun tearDown() {
    Tracing.close()
    tempDir.deleteRecursively()
  }

  @Test
  fun testTracingLifecycle() {
    val config =
      object : TracingConfigProvider {
        override fun isTracingEnabled() = true

        override fun getTraceDirectory() = tempDir
      }

    var fileCounter = 0
    val fileProvider: (File) -> File = { dir -> File(dir, "trace-${fileCounter++}.perfetto") }

    Tracing.initialize(config, fileProvider)

    assertThat(File(tempDir, "trace-0.perfetto").exists()).isTrue()
    assertThat(File(tempDir, "trace-1.perfetto").exists()).isFalse()
    val initialTracer = Tracing.tracer
    assertThat(initialTracer).isNotNull()

    val result = trace { "result" }
    assertThat(result).isEqualTo("result")

    val flushedFile = Tracing.flush()
    assertThat(flushedFile).endsWith("trace-0.perfetto")
    assertThat(File(tempDir, "trace-0.perfetto").length() > 0).isTrue()

    // Assert that the tracer instance was NOT reused after flushing because we aren't using a ring buffer.
    val tracerAfterFlush = Tracing.tracer
    assertThat(tracerAfterFlush).isNotSameAs(initialTracer)
    assertThat(tracerAfterFlush).isNotNull()

    // The next file is created, despite not having any data.
    assertThat(File(tempDir, "trace-1.perfetto").exists()).isTrue()
    assertEquals(0L, File(tempDir, "trace-1.perfetto").length())

    Tracing.close()
    assertThat(Tracing.tracer).isNull()
  }

  @Test
  fun testTracingRingBufferLifecycle() {
    val config =
      object : TracingConfigProvider {
        override fun isTracingEnabled() = true

        override fun getTraceDirectory() = tempDir

        override fun getRingBufferCapacity(): Long = 10_000_000
      }

    var fileCounter = 0
    val fileProvider: (File) -> File = { dir -> File(dir, "trace-${fileCounter++}.perfetto") }

    Tracing.initialize(config, fileProvider)

    val initialTracer = Tracing.tracer
    assertThat(initialTracer).isNotNull()

    // A file won't be created until flushing.
    val file0 = File(tempDir, "trace-0.perfetto")
    assertThat(file0.exists()).isFalse()

    val result = trace { "result" }
    assertThat(result).isEqualTo("result")

    val flushedFile = Tracing.flush()
    assertThat(flushedFile).endsWith(file0.name)
    assertThat(file0.exists()).isTrue()
    assertThat(file0.length() > 0L).isTrue()

    // Assert that the tracer instance was reused after flushing.
    val tracerAfterFlush = Tracing.tracer
    assertThat(tracerAfterFlush).isSameAs(initialTracer)

    Tracing.close()

    assertThat(Tracing.tracer).isNull()

    // The next file doesn't exist.
    assertThat(File(tempDir, "trace-1.perfetto").exists()).isFalse()
  }

  @Test
  fun testTracingDisabled() {
    val config =
      object : TracingConfigProvider {
        override fun isTracingEnabled() = false

        override fun getTraceDirectory() = tempDir

        override fun getRingBufferCapacity(): Long = 10_000_000
      }

    val disabledFile = File(tempDir, "trace-disabled.perfetto")
    Tracing.initialize(config) { disabledFile }

    assertThat(Tracing.tracer).isNotNull()
    assertThat(disabledFile.exists()).isFalse()

    val result = trace { "result" }
    assertThat(result).isEqualTo("result")

    assertThat(Tracing.flush()).endsWith("trace-disabled.perfetto")

    // Despite flushing, nothing is written because we're disabled.
    assertThat(disabledFile.exists()).isTrue()
    assertEquals(0L, disabledFile.length())
  }

  @Test
  fun testTracingNotInitialized() {
    assertThat(Tracing.flush()).isNull()
    assertThat(Tracing.tracer).isNull()
    val result = trace { "result" }
    assertThat(result).isEqualTo("result")
  }

  @Test
  fun testTracingExceptionPropagation() {
    val config =
      object : TracingConfigProvider {
        override fun isTracingEnabled() = true

        override fun getTraceDirectory() = tempDir

        override fun getRingBufferCapacity(): Long = 10_000_000
      }
    Tracing.initialize(config) { File(it, "trace.perfetto") }

    try {
      trace { throw RuntimeException("Intentional Failure") }
      // Fail if exception is swallowed
      @Suppress("KotlinUnreachableCode") fail("Exception should have propagated")
    } catch (e: RuntimeException) {
      assertThat(e.message).isEqualTo("Intentional Failure")
    }
  }

  @Test
  fun testPublicInitializeShortCircuitsIfConfigIsSame() {
    val config =
      object : TracingConfigProvider {
        override fun isTracingEnabled() = true

        override fun getTraceDirectory() = tempDir
      }

    Tracing.initialize(config)
    val initialTracer = Tracing.tracer
    assertThat(initialTracer).isNotNull()

    val config2 =
      object : TracingConfigProvider {
        override fun isTracingEnabled() = true

        override fun getTraceDirectory() = tempDir
      }
    Tracing.initialize(config2)

    val secondTracer = Tracing.tracer
    assertThat(secondTracer).isSameAs(initialTracer)

    val config3 =
      object : TracingConfigProvider {
        override fun isTracingEnabled() = false

        override fun getTraceDirectory() = tempDir
      }
    Tracing.initialize(config3)

    val thirdTracer = Tracing.tracer
    assertThat(thirdTracer).isNotSameAs(initialTracer)
  }
}
