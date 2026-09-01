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
package com.android.tools.perflib.heap.hprof

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Test

class HprofRecordsTest {

  @Test
  fun testHprofRootNativeStack() {
    val objectId = 0x12345678L
    val threadSerialNumber = 42
    val record = HprofRootNativeStack(objectId, threadSerialNumber)

    // Test getLength
    assertThat(record.getLength(4)).isEqualTo(1 + 4 + 4)
    assertThat(record.getLength(8)).isEqualTo(1 + 8 + 4)

    // Test write
    val baos = ByteArrayOutputStream()
    val hos = HprofOutputStream(4, baos)
    record.write(hos)
    hos.flush()

    val bytes = baos.toByteArray()
    assertThat(bytes.size).isEqualTo(record.getLength(4))
    assertThat(bytes[0]).isEqualTo(HprofRootNativeStack.SUBTAG)
  }

  @Test
  fun testHprofRootVmInternal() {
    val objectId = 0x87654321L
    val record = HprofRootVmInternal(objectId)

    // Test getLength
    assertThat(record.getLength(4)).isEqualTo(1 + 4)
    assertThat(record.getLength(8)).isEqualTo(1 + 8)

    // Test write
    val baos = ByteArrayOutputStream()
    val hos = HprofOutputStream(4, baos)
    record.write(hos)
    hos.flush()

    val bytes = baos.toByteArray()
    assertThat(bytes.size).isEqualTo(record.getLength(4))
    assertThat(bytes[0]).isEqualTo(HprofRootVmInternal.SUBTAG)
  }
}
