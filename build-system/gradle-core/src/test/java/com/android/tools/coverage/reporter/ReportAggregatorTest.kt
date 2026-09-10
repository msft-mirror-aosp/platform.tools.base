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

  @Test
  fun testR8LambdaFiltering() {
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
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("\$r8\$lambda\$wUriQ8Z2fketdTxO")
                .addBlocks(
                  BlockMetadata.newBuilder().setBlockId(1).addLines(LineMetadata.newBuilder().setLineNumber(11).setInstructionCount(10))
                )
            )
        )
        .build()

    val hits = BitSet()
    hits.set(0) // Hit method1
    hits.set(1) // Hit r8 lambda
    val data = CoverageData(metadata, hits)

    val aggregator = ReportAggregator()
    val report = aggregator.aggregate(data, "test")

    val pkg = report.packages["com/example"]!!
    val cls = pkg.classes["com/example/MyClass"]!!

    // The methods list should ONLY contain method1 (NOT the r8 lambda)
    assertThat(cls.methods.size).isEqualTo(1)
    assertThat(cls.methods[0].name).isEqualTo("method1")
    assertThat(cls.methodsCounter.covered).isEqualTo(1) // Only 1 user-declared method covered

    // BUT the instructions coverage of the class must contain BOTH methods (5 + 10 = 15 instructions covered)
    assertThat(cls.instructions.covered).isEqualTo(15)
  }

  @Test
  fun testSpanningBlockBranchParity() {
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
                  BlockMetadata.newBuilder()
                    .setBlockId(0)
                    .setBranchCount(2)
                    .addSuccessorBlockIds(1)
                    .addSuccessorBlockIds(2)
                    .addLines(LineMetadata.newBuilder().setLineNumber(10).setInstructionCount(5))
                    .addLines(LineMetadata.newBuilder().setLineNumber(11).setInstructionCount(5))
                )
                .addBlocks(BlockMetadata.newBuilder().setBlockId(1).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(2).setBranchCount(1))
            )
        )
        .build()

    val hits = BitSet()
    hits.set(0) // Block 0 hit
    hits.set(1) // Successor 1 hit -> 1 covered branch, 1 missed
    val data = CoverageData(metadata, hits)

    val aggregator = ReportAggregator()
    val report = aggregator.aggregate(data, "test")

    val pkg = report.packages["com/example"]!!
    val src = pkg.sourceFiles["MyClass.kt"]!!

    // The spanning block spans lines 10 and 11.
    // - Line 10 (the spanned line) should have exactly 0 branches.
    // - Line 11 (the true branch line) should have exactly 1 covered and 1 missed branch.
    // - The overall source file / package / report branch totals should be exactly 1 covered and 1 missed (NOT doubled!).

    val line10 = src.lineMap[10]!!
    assertThat(line10.cb).isEqualTo(0)
    assertThat(line10.mb).isEqualTo(0)

    val line11 = src.lineMap[11]!!
    assertThat(line11.cb).isEqualTo(1)
    assertThat(line11.mb).isEqualTo(1)

    assertThat(src.branches.covered).isEqualTo(1)
    assertThat(src.branches.missed).isEqualTo(1)
    assertThat(report.branches.covered).isEqualTo(1)
    assertThat(report.branches.missed).isEqualTo(1)
  }

  @Test
  fun testInlineFilter() {
    val smap =
      """
      SMAP
      MyClass.kt
      Kotlin
      *S Kotlin
      *F
      + 1 MyClass.kt
      com/example/MyClass
      + 2 Column.kt
      androidx/compose/foundation/layout/ColumnKt
      *L
      1#1,15:10
      101#2,2:20,2
      *E
      """
        .trimIndent()

    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/MyClass")
            .setSourceFile("MyClass.kt")
            .setSmap(smap)
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("method1")
                // Block 0 represents local non-inlined branches (mapped to output line 10, which resolves to MyClass.kt)
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(0)
                    .setBranchCount(2)
                    .addSuccessorBlockIds(1)
                    .addSuccessorBlockIds(2)
                    .addLines(LineMetadata.newBuilder().setLineNumber(10).setInstructionCount(5))
                )
                // Block 3 represents inlined branches (mapped to output line 20, which resolves to Column.kt)
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(3)
                    .setBranchCount(2)
                    .addSuccessorBlockIds(4)
                    .addSuccessorBlockIds(5)
                    .addLines(LineMetadata.newBuilder().setLineNumber(20).setInstructionCount(5))
                )
                .addBlocks(BlockMetadata.newBuilder().setBlockId(1).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(2).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(4).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(5).setBranchCount(1))
            )
        )
        .build()

    val hits = BitSet()
    hits.set(0) // Block 0 hit (covered branch)
    hits.set(1)
    hits.set(3) // Block 3 (inline) hit
    hits.set(4)
    val data = CoverageData(metadata, hits)

    val aggregator = ReportAggregator()
    val report = aggregator.aggregate(data, "test")

    val pkg = report.packages["com/example"]!!
    val src = pkg.sourceFiles["MyClass.kt"]!!

    // Local Block 0 branches must be kept
    val line1 = src.lineMap[1]!!
    assertThat(line1.cb).isEqualTo(1)
    assertThat(line1.mb).isEqualTo(1)
    assertThat(line1.ci).isEqualTo(5)

    // Inlined Block 3 branches and instructions must be COMPLETELY ignored in the local class report
    assertThat(src.lineMap[20]).isNull()
    assertThat(src.lineMap[101]).isNull()

    // Total branches and instructions for MyClass.kt should only count the local Block 0 (5 instructions, 1/2 branches)
    assertThat(src.instructions.covered).isEqualTo(5)
    assertThat(src.branches.covered).isEqualTo(1)
    assertThat(src.branches.missed).isEqualTo(1)
    assertThat(report.branches.covered).isEqualTo(1)
    assertThat(report.branches.missed).isEqualTo(1)
  }

  @Test
  fun testMixedBlockInlineFilter() {
    val smap =
      """
      SMAP
      MyClass.kt
      Kotlin
      *S Kotlin
      *F
      + 1 MyClass.kt
      com/example/MyClass
      + 2 Column.kt
      androidx/compose/foundation/layout/ColumnKt
      *L
      1#1,15:10
      101#2,2:20,2
      *E
      """
        .trimIndent()

    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/MyClass")
            .setSourceFile("MyClass.kt")
            .setSmap(smap)
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("method1")
                // Block 0 is a Mixed Block:
                // - First instruction maps to line 20 (inlined Column.kt) with 5 instructions.
                // - Last instruction maps to line 10 (local MyClass.kt) with 5 instructions, ending in a conditional branch.
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(0)
                    .setBranchCount(2)
                    .addSuccessorBlockIds(1)
                    .addSuccessorBlockIds(2)
                    .addLines(LineMetadata.newBuilder().setLineNumber(20).setInstructionCount(5)) // Inlined
                    .addLines(LineMetadata.newBuilder().setLineNumber(10).setInstructionCount(5)) // Local
                )
                .addBlocks(BlockMetadata.newBuilder().setBlockId(1).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(2).setBranchCount(1))
            )
        )
        .build()

    val hits = BitSet()
    hits.set(0) // Block 0 hit (covered branch)
    hits.set(1)
    val data = CoverageData(metadata, hits)

    val aggregator = ReportAggregator()
    val report = aggregator.aggregate(data, "test")

    val pkg = report.packages["com/example"]!!
    val src = pkg.sourceFiles["MyClass.kt"]!!

    // Inside our Mixed Block:
    // - Inlined line 20 instructions must be skipped.
    assertThat(src.lineMap[20]).isNull()
    assertThat(src.lineMap[101]).isNull()

    // - Local line 10 (resolved to line 1) instructions must be kept.
    val line1 = src.lineMap[1]!!
    assertThat(line1.ci).isEqualTo(5)

    // - The branches belong to the local file (line 10 is the last line of the block).
    // - The branches must be attached to the first local line (line 10 -> line 1).
    assertThat(line1.cb).isEqualTo(1)
    assertThat(line1.mb).isEqualTo(1)

    // - The total aggregates must reflect only the local portion (5 instructions, 1/2 branches).
    assertThat(src.instructions.covered).isEqualTo(5)
    assertThat(src.branches.covered).isEqualTo(1)
    assertThat(src.branches.missed).isEqualTo(1)
  }

  @Test
  fun testKotlinCoroutineFilter() {
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/MyClass")
            .setSourceFile("MyClass.kt")
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("suspendMethod")
                .setSignature("(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;")
                // Block 0 represents the state-machine setup branch mapped to the declaration line (line 10)
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(0)
                    .setBranchCount(2)
                    .addSuccessorBlockIds(1)
                    .addSuccessorBlockIds(2)
                    .addLines(LineMetadata.newBuilder().setLineNumber(10).setInstructionCount(5))
                )
                // Block 3 represents a real user conditional branch mapped to a body line (line 12)
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(3)
                    .setBranchCount(2)
                    .addSuccessorBlockIds(4)
                    .addSuccessorBlockIds(5)
                    .addLines(LineMetadata.newBuilder().setLineNumber(12).setInstructionCount(5))
                )
                .addBlocks(BlockMetadata.newBuilder().setBlockId(1).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(2).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(4).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(5).setBranchCount(1))
            )
        )
        .build()

    val hits = BitSet()
    hits.set(0) // State machine block hit
    hits.set(1)
    hits.set(3) // User branch block hit
    hits.set(4)
    val data = CoverageData(metadata, hits)

    val aggregator = ReportAggregator()
    val report = aggregator.aggregate(data, "test")

    val pkg = report.packages["com/example"]!!
    val src = pkg.sourceFiles["MyClass.kt"]!!

    // State machine branch on declaration line 10 must be STRIPPED (0 branches)
    val line10 = src.lineMap[10]!!
    assertThat(line10.cb).isEqualTo(0)
    assertThat(line10.mb).isEqualTo(0)
    assertThat(line10.ci).isEqualTo(5)

    // User branch on body line 12 must be PRESERVED (1 covered, 1 missed)
    val line12 = src.lineMap[12]!!
    assertThat(line12.cb).isEqualTo(1)
    assertThat(line12.mb).isEqualTo(1)
    assertThat(line12.ci).isEqualTo(5)

    // Total branches must reflect ONLY the user body branch (1 covered, 1 missed)
    assertThat(src.branches.covered).isEqualTo(1)
    assertThat(src.branches.missed).isEqualTo(1)
  }

  @Test
  fun testBoilerplateMethodsHaveZeroBranches() {
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/MyClass")
            .setSourceFile("MyClass.kt")
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("equals")
                .setSignature("(Ljava/lang/Object;)Z")
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(0)
                    .setBranchCount(2)
                    .addSuccessorBlockIds(1)
                    .addSuccessorBlockIds(2)
                    .addLines(LineMetadata.newBuilder().setLineNumber(10).setInstructionCount(5))
                )
                .addBlocks(BlockMetadata.newBuilder().setBlockId(1).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(2).setBranchCount(1))
            )
        )
        .build()

    val hits = BitSet()
    hits.set(0)
    hits.set(1)
    val data = CoverageData(metadata, hits)

    val aggregator = ReportAggregator()
    val report = aggregator.aggregate(data, "test")

    val pkg = report.packages["com/example"]!!
    val src = pkg.sourceFiles["MyClass.kt"]!!

    // Equals branches must be stripped to 0/0
    assertThat(src.branches.covered).isEqualTo(0)
    assertThat(src.branches.missed).isEqualTo(0)

    // Instructions must still be fully counted (5 covered instructions on line 10)
    val line10 = src.lineMap[10]!!
    assertThat(line10.ci).isEqualTo(5)
  }

  @Test
  fun testComposableSingletonsWrapperHasZeroBranchesButKeepsInstructions() {
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/ComposableSingletons\$MainActivityKt")
            .setSourceFile("MainActivity.kt")
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("getLambda-1\$app_debug")
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(0)
                    .setBranchCount(2)
                    .addSuccessorBlockIds(1)
                    .addSuccessorBlockIds(2)
                    .addLines(LineMetadata.newBuilder().setLineNumber(10).setInstructionCount(5))
                )
                .addBlocks(BlockMetadata.newBuilder().setBlockId(1).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(2).setBranchCount(1))
            )
        )
        .build()

    val hits = BitSet()
    hits.set(0)
    hits.set(1)
    val data = CoverageData(metadata, hits)

    val aggregator = ReportAggregator()
    val report = aggregator.aggregate(data, "test")

    val pkg = report.packages["com/example"]!!
    val src = pkg.sourceFiles["MainActivity.kt"]!!

    // ComposableSingletons wrapper branches must be stripped to 0/0
    assertThat(src.branches.covered).isEqualTo(0)
    assertThat(src.branches.missed).isEqualTo(0)

    // Instructions must still be fully counted (5 covered instructions on line 10)
    val line10 = src.lineMap[10]!!
    assertThat(line10.ci).isEqualTo(5)
  }

  @Test
  fun testComposableSingletonsLambdaPreservesBranches() {
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/ComposableSingletons\$MainActivityKt\$lambda-1\$1")
            .setSourceFile("MainActivity.kt")
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("invoke")
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(0)
                    .setBranchCount(2)
                    .addSuccessorBlockIds(1)
                    .addSuccessorBlockIds(2)
                    .addLines(LineMetadata.newBuilder().setLineNumber(10).setInstructionCount(5))
                )
                .addBlocks(BlockMetadata.newBuilder().setBlockId(1).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(2).setBranchCount(1))
            )
        )
        .build()

    val hits = BitSet()
    hits.set(0)
    hits.set(1)
    val data = CoverageData(metadata, hits)

    val aggregator = ReportAggregator()
    val report = aggregator.aggregate(data, "test")

    val pkg = report.packages["com/example"]!!
    val src = pkg.sourceFiles["MainActivity.kt"]!!

    // User lambda branches must be preserved (1 covered, 1 missed)
    assertThat(src.branches.covered).isEqualTo(1)
    assertThat(src.branches.missed).isEqualTo(1)

    // Instructions must be fully counted (5 covered instructions on line 10)
    val line10 = src.lineMap[10]!!
    assertThat(line10.ci).isEqualTo(5)
  }

  @Test
  fun testDefaultParameterMethodHasZeroBranches() {
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/MyClass")
            .setSourceFile("MyClass.kt")
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("method1\$default")
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(0)
                    .setBranchCount(24)
                    .addSuccessorBlockIds(1)
                    .addSuccessorBlockIds(2)
                    .addLines(LineMetadata.newBuilder().setLineNumber(10).setInstructionCount(5))
                )
                .addBlocks(BlockMetadata.newBuilder().setBlockId(1).setBranchCount(1))
                .addBlocks(BlockMetadata.newBuilder().setBlockId(2).setBranchCount(1))
            )
        )
        .build()

    val hits = BitSet()
    hits.set(0)
    hits.set(1)
    val data = CoverageData(metadata, hits)

    val aggregator = ReportAggregator()
    val report = aggregator.aggregate(data, "test")

    val pkg = report.packages["com/example"]!!
    val src = pkg.sourceFiles["MyClass.kt"]!!

    // Default parameter branches must be stripped to 0/0
    assertThat(src.branches.covered).isEqualTo(0)
    assertThat(src.branches.missed).isEqualTo(0)
  }
}
