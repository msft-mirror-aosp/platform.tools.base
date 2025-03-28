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
package com.android.testutils

import com.android.sdklib.deviceprovisioner.ProcessHandleProvider
import fleet.fastutil.longs.Long2ObjectOpenHashMap
import org.junit.rules.ExternalResource
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/** Allows tests to inject fake project handles in [ProcessHandleProvider]. */
class ProcessHandleProviderRule(
    var factory: ProcessHandleProvider.Factory? = null
) : ExternalResource() {

  override fun before() {
    ProcessHandleProvider.overrideForTest(factory ?: fakeProcessHandleFactory)
  }

  override fun after() {
    ProcessHandleProvider.overrideForTest(null)
    fakeProcessHandleFactory.processHandles.clear()
  }

  private val fakeProcessHandleFactory =
    object : ProcessHandleProvider.Factory {
      val processHandles = Long2ObjectOpenHashMap<ProcessHandle>()

      override fun getProcessHandle(pid: Long): ProcessHandle? {
        synchronized(processHandles) {
          var handle = processHandles[pid]
          if (handle != null) {
            return handle
          }
          handle = FakeProcessHandle(pid)
          processHandles[pid] = handle
          handle.onExit().thenRun { synchronized(processHandles) { processHandles.remove(pid) } }

          return handle
        }
      }
    }
}

class FakeProcessHandle(val pid: Long) : ProcessHandle {

  private val run = CompletableFuture<ProcessHandle>()

  override fun pid(): Long = pid

  override fun parent(): Optional<ProcessHandle> = Optional.empty()

  override fun children(): Stream<ProcessHandle> = Stream.empty()

  override fun descendants(): Stream<ProcessHandle> = Stream.empty()

  override fun onExit(): CompletableFuture<ProcessHandle> = run

  override fun supportsNormalTermination(): Boolean = true

  override fun destroy(): Boolean = terminate()

  override fun destroyForcibly(): Boolean = terminate()

  override fun isAlive(): Boolean = !run.isDone

  override fun compareTo(other: ProcessHandle): Int = pid.compareTo(other.pid())

  override fun equals(other: Any?): Boolean = other is FakeProcessHandle && pid == other.pid

  override fun hashCode(): Int = pid.toInt()

  override fun toString(): String = pid.toString()

  override fun info(): ProcessHandle.Info {
    return object : ProcessHandle.Info {
      override fun command(): Optional<String> = Optional.empty()

      override fun commandLine(): Optional<String> = Optional.empty()

      override fun arguments(): Optional<Array<out String>> = Optional.empty()

      override fun startInstant(): Optional<Instant> = Optional.empty()

      override fun totalCpuDuration(): Optional<Duration> = Optional.empty()

      override fun user(): Optional<String> = Optional.empty()
    }
  }

  private fun terminate(): Boolean = run.complete(this)
}
