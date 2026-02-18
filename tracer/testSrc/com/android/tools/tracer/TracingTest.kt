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
    assertThat(Tracing.tracer).isNotNull()

    val result = trace { "result" }
    assertThat(result).isEqualTo("result")

    val flushedFile = Tracing.flush()
    assertThat(flushedFile).endsWith("trace-0.perfetto")

    assertThat(File(tempDir, "trace-1.perfetto").exists()).isTrue()

    Tracing.close()
    assertThat(Tracing.tracer).isNull()
  }

  @Test
  fun testTracingDisabled() {
    val config =
      object : TracingConfigProvider {
        override fun isTracingEnabled() = false

        override fun getTraceDirectory() = tempDir
      }

    val disabledFile = "trace-disabled.perfetto"
    Tracing.initialize(config) { dir -> File(dir, disabledFile) }

    assertThat(Tracing.tracer).isNull()
    assertThat(File(tempDir, disabledFile).exists()).isFalse()

    val result = trace { "result" }
    assertThat(result).isEqualTo("result")

    assertThat(Tracing.flush()).isNull()

    assertThat(File(tempDir, disabledFile).exists()).isFalse()
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
}
