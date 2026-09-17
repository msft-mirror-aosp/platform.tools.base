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

package com.android.tools.coverage.reporter

import com.android.tools.coverage.proto.CoverageMetadataProto.ClassMetadata
import com.android.tools.coverage.proto.CoverageMetadataProto.CoverageHits
import com.android.tools.coverage.proto.CoverageMetadataProto.CoverageMetadata
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.util.BitSet
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BinaryDecoderTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun testDecodeMergedData() {
    // 1. Create a mock metadata file
    val metadata =
      CoverageMetadata.newBuilder()
        .setVersion(1)
        .addClasses(ClassMetadata.newBuilder().setClassName("com/example/MyClass").setSourceFile("MyClass.kt"))
        .build()
    val metadataFile = tempFolder.newFile("metadata.pb")
    metadataFile.outputStream().use { metadata.writeTo(it) }

    // 2. Create a mock hits file (Block IDs 0 and 2 are hit)
    val bitSet = BitSet()
    bitSet.set(0)
    bitSet.set(2)
    val hits = CoverageHits.newBuilder().setVersion(1).setHitMask(ByteString.copyFrom(bitSet.toByteArray())).build()
    val hitsFile = tempFolder.newFile("hits.pb")
    hitsFile.outputStream().use { hits.writeTo(it) }

    // 3. Decode
    val decoder = BinaryDecoder()
    val data = decoder.decode(metadataFile, hitsFile)

    // 4. Verify
    assertThat(data.metadata.classesCount).isEqualTo(1)
    assertThat(data.metadata.getClasses(0).className).isEqualTo("com/example/MyClass")
    assertThat(data.hits.get(0)).isTrue()
    assertThat(data.hits.get(1)).isFalse()
    assertThat(data.hits.get(2)).isTrue()
    assertThat(data.hits.get(3)).isFalse()
  }
}
