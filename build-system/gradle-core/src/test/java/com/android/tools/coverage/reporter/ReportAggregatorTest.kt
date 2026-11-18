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
import java.io.File
import java.util.BitSet
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReportAggregatorTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

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
  fun testRAndManifestClassesAreLeftOutWithASource() {
    // BuildConfig stays: a module with build config fields generates its source in a folder among the source folders, and the fields can
    // run code.
    val sourceFolder = temporaryFolder.newFolder("src")
    for (name in listOf("BuildConfig.java", "R.java", "Manifest.java", "ManifestReader.kt")) {
      File(sourceFolder, "com/example/$name").apply { parentFile.mkdirs() }.writeText("package com.example")
    }
    fun classMetadata(className: String, sourceFile: String) =
      ClassMetadata.newBuilder().setClassName(className).setSourceFile(sourceFile).addMethods(MethodMetadata.newBuilder().setName("<init>"))
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(classMetadata("com/example/BuildConfig", "BuildConfig.java"))
        .addClasses(classMetadata("com/example/R", "R.java"))
        .addClasses(classMetadata("com/example/R\$string", "R.java"))
        .addClasses(classMetadata("com/example/Manifest", "Manifest.java"))
        .addClasses(classMetadata("com/example/Manifest\$permission", "Manifest.java"))
        // Only the names themselves count.
        .addClasses(classMetadata("com/example/ManifestReader", "ManifestReader.kt"))
        .build()

    val report = ReportAggregator().aggregate(CoverageData(metadata, BitSet()), "test", sourceFolders = listOf(sourceFolder))

    assertThat(report.packages["com/example"]!!.classes.keys).containsExactly("com/example/BuildConfig", "com/example/ManifestReader")
  }

  @Test
  fun testManifestClassesAreLeftOutWithoutASource() {
    // As they are when they come compiled from a jar, without a source file. BuildConfig stays, as any other class does.
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder().setClassName("com/example/BuildConfig").addMethods(MethodMetadata.newBuilder().setName("<init>"))
        )
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/Manifest\$permission")
            .addMethods(MethodMetadata.newBuilder().setName("<init>"))
        )
        .build()

    val report = ReportAggregator().aggregate(CoverageData(metadata, BitSet()), "test")

    assertThat(report.packages["com/example"]!!.classes.keys).containsExactly("com/example/BuildConfig")
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

  @Test
  fun testClassWithoutMethodsIsNotCounted() {
    // MyObject has no methods left in the metadata, for example because the agent filtered all of them out.
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
        .addClasses(ClassMetadata.newBuilder().setClassName("com/example/MyObject").setSourceFile("MyClass.kt"))
        .build()

    val hits = BitSet()
    hits.set(0) // Hit method1
    val report = ReportAggregator().aggregate(CoverageData(metadata, hits), "test")

    val pkg = report.packages["com/example"]!!
    assertThat(pkg.classes["com/example/MyObject"]!!.classesCounter).isEqualTo(Counter(covered = 0, missed = 0))
    assertThat(pkg.classes["com/example/MyClass"]!!.classesCounter).isEqualTo(Counter(covered = 1, missed = 0))
    assertThat(pkg.sourceFiles["MyClass.kt"]!!.classesCounter).isEqualTo(Counter(covered = 1, missed = 0))
    assertThat(pkg.classesCounter).isEqualTo(Counter(covered = 1, missed = 0))
    assertThat(report.classesCounter).isEqualTo(Counter(covered = 1, missed = 0))
  }

  @Test
  fun testClassesThatTheProjectCompilesAreReportedWithoutASource() {
    val sourceFolder = temporaryFolder.newFolder("src")
    File(sourceFolder, "com/example/Api.kt").apply { parentFile.mkdirs() }.writeText("package com.example")
    val metadata =
      CoverageMetadata.newBuilder()
        // D8 adds this companion class for the interface in Api.kt: the project doesn't compile it, but its source is in the source folder.
        .addClasses(ClassMetadata.newBuilder().setClassName("com/example/Api\$-CC").setSourceFile("Api.kt"))
        // The project compiles this class from a source generated outside the source folders, as Hilt does for its components.
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/DaggerApp_HiltComponents_SingletonC")
            .setSourceFile("DaggerApp_HiltComponents_SingletonC.java")
        )
        // A class of a library: the project doesn't compile it and has no source for it.
        .addClasses(ClassMetadata.newBuilder().setClassName("com/example/Library").setSourceFile("Library.kt"))
        .build()

    val report =
      ReportAggregator()
        .aggregate(
          CoverageData(metadata, BitSet()),
          "test",
          sourceFolders = listOf(sourceFolder),
          projectClasses = setOf("com/example/DaggerApp_HiltComponents_SingletonC"),
        )

    assertThat(report.packages["com/example"]!!.classes.keys)
      .containsExactly("com/example/Api\$-CC", "com/example/DaggerApp_HiltComponents_SingletonC")
  }

  @Test
  fun testClassesThatTheProjectCompilesAreReportedInThePackageOfTheTests() {
    fun classMetadata(className: String) =
      ClassMetadata.newBuilder()
        .setClassName(className)
        .setSourceFile("Fixture.kt")
        .addMethods(MethodMetadata.newBuilder().setName("<init>"))
    val metadata =
      CoverageMetadata.newBuilder()
        // The module compiles a class in the package of its test APK, as the build-time report covers it.
        .addClasses(classMetadata("com/example/test/Fixture"))
        // A test class and an excluded class, with the companion class of an excluded interface: the module doesn't compile them.
        .addClasses(classMetadata("com/example/test/FixtureTest"))
        .addClasses(classMetadata("com/example/Excluded"))
        .addClasses(classMetadata("com/example/Excluded\$-CC"))
        .build()

    val report =
      ReportAggregator()
        .aggregate(
          CoverageData(metadata, BitSet()),
          "test",
          testPackageId = "com.example.test",
          exclusions = setOf("com/example/Excluded"),
          projectClasses = setOf("com/example/test/Fixture"),
        )

    assertThat(report.packages.keys).containsExactly("com/example/test")
    assertThat(report.packages["com/example/test"]!!.classes.keys).containsExactly("com/example/test/Fixture")
  }

  @Test
  fun testCompanionClassesOfTheInterfacesThatTheProjectCompilesAreReportedAsTheInterfaces() {
    fun companionClassMetadata(interfaceName: String, sourceFile: String) =
      ClassMetadata.newBuilder()
        .setClassName("$interfaceName\$-CC")
        .setSourceFile(sourceFile)
        .addMethods(MethodMetadata.newBuilder().setName("\$default\$run").setSignature("(L$interfaceName;)V"))
    val metadata =
      CoverageMetadata.newBuilder()
        // Below API 24, d8 moves the default methods of these interfaces to companion classes, which the class files don't have. The module
        // compiles an interface in the package of its test APK, one from a source generated outside the source folders, and one that an
        // annotation marks as generated.
        .addClasses(companionClassMetadata("com/example/test/FixtureApi", "FixtureApi.kt"))
        .addClasses(companionClassMetadata("com/example/GeneratedApi", "GeneratedApi.java"))
        .addClasses(companionClassMetadata("com/example/AnnotatedApi", "AnnotatedApi.java"))
        // An interface of the tests: the module doesn't compile it.
        .addClasses(companionClassMetadata("com/example/test/TestApi", "TestApi.kt"))
        .build()

    val report =
      ReportAggregator()
        .aggregate(
          CoverageData(metadata, BitSet()),
          "test",
          testPackageId = "com.example.test",
          sourceFolders = listOf(temporaryFolder.newFolder("src")),
          projectClasses = setOf("com/example/test/FixtureApi", "com/example/GeneratedApi", "com/example/AnnotatedApi"),
          generatedCode = GeneratedCode(classes = setOf("com/example/AnnotatedApi")),
        )

    assertThat(report.packages["com/example/test"]!!.classes.keys).containsExactly("com/example/test/FixtureApi\$-CC")
    val classes = report.packages["com/example"]!!.classes
    assertThat(classes.keys).containsExactly("com/example/GeneratedApi\$-CC", "com/example/AnnotatedApi\$-CC")
    assertThat(classes["com/example/GeneratedApi\$-CC"]!!.methods.map { it.name }).containsExactly("\$default\$run")
    // The code of the interface is generated, so its companion class has no methods.
    assertThat(classes["com/example/AnnotatedApi\$-CC"]!!.methods).isEmpty()
  }

  @Test
  fun testGeneratedMethodsOfAnInterfaceAreLeftOutOfItsCompanionClass() {
    fun method(name: String, signature: String) = MethodMetadata.newBuilder().setName(name).setSignature(signature)
    val metadata =
      CoverageMetadata.newBuilder()
        // Below API 24, d8 moves the default, private and static methods of the interface to its companion class. It adds the interface as
        // the first parameter of the default and private instance methods, and prefixes their names.
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/Api\$-CC")
            .setSourceFile("Api.java")
            .addMethods(method("\$default\$generated", "(Lcom/example/Api;I)V"))
            .addMethods(method("\$private\$generatedHelper", "(Lcom/example/Api;)V"))
            .addMethods(method("generatedStatic", "()V"))
            .addMethods(method("\$default\$written", "(Lcom/example/Api;)V"))
        )
        .build()

    val report =
      ReportAggregator()
        .aggregate(
          CoverageData(metadata, BitSet()),
          "test",
          generatedCode =
            GeneratedCode(
              methods = setOf("com/example/Api.generated(I)V", "com/example/Api.generatedHelper()V", "com/example/Api.generatedStatic()V")
            ),
        )

    assertThat(report.packages["com/example"]!!.classes["com/example/Api\$-CC"]!!.methods.map { it.name })
      .containsExactly("\$default\$written")
  }

  @Test
  fun testDefaultMethodsOfAnInterfaceKeepTheirBranchesInItsCompanionClass() {
    fun method(name: String, signature: String, firstBlockId: Int) =
      MethodMetadata.newBuilder()
        .setName(name)
        .setSignature(signature)
        .addBlocks(
          BlockMetadata.newBuilder()
            .setBlockId(firstBlockId)
            .setBranchCount(2)
            .addSuccessorBlockIds(firstBlockId + 1)
            .addSuccessorBlockIds(firstBlockId + 2)
            .addLines(LineMetadata.newBuilder().setLineNumber(10 + firstBlockId).setInstructionCount(2))
        )
        .addBlocks(BlockMetadata.newBuilder().setBlockId(firstBlockId + 1).setBranchCount(1))
        .addBlocks(BlockMetadata.newBuilder().setBlockId(firstBlockId + 2).setBranchCount(1))
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/Api\$-CC")
            .setSourceFile("Api.kt")
            // A default method of the interface, which d8 moved to its companion class.
            .addMethods(method("\$default\$run", "(Lcom/example/Api;)V", firstBlockId = 0))
            // Kotlin's stub for the default arguments of a method of the interface: a static method, which d8 moves there too.
            .addMethods(method("start\$default", "(Lcom/example/Api;IILjava/lang/Object;)V", firstBlockId = 3))
        )
        .build()
    val hits = BitSet().apply { set(0, 2) }

    val report = ReportAggregator().aggregate(CoverageData(metadata, hits), "test")

    val methods = report.packages["com/example"]!!.classes["com/example/Api\$-CC"]!!.methods.associateBy { it.name }
    assertThat(methods["\$default\$run"]!!.branches).isEqualTo(Counter(covered = 1, missed = 1))
    assertThat(methods["start\$default"]!!.branches).isEqualTo(Counter(covered = 0, missed = 0))
  }

  @Test
  fun testGeneratedCodeIsLeftOut() {
    fun method(name: String, blockId: Int, line: Int) =
      MethodMetadata.newBuilder()
        .setName(name)
        .setSignature("()V")
        .addBlocks(
          BlockMetadata.newBuilder().setBlockId(blockId).addLines(LineMetadata.newBuilder().setLineNumber(line).setInstructionCount(2))
        )
    val metadata =
      CoverageMetadata.newBuilder()
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/Foo_Factory")
            .setSourceFile("Foo_Factory.java")
            .addMethods(method("get", 0, 10))
        )
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("com/example/Foo")
            .setSourceFile("Foo.kt")
            .addMethods(method("generated", 1, 20))
            .addMethods(method("written", 2, 21))
        )
        .build()
    val hits = BitSet().apply { set(0, 3) }

    val report =
      ReportAggregator()
        .aggregate(
          CoverageData(metadata, hits),
          "test",
          generatedCode = GeneratedCode(classes = setOf("com/example/Foo_Factory"), methods = setOf("com/example/Foo.generated()V")),
        )

    val pkg = report.packages["com/example"]!!
    // The class whose code is generated stays in the report, without methods, so it doesn't count as a class.
    val factory = pkg.classes["com/example/Foo_Factory"]!!
    assertThat(factory.methods).isEmpty()
    assertThat(factory.classesCounter).isEqualTo(Counter(covered = 0, missed = 0))
    assertThat(pkg.sourceFiles["Foo_Factory.java"]!!.lineMap).isEmpty()
    assertThat(pkg.classes["com/example/Foo"]!!.methods.map { it.name }).containsExactly("written")
    assertThat(pkg.sourceFiles["Foo.kt"]!!.lineMap.keys).containsExactly(21)
    assertThat(report.instructions).isEqualTo(Counter(covered = 2, missed = 0))
    assertThat(report.methodsCounter).isEqualTo(Counter(covered = 1, missed = 0))
    assertThat(report.classesCounter).isEqualTo(Counter(covered = 1, missed = 0))
  }
}
