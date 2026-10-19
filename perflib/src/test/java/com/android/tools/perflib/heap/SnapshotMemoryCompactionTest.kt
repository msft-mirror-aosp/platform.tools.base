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
package com.android.tools.perflib.heap

import com.android.tools.perflib.captures.MemoryMappedFileBuffer
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SnapshotMemoryCompactionTest {

  @Test
  fun testCompactMemoryWithManyUniqueReferences() {
    val tempFile =
      File.createTempFile("empty_buffer", ".hprof").apply {
        deleteOnExit()
        writeBytes(ByteArray(100))
      }
    val buffer = MemoryMappedFileBuffer(tempFile)
    val snapshot = Snapshot(buffer)
    val heap = snapshot.setHeapTo(1, "app")

    val nodeCount = 100_000
    val instances = Array(nodeCount) { id -> RootObj(RootType.UNKNOWN, id.toLong()).also { heap.addInstance(id.toLong(), it) } }

    // Build unique reference lists for every instance
    for (i in 0 until nodeCount) {
      val next = instances[(i + 1) % nodeCount]
      val next2 = instances[(i + 2) % nodeCount]
      instances[i]._hardFwdRefs += next
      instances[i]._hardFwdRefs += next2
      instances[i]._hardRevRefs += instances[(i + nodeCount - 1) % nodeCount]
      instances[i]._hardRevRefs += instances[(i + nodeCount - 2) % nodeCount]
    }

    val runtime = Runtime.getRuntime()
    runtime.gc()
    val usedMemoryBefore = runtime.totalMemory() - runtime.freeMemory()
    val startTime = System.currentTimeMillis()

    snapshot.compactMemory()

    val durationMs = System.currentTimeMillis() - startTime
    val usedMemoryAfter = runtime.totalMemory() - runtime.freeMemory()

    println(
      "compactMemory took ${durationMs}ms for $nodeCount nodes, memory delta: ${(usedMemoryAfter - usedMemoryBefore) / (1024 * 1024)}MB"
    )

    // Verify all instances still have their references intact
    for (i in 0 until nodeCount) {
      val fwd = instances[i].hardForwardReferences.toList()
      assertThat(fwd).containsExactly(instances[(i + 1) % nodeCount], instances[(i + 2) % nodeCount])

      val rev = instances[i].hardReverseReferences.toList()
      assertThat(rev).containsExactly(instances[(i + nodeCount - 1) % nodeCount], instances[(i + nodeCount - 2) % nodeCount])
    }

    snapshot.dispose()
  }

  @Test
  fun testCompactMemoryUnboxesSingleElementListsAndCollapsesEmpty() {
    val tempFile =
      File.createTempFile("empty_buffer2", ".hprof").apply {
        deleteOnExit()
        writeBytes(ByteArray(100))
      }
    val buffer = MemoryMappedFileBuffer(tempFile)
    val snapshot = Snapshot(buffer)
    val heap = snapshot.setHeapTo(1, "app")

    val inst1 = RootObj(RootType.UNKNOWN, 1L).also { heap.addInstance(1L, it) }
    val inst2 = RootObj(RootType.UNKNOWN, 2L).also { heap.addInstance(2L, it) }
    val inst3 = RootObj(RootType.UNKNOWN, 3L).also { heap.addInstance(3L, it) }

    // inst1 has an array with duplicates that should collapse and unbox to a single Instance
    inst1._hardFwdRefs = InstanceList.of(arrayOf(inst2, inst2, null))
    // inst2 has empty fwd refs
    // inst3 has 2 distinct elements
    inst3._hardFwdRefs = InstanceList.of(arrayOf(inst1, inst2, null))

    snapshot.compactMemory()

    // inst1._hardFwdRefs raw should be unboxed Instance, not an Array
    inst1._hardFwdRefs.onCases(
      onInstance = { assertThat(it).isEqualTo(inst2) },
      onArray = { org.junit.Assert.fail("Expected unboxed single Instance, but got Array of size ${it.size}") },
    )

    // inst2._hardFwdRefs raw should be empty
    assertThat(inst2._hardFwdRefs.asList()).isEmpty()

    // inst3._hardFwdRefs should have 2 elements
    assertThat(inst3._hardFwdRefs.asList()).containsExactly(inst1, inst2)

    snapshot.dispose()
  }

  @Test
  fun testCompactMemoryLargeScaleOneMillionNodes() {
    val tempFile =
      File.createTempFile("empty_buffer_large", ".hprof").apply {
        deleteOnExit()
        writeBytes(ByteArray(100))
      }
    val buffer = MemoryMappedFileBuffer(tempFile)
    val snapshot = Snapshot(buffer)
    val heap = snapshot.setHeapTo(1, "app")

    val nodeCount = 1_000_000
    val instances = Array(nodeCount) { id -> RootObj(RootType.UNKNOWN, id.toLong()).also { heap.addInstance(id.toLong(), it) } }

    for (i in 0 until nodeCount) {
      val next = instances[(i + 1) % nodeCount]
      val next2 = instances[(i + 2) % nodeCount]
      instances[i]._hardFwdRefs += next
      instances[i]._hardFwdRefs += next2
      instances[i]._hardRevRefs += instances[(i + nodeCount - 1) % nodeCount]
      instances[i]._hardRevRefs += instances[(i + nodeCount - 2) % nodeCount]
    }

    val runtime = Runtime.getRuntime()
    runtime.gc()
    val usedMemoryBefore = runtime.totalMemory() - runtime.freeMemory()
    val startTime = System.currentTimeMillis()

    snapshot.compactMemory()

    val durationMs = System.currentTimeMillis() - startTime
    val usedMemoryAfter = runtime.totalMemory() - runtime.freeMemory()

    println(
      "LARGE SCALE: compactMemory took ${durationMs}ms for $nodeCount nodes, memory delta: ${(usedMemoryAfter - usedMemoryBefore) / (1024 * 1024)}MB"
    )

    assertThat(instances[0].hardForwardReferences.toList()).containsExactly(instances[1], instances[2])
    assertThat(instances[nodeCount - 1].hardForwardReferences.toList()).containsExactly(instances[0], instances[1])

    snapshot.dispose()
  }
}
