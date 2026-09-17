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
import com.android.tools.coverage.proto.CoverageMetadataProto.CoverageHits
import com.android.tools.coverage.proto.CoverageMetadataProto.CoverageMetadata
import com.android.tools.coverage.proto.CoverageMetadataProto.LineMetadata
import com.android.tools.coverage.proto.CoverageMetadataProto.MethodMetadata
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.util.BitSet
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverageXmlGeneratorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun testEndToEndGeneration() {
    // 1. Create mock .pb files
    val metadata =
      CoverageMetadata.newBuilder()
        .setVersion(1)
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("Lcom/example/MyClass;") // Tested class
            .setSourceFile("MyClass.kt")
            .setSmap(
              """
              SMAP
              MyClass.kt
              Kotlin
              *S Kotlin
              *F
              + 1 MyClass.kt
              com/example/MyClass.kt
              *L
              10:100
              *E
              """
                .trimIndent()
            )
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("myMethod")
                .setSignature("()V")
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(0)
                    .setBranchCount(2)
                    .addLines(LineMetadata.newBuilder().setLineNumber(100).setInstructionCount(10))
                )
            )
        )
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("Lcom/example/test/MyTest;") // Tier 1: testPackageId filter
            .setSourceFile("MyTest.kt")
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("testMethod")
                .setSignature("()V")
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(1)
                    .setBranchCount(1)
                    .addLines(LineMetadata.newBuilder().setLineNumber(20).setInstructionCount(5))
                )
            )
        )
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("Lcom/example/R;") // Tier 2: Safe R filter (empty source file)
            .setSourceFile("")
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("<init>")
                .setSignature("()V")
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(2)
                    .setBranchCount(1)
                    .addLines(LineMetadata.newBuilder().setLineNumber(1).setInstructionCount(1))
                )
            )
        )
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("Lcom/example/ExcludedClass;") // Tier 3: Explicit exclusion list
            .setSourceFile("ExcludedClass.kt")
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("secret")
                .setSignature("()V")
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(3)
                    .setBranchCount(1)
                    .addLines(LineMetadata.newBuilder().setLineNumber(50).setInstructionCount(1))
                )
            )
        )
        .build()
    val metadataFile = tempFolder.newFile("metadata.pb")
    metadataFile.outputStream().use { metadata.writeTo(it) }

    val bitSet = BitSet()
    bitSet.set(0) // Hit MyClass
    bitSet.set(1) // Hit MyTest
    bitSet.set(2) // Hit generated R
    bitSet.set(3) // Hit ExcludedClass
    val hits = CoverageHits.newBuilder().setVersion(1).setHitMask(ByteString.copyFrom(bitSet.toByteArray())).build()
    val hitsFile = tempFolder.newFile("hits.pb")
    hitsFile.outputStream().use { hits.writeTo(it) }

    val outputFile = tempFolder.newFile("report.xml")

    // 2. Run generator with testPackageId AND an exclusion list
    val generator = CoverageXmlGenerator()
    generator.generate(metadataFile, hitsFile, outputFile, "test-report", "com.example.test", setOf("com.example.ExcludedClass"))

    // 3. Verify XML
    val content = outputFile.readText()

    // Structure - should have L and ; stripped
    assertThat(content).contains("<report name=\"test-report\">")
    assertThat(content).contains("<package name=\"com/example\">")
    assertThat(content).contains("<class name=\"com/example/MyClass\" sourcefilename=\"MyClass.kt\">")

    // Filter verification 1: testPackageId
    assertThat(content).doesNotContain("com/example/test")

    // Filter verification 2: Safe R
    assertThat(content).doesNotContain("<class name=\"com/example/R\"")

    // Filter verification 3: Explicit Exclusion List
    assertThat(content).doesNotContain("ExcludedClass")

    val sourceFileSection = content.substringAfter("<sourcefile name=\"MyClass.kt\">").substringBefore("</sourcefile>")
    assertThat(sourceFileSection).contains("<counter type=\"INSTRUCTION\" missed=\"0\" covered=\"10\"/>")
  }
}
