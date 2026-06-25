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

import com.android.tools.coverage.proto.CoverageMetadataProto.BlockMetadata
import com.android.tools.coverage.proto.CoverageMetadataProto.ClassMetadata
import com.android.tools.coverage.proto.CoverageMetadataProto.CoverageMetadata
import com.android.tools.coverage.proto.CoverageMetadataProto.LineMetadata
import com.android.tools.coverage.proto.CoverageMetadataProto.MethodMetadata
import com.google.common.truth.Truth.assertThat
import java.util.BitSet
import org.junit.Test

class ReportAggregatorTest {

  @Test
  fun testHierarchicalAggregation() {
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/MyClass")
            .setSourceFile("MyClass.kt")
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("method1")
                .addBlocks(
                  BlockMetadata.newBuilder().setBlockId(0).addLines(LineMetadata.newBuilder().setLineNumber(10).setInstructionCount(5))
                )
            )
        )
        .build()

    val hits = BitSet()
    hits.set(0) // Hit method1
    val data = CoverageData(metadata, hits)

    val aggregator = ReportAggregator()
    val report = aggregator.aggregate(data, "test")

    val pkg = report.packages["com/example"]!!
    val cls = pkg.classes["com/example/MyClass"]!!
    val src = pkg.sourceFiles["MyClass.kt"]!!

    // Verify all levels aggregated correctly
    assertThat(cls.instructions.covered).isEqualTo(5)
    assertThat(src.instructions.covered).isEqualTo(5)
    assertThat(pkg.instructions.covered).isEqualTo(5)
    assertThat(report.instructions.covered).isEqualTo(5)

    assertThat(cls.methodsCounter.covered).isEqualTo(1)
    assertThat(report.methodsCounter.covered).isEqualTo(1)
  }

  @Test
  fun testSafeRFilter() {
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/R")
            .setSourceFile("") // DEX-generated R has empty source file
            .addMethods(MethodMetadata.newBuilder().setName("<init>"))
        )
        .build()

    val data = CoverageData(metadata, BitSet())
    val report = ReportAggregator().aggregate(data, "test")

    assertThat(report.packages).isEmpty()
  }

  @Test
  fun testExclusionList() {
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(ClassMetadata.newBuilder().setClassName("com/example/Secret").setSourceFile("Secret.kt"))
        .build()

    val data = CoverageData(metadata, BitSet())
    val report = ReportAggregator().aggregate(data, "test", exclusions = setOf("com.example.Secret"))

    assertThat(report.packages).isEmpty()
  }
}
